// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import cats.data.NonEmptyList
import cats.syntax.traverse.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.{PekkoRetryableService, PekkoRetryingService}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageBackfillingConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdatesSegment
import org.lfdecentralizedtrust.splice.scan.store.historystart.{HistoryStart, ScanHistoryStart}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class BulkStorageBackfilling(
    config: BulkStorageBackfillingConfig,
    storageConfig: ScanStorageConfig,
    currentMigrationId: Long,
    listing: BulkObjectListing,
    copier: ObjectCopier,
    progress: BackfillingProgress,
    upperBound: BulkStorageBackfilling.BackfillUpperBound,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[BulkStorageBackfilling.Step] {

  import BulkStorageBackfilling.*

  private[backfilling] def serviceSource()(implicit tc: TraceContext): Source[Step, NotUsed] =
    mksrc().concat(Source.never)

  private[backfilling] def mksrc()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.future(progress.isComplete).flatMapConcat { complete =>
      if (complete) {
        logger.info("Bulk storage backfilling from peers already complete, nothing to do")
        Source.empty
      } else backfill()
    }

  private def backfill()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source
      .futureSource(upperBound.end.map {
        case BackfillEnd.NotYetKnown =>
          logger.debug("The history start of this Scan is not known yet, waiting")
          waitForHistoryStart().concatLazy(Source.lazySource(() => backfill()))
        case BackfillEnd.HistoryComplete =>
          logger.info(
            "This Scan holds history from genesis, there is nothing to copy from the peers"
          )
          Source.single[Step](NothingToCopy).concatLazy(finish())
        case BackfillEnd.CopyUpTo(firstOwnSegmentStart) =>
          copyUpdates(firstOwnSegmentStart)
            .concatLazy(copySnapshots(firstOwnSegmentStart))
            .concatLazy(finish())
      })
      .mapMaterializedValue(_ => NotUsed)

  private def waitForHistoryStart(): Source[Step, NotUsed] =
    Source.lazyFuture(() =>
      after(config.pollingInterval.underlying, actorSystem.scheduler)(
        Future.successful[Step](WaitingForHistoryStart)
      )
    )

  private def copyUpdates(copyUpTo: CantonTimestamp)(implicit
      tc: TraceContext
  ): Source[Step, NotUsed] =
    walk(())(_ => copyNextSegment(copyUpTo))

  private def copySnapshots(copyUpTo: CantonTimestamp)(implicit
      tc: TraceContext
  ): Source[Step, NotUsed] =
    walk(Option.empty[CantonTimestamp])(copyNextSnapshot(_, copyUpTo))

  private def finish()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.lazyFuture(() => progress.markComplete().map[Step](_ => Completed))

  private def walk[S](initial: S)(stepFrom: S => Future[Emitted[S]]): Source[Step, NotUsed] =
    Source.unfoldAsync[Option[S], Step](Some(initial)) {
      case Some(state) => stepFrom(state).map(emitted => Some((emitted.nextState, emitted.step)))
      case None => Future.successful(None)
    }

  private def continueWith[S](state: S, step: Step): Future[Emitted[S]] =
    Future.successful(Emitted(step, Some(state)))

  private def lastStep[S](step: Step): Future[Emitted[S]] =
    Future.successful(Emitted(step, None))

  private def waitThen[S](state: S, step: Step): Future[Emitted[S]] =
    after(config.pollingInterval.underlying, actorSystem.scheduler)(continueWith(state, step))

  private def copyNextSegment(copyUpTo: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Emitted[Unit]] =
    progress.readUpdatesCursor.flatMap {
      case Some(copied) if copied.toTimestamp.timestamp >= copyUpTo =>
        val upTo = copied.toTimestamp.timestamp
        logger.info(s"Update objects are copied up to $upTo, where this Scan's own segments start")
        lastStep(UpdatesCopied(upTo))
      case Some(copied) => copySegment(nextSegment(copied))
      case None =>
        firstSegment(copyUpTo).flatMap {
          case PeerListing.Available(Some(first)) => copySegment(first)
          case PeerListing.Available(None) | PeerListing.NotAvailableYet =>
            logger.debug(s"Not enough peers hold update objects up to $copyUpTo yet, waiting")
            waitThen((), WaitingForPeers(CantonTimestamp.MinValue))
          case PeerListing.NoPeerWillHold =>
            noPeerHolds((), CantonTimestamp.MinValue, s"update objects up to $copyUpTo")
        }
    }

  private def noPeerHolds[S](state: S, at: CantonTimestamp, what: String)(implicit
      tc: TraceContext
  ): Future[Emitted[S]] = {
    logger.error(
      s"No peer will ever hold $what, so this Scan cannot copy it from its peers; " +
        s"checking again in ${config.noPeerWillHoldRetryInterval}"
    )
    after(config.noPeerWillHoldRetryInterval.underlying, actorSystem.scheduler)(
      continueWith(state, NoPeerHolds(at))
    )
  }

  private def nextSegment(copied: UpdatesSegment): UpdatesSegment =
    UpdatesSegment(
      copied.toTimestamp,
      TimestampWithMigrationId(
        storageConfig.computeBulkSnapshotTimeAfter(copied.toTimestamp.timestamp),
        currentMigrationId,
      ),
    )

  private def firstSegment(copyUpTo: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[UpdatesSegment]]] =
    listing
      .updateObjectsPage(
        CantonTimestamp.MinValue,
        copyUpTo,
        config.pageSize,
        availableAt = copyUpTo,
      )
      .flatMap {
        case PeerListing.Available(objectsOnPeers) =>
          Future
            .fromTry(segmentsOf(ObjectsOnPeers.objectsIn(objectsOnPeers)))
            .map(segments => PeerListing.Available(segments.headOption))
        case PeerListing.NotAvailableYet => Future.successful(PeerListing.NotAvailableYet)
        case PeerListing.NoPeerWillHold => Future.successful(PeerListing.NoPeerWillHold)
      }

  private def copySegment(segment: UpdatesSegment)(implicit
      tc: TraceContext
  ): Future[Emitted[Unit]] = {
    val (from, to) = (segment.fromTimestamp.timestamp, segment.toTimestamp.timestamp)
    listing.updateObjectsPage(from, to, config.pageSize, availableAt = to).flatMap {
      case PeerListing.NotAvailableYet =>
        logger.debug(s"Not enough peers hold the update segment $from - $to yet, waiting")
        waitThen((), WaitingForPeers(from))
      case PeerListing.NoPeerWillHold =>
        noPeerHolds((), from, s"the update segment $from - $to")
      case PeerListing.Available(objectsOnPeers) =>
        for {
          _ <- copyFromPeers(objectsOnPeers)
          _ <- progress.persistUpdatesCursor(segment)
          step <- continueWith(
            (),
            SegmentCopied(segment, ObjectsOnPeers.objectsIn(objectsOnPeers).size),
          )
        } yield step
    }
  }

  private def copyFromPeers(objectsOnPeers: NonEmptyList[ObjectsOnPeers])(implicit
      tc: TraceContext
  ): Future[Unit] =
    MonadUtil.sequentialTraverse_(objectsOnPeers.toList)(onPeers =>
      copier.copy(onPeers.objects, onPeers.peers)
    )

  private def copyNextSnapshot(
      lastRequested: Option[CantonTimestamp],
      copyUpTo: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Emitted[Option[CantonTimestamp]]] =
    for {
      cursor <- progress.readSnapshotsCursor
      requested <- lastRequested.orElse(cursor.map(_.timestamp)) match {
        case Some(last) =>
          Future.successful[PeerListing[Option[CantonTimestamp]]](
            PeerListing.Available(Some(storageConfig.computeBulkSnapshotTimeAfter(last)))
          )
        case None =>
          firstSegment(copyUpTo).map[PeerListing[Option[CantonTimestamp]]] {
            case PeerListing.Available(first) =>
              PeerListing.Available(first.map(_.toTimestamp.timestamp))
            case PeerListing.NotAvailableYet => PeerListing.NotAvailableYet
            case PeerListing.NoPeerWillHold => PeerListing.NoPeerWillHold
          }
      }
      result <- requested match {
        case PeerListing.Available(None) | PeerListing.NotAvailableYet =>
          logger.debug("The peers list no update segment yet, waiting before walking the snapshots")
          waitThen(lastRequested, WaitingForPeers(CantonTimestamp.MinValue))
        case PeerListing.NoPeerWillHold =>
          noPeerHolds(lastRequested, CantonTimestamp.MinValue, s"update objects up to $copyUpTo")
        case PeerListing.Available(Some(ts)) if ts > copyUpTo => lastStep(SnapshotsCopied)
        case PeerListing.Available(Some(ts)) =>
          def nothingNewer(reason: String): Future[Emitted[Option[CantonTimestamp]]] = {
            logger.debug(s"$reason, waiting for the snapshot at $ts")
            waitThen(lastRequested, WaitingForPeers(ts))
          }
          listing.snapshotObjectsAtOrBefore(ts).flatMap {
            case PeerListing.NotAvailableYet =>
              nothingNewer(s"Not enough peers hold the snapshot at $ts yet")
            case PeerListing.NoPeerWillHold =>
              noPeerHolds(lastRequested, ts, s"the snapshot at $ts")
            case PeerListing.Available(None) =>
              nothingNewer(s"The peers have no committed snapshot at $ts")
            case PeerListing.Available(Some(snapshot))
                if cursor.exists(_.timestamp >= snapshot.recordTime) =>
              nothingNewer(s"The peers have no snapshot after ${snapshot.recordTime}")
            case PeerListing.Available(Some(snapshot))
                if snapshot.perEncoding.forall(_.objects.isEmpty) =>
              val skipped = TimestampWithMigrationId(snapshot.recordTime, currentMigrationId)
              for {
                _ <- progress.persistSnapshotsCursor(skipped)
                step <- continueWith(
                  Some(snapshot.recordTime),
                  SnapshotSkipped(snapshot.recordTime),
                )
              } yield step
            case PeerListing.Available(Some(snapshot)) =>
              val copied = TimestampWithMigrationId(snapshot.recordTime, currentMigrationId)
              for {
                _ <- copyFromPeers(snapshot.perEncoding)
                _ <- progress.persistSnapshotsCursor(copied)
                step <- continueWith(
                  Some(snapshot.recordTime),
                  SnapshotCopied(copied, ObjectsOnPeers.objectsIn(snapshot.perEncoding).size),
                )
              } yield step
          }
      }
    } yield result

  private def segmentsOf(objects: Seq[ObjectKeyAndChecksum]): Try[Seq[UpdatesSegment]] =
    objects
      .map(obj => storageConfig.getSegmentFolderOfObjectKey(obj.key))
      .distinct
      .toList
      .traverse(segmentOfFolder)
      .map(_.sortBy(_.fromTimestamp))

  private def segmentOfFolder(folder: String): Try[UpdatesSegment] =
    storageConfig.getStartAndEndTimestampsForFolder(folder) match {
      case Right((from, to)) =>
        Success(
          UpdatesSegment(
            TimestampWithMigrationId(from, currentMigrationId),
            TimestampWithMigrationId(to, currentMigrationId),
          )
        )
      case Left(err) => Failure(new IllegalStateException(err))
    }

  override def asPekkoRetryingService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[Step] =
    withNewTrace(description) { implicit traceContext => _ =>
      new PekkoRetryingService(
        serviceSource(),
        Sink.ignore,
        automationConfig,
        backoffClock,
        description,
        retryProvider,
        loggerFactory,
      )
    }
}

object BulkStorageBackfilling {
  val description = "BulkStorageBackfilling"

  /** How far this Scan has to copy from its peers: up to its first own segment start, nothing (its history starts at
    * genesis), or not known yet (its history start is not recorded).
    */
  sealed trait BackfillEnd

  object BackfillEnd {
    final case class CopyUpTo(firstOwnSegmentStart: CantonTimestamp) extends BackfillEnd
    case object HistoryComplete extends BackfillEnd
    case object NotYetKnown extends BackfillEnd
  }

  trait BackfillUpperBound {
    def end(implicit tc: TraceContext): Future[BackfillEnd]
  }

  /** Derives the [[BackfillEnd]] from the recorded history start. */
  class UpToFirstOwnSegment(historyStart: ScanHistoryStart, storageConfig: ScanStorageConfig)(
      implicit ec: ExecutionContext
  ) extends BackfillUpperBound {
    override def end(implicit tc: TraceContext): Future[BackfillEnd] =
      historyStart.get.map {
        case None => BackfillEnd.NotYetKnown
        case Some(HistoryStart.Genesis) => BackfillEnd.HistoryComplete
        case Some(start: HistoryStart.From) =>
          BackfillEnd.CopyUpTo(start.firstOwnSegmentStart(storageConfig))
      }
  }

  private final case class Emitted[+S](step: Step, nextState: Option[S])

  sealed trait Step
  final case class SegmentCopied(segment: UpdatesSegment, objects: Int) extends Step
  final case class UpdatesCopied(upTo: CantonTimestamp) extends Step
  final case class SnapshotSkipped(at: CantonTimestamp) extends Step
  final case class SnapshotCopied(snapshot: TimestampWithMigrationId, objects: Int) extends Step
  case object SnapshotsCopied extends Step
  final case class WaitingForPeers(at: CantonTimestamp) extends Step
  final case class NoPeerHolds(at: CantonTimestamp) extends Step
  case object WaitingForHistoryStart extends Step
  case object NothingToCopy extends Step
  case object Completed extends Step
}
