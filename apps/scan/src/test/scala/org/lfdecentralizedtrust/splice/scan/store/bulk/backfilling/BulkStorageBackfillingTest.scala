// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import cats.data.NonEmptyList
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.pattern
import org.apache.pekko.stream.scaladsl.Sink
import org.slf4j.event.Level
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.scan.config.{
  BulkStorageBackfillingConfig,
  ScanStorageConfigs,
}
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdatesSegment
import org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling.BulkStorageBackfilling.{
  BackfillEnd,
  BackfillUpperBound,
}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class BulkStorageBackfillingTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  private val storageConfig = ScanStorageConfigs.scanStorageConfigV1
  private val migrationId = 0L

  private def ts(day: Int): CantonTimestamp =
    CantonTimestamp.tryFromInstant(Instant.parse(f"2026-01-$day%02dT00:00:00Z"))

  private def folder(fromDay: Int, toDay: Int): String =
    storageConfig.getSegmentFolder(ts(fromDay), Some(ts(toDay)))

  private def segment(fromDay: Int, toDay: Int): UpdatesSegment =
    UpdatesSegment(
      TimestampWithMigrationId(ts(fromDay), migrationId),
      TimestampWithMigrationId(ts(toDay), migrationId),
    )

  private def objects(folderName: String, n: Int): Seq[ObjectKeyAndChecksum] =
    (0 until n).map(i => ObjectKeyAndChecksum(s"$folderName/updates_compact_json_$i.zstd", s"d-$i"))

  private def snapshotAt(day: Int): (CantonTimestamp, Seq[ObjectKeyAndChecksum]) =
    ts(day) -> Seq(ObjectKeyAndChecksum(s"${ts(day)}/ACS_compact_json_0.zstd", s"s-$day"))

  private class InMemoryProgress extends BackfillingProgress {
    val updates = new AtomicReference[Option[UpdatesSegment]](None)
    val snapshots = new AtomicReference[Option[TimestampWithMigrationId]](None)
    val complete = new AtomicReference[Int](0)
    override def readUpdatesCursor(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(updates.get())
    override def persistUpdatesCursor(segment: UpdatesSegment)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ) = Future.successful(updates.set(Some(segment)))
    override def readSnapshotsCursor(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(snapshots.get())
    override def persistSnapshotsCursor(ts: TimestampWithMigrationId)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ) = Future.successful(snapshots.set(Some(ts)))
    override def isComplete(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(complete.get() > 0)
    override def markComplete()(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(complete.updateAndGet(_ + 1)).map(_ => ())
    override def resetCompletion()(implicit tc: TraceContext, ec: ExecutionContext) =
      Future.successful(complete.set(0))
  }

  private val peers = Seq(Uri("http://peer-1"), Uri("http://peer-2"))

  private def folderRange(name: String): (CantonTimestamp, CantonTimestamp) =
    storageConfig.getStartAndEndTimestampsForFolder(name) match {
      case Right(range) => range
      case Left(err) => throw new IllegalStateException(err)
    }

  private class FakeListing(
      folders: () => Seq[(String, Seq[ObjectKeyAndChecksum])],
      snapshotsByTime: () => Seq[(CantonTimestamp, Seq[ObjectKeyAndChecksum])],
      val updateListingsCallCount: AtomicInteger = new AtomicInteger(0),
      noPeerWillHold: Boolean = false,
      peersPerEncoding: NonEmptyList[Seq[Uri]] = NonEmptyList.one(peers),
  ) extends BulkObjectListing {

    private def perEncoding(objects: Seq[ObjectKeyAndChecksum]): NonEmptyList[ObjectsOnPeers] =
      peersPerEncoding.map(ObjectsOnPeers(objects, _))

    private def wholeFoldersWithin(
        pageSize: Int,
        folders: Seq[(String, Seq[ObjectKeyAndChecksum])],
    ): Seq[(String, Seq[ObjectKeyAndChecksum])] = {
      val objectsUpToEachFolder = folders.map(_._2.size).scanLeft(0)(_ + _).tail
      folders
        .zip(objectsUpToEachFolder)
        .takeWhile { case (_, objectsSoFar) => objectsSoFar <= pageSize }
        .map { case (folder, _) => folder }
    }

    override def updateObjectsPage(
        startRecordTime: CantonTimestamp,
        endRecordTime: CantonTimestamp,
        pageSize: Int,
        availableAt: CantonTimestamp,
    )(implicit
        tc: TraceContext
    ): Future[PeerListing[NonEmptyList[ObjectsOnPeers]]] = {
      updateListingsCallCount.incrementAndGet()
      val held = folders()
      if (noPeerWillHold) Future.successful(PeerListing.NoPeerWillHold)
      else if (!held.lastOption.exists { case (name, _) => folderRange(name)._2 >= availableAt })
        Future.successful(PeerListing.NotAvailableYet)
      else {
        val inRange = held.filter { case (name, _) =>
          val (from, to) = folderRange(name)
          to > startRecordTime && from < endRecordTime
        }
        val page = wholeFoldersWithin(pageSize, inRange)
        if (page.isEmpty && inRange.nonEmpty)
          Future.failed(new IllegalArgumentException("Limit too low for a single folder"))
        else
          Future.successful(PeerListing.Available(perEncoding(page.flatMap(_._2))))
      }
    }

    override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
        tc: TraceContext
    ): Future[PeerListing[Option[SnapshotOnPeers]]] = {
      val snapshots = snapshotsByTime()
      if (!snapshots.lastOption.exists(_._1 >= recordTime))
        Future.successful(PeerListing.NotAvailableYet)
      else {
        val grid = storageConfig.computeBulkSnapshotTimeAtOrBefore(recordTime)
        Future.successful(
          PeerListing.Available(
            snapshots.collectFirst {
              case (t, objs) if t == grid => SnapshotOnPeers(grid, perEncoding(objs))
            }
          )
        )
      }
    }
  }

  private class ListingWith(
      updates: => Future[PeerListing[NonEmptyList[ObjectsOnPeers]]] =
        Future.successful(PeerListing.NotAvailableYet),
      snapshot: => Future[PeerListing[Option[SnapshotOnPeers]]] =
        Future.successful(PeerListing.NotAvailableYet),
  ) extends BulkObjectListing {
    override def updateObjectsPage(
        startRecordTime: CantonTimestamp,
        endRecordTime: CantonTimestamp,
        pageSize: Int,
        availableAt: CantonTimestamp,
    )(implicit tc: TraceContext): Future[PeerListing[NonEmptyList[ObjectsOnPeers]]] = updates

    override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
        tc: TraceContext
    ): Future[PeerListing[Option[SnapshotOnPeers]]] = snapshot
  }

  private class RecordingCopier extends ObjectCopier {
    val copied = new AtomicReference[Vector[String]](Vector.empty)
    val peersSeen = new AtomicReference[Vector[Seq[Uri]]](Vector.empty)
    val copiesWithPeers = new AtomicReference[Vector[(Seq[String], Seq[Uri])]](Vector.empty)
    override def copy(objects: Seq[ObjectKeyAndChecksum], peers: Seq[Uri])(implicit
        tc: TraceContext
    ) = {
      peersSeen.updateAndGet(_ :+ peers)
      copiesWithPeers.updateAndGet(_ :+ (objects.map(_.key) -> peers))
      Future.successful(copied.updateAndGet(_ ++ objects.map(_.key))).map(_ => ())
    }
  }

  private class SequenceBound(ends: BackfillEnd*) extends BackfillUpperBound {
    private val remaining = new AtomicReference[List[BackfillEnd]](ends.toList)
    override def end(implicit tc: TraceContext): Future[BackfillEnd] =
      Future.successful(remaining.getAndUpdate {
        case _ :: (rest @ (_ :: _)) => rest
        case last => last
      }.head)
  }

  private val folders = Seq(
    folder(1, 2) -> objects(folder(1, 2), 2),
    folder(2, 3) -> objects(folder(2, 3), 2),
    folder(3, 4) -> objects(folder(3, 4), 1),
  )
  private val snapshots = Seq(snapshotAt(2), snapshotAt(3), snapshotAt(4))

  private def backfilling(
      progress: InMemoryProgress,
      copier: RecordingCopier,
      listing: BulkObjectListing = new FakeListing(() => folders, () => snapshots),
      upperBound: BackfillUpperBound = new SequenceBound(BackfillEnd.CopyUpTo(ts(4))),
      pageSize: Int = 3,
  ) =
    service(progress, copier, listing, upperBound, pageSize).mksrc().runWith(Sink.seq)

  private def service(
      progress: InMemoryProgress,
      copier: RecordingCopier,
      listing: BulkObjectListing,
      upperBound: BackfillUpperBound,
      pageSize: Int,
  ) =
    new BulkStorageBackfilling(
      BulkStorageBackfillingConfig(
        enabled = true,
        pageSize = pageSize,
        pollingInterval = NonNegativeFiniteDuration.ofMillis(10),
        noPeerWillHoldRetryInterval = NonNegativeFiniteDuration.ofMillis(10),
      ),
      storageConfig,
      migrationId,
      listing,
      copier,
      progress,
      upperBound,
      loggerFactory,
    )

  "BulkStorageBackfilling" should {
    "copy every update object and snapshot in order, then mark completion once" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      backfilling(progress, copier).map { steps =>
        copier.copied.get() shouldBe (folders.flatMap(_._2) ++ snapshots.flatMap(_._2)).map(_.key)
        progress.updates.get() shouldBe Some(segment(3, 4))
        progress.snapshots.get() shouldBe Some(TimestampWithMigrationId(ts(4), migrationId))
        progress.complete.get() shouldBe 1
        steps.last shouldBe BulkStorageBackfilling.Completed
      }
    }

    "start the snapshot walk at the end of the first segment" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      backfilling(progress, copier).map { steps =>
        steps.collect { case s: BulkStorageBackfilling.SnapshotCopied =>
          s.snapshot.timestamp
        } shouldBe
          Seq(ts(2), ts(3), ts(4))
        steps.collect { case s: BulkStorageBackfilling.SnapshotSkipped => s } shouldBe empty
      }
    }

    "list one segment per call and copy it only from the peers that hold it" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      backfilling(progress, copier, pageSize = 2).map { steps =>
        steps.collect { case s: BulkStorageBackfilling.SegmentCopied =>
          (s.segment, s.objects)
        } shouldBe
          Seq(segment(1, 2) -> 2, segment(2, 3) -> 2, segment(3, 4) -> 1)
        copier.copied.get().size shouldBe 8
        forAll(copier.peersSeen.get())(_ shouldBe peers)
      }
    }

    "copy each encoding only from the peers that agreed on that encoding" in {
      val compactJsonPeers = Seq(Uri("http://compact-json-peer"))
      val protobufJsonPeers = Seq(Uri("http://protobuf-json-peer"))
      val copier = new RecordingCopier
      val listing = new FakeListing(
        () => folders,
        () => snapshots,
        peersPerEncoding = NonEmptyList.of(compactJsonPeers, protobufJsonPeers),
      )
      backfilling(new InMemoryProgress, copier, listing).map { _ =>
        val peersPerCopy = copier.copiesWithPeers.get().map(_._2)
        peersPerCopy.distinct should contain theSameElementsAs Seq(
          compactJsonPeers,
          protobufJsonPeers,
        )
        peersPerCopy.count(_ == compactJsonPeers) shouldBe
          peersPerCopy.count(_ == protobufJsonPeers)
      }
    }

    "resume from the persisted cursors and skip what was already copied" in {
      val progress = new InMemoryProgress
      progress.updates.set(Some(segment(2, 3)))
      progress.snapshots.set(Some(TimestampWithMigrationId(ts(2), migrationId)))
      val copier = new RecordingCopier
      backfilling(progress, copier).map { _ =>
        copier.copied.get() shouldBe (folders(2)._2 ++ snapshots(1)._2 ++ snapshots(2)._2)
          .map(_.key)
        progress.complete.get() shouldBe 1
      }
    }

    "persist the snapshot cursor when it skips an empty snapshot" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      val listing = new FakeListing(
        () => folders,
        () => Seq(snapshotAt(2), ts(3) -> Seq.empty, snapshotAt(4)),
      )
      backfilling(progress, copier, listing, new SequenceBound(BackfillEnd.CopyUpTo(ts(3)))).map {
        steps =>
          steps.collect { case s: BulkStorageBackfilling.SnapshotSkipped => s.at } shouldBe
            Seq(ts(3))
          progress.snapshots.get() shouldBe Some(TimestampWithMigrationId(ts(3), migrationId))
      }
    }

    "advance the update cursor over an empty segment" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      val withEmptySegment = Seq(folders(0), folder(2, 3) -> Seq.empty, folders(2))
      backfilling(progress, copier, new FakeListing(() => withEmptySegment, () => snapshots)).map {
        steps =>
          steps.collect { case s: BulkStorageBackfilling.SegmentCopied =>
            (s.segment, s.objects)
          } shouldBe
            Seq(segment(1, 2) -> 2, segment(2, 3) -> 0, segment(3, 4) -> 1)
          progress.updates.get() shouldBe Some(segment(3, 4))
      }
    }

    "wait when the peers only have a snapshot this Scan already copied" in {
      val progress = new InMemoryProgress
      progress.updates.set(Some(segment(3, 4)))
      progress.snapshots.set(Some(TimestampWithMigrationId(ts(3), migrationId)))
      val copier = new RecordingCopier
      val onlyOlderSnapshot = new ListingWith(
        snapshot = Future.successful(
          PeerListing.Available(
            Some(SnapshotOnPeers(ts(3), NonEmptyList.one(ObjectsOnPeers(snapshotAt(3)._2, peers))))
          )
        )
      )
      service(
        progress,
        copier,
        onlyOlderSnapshot,
        new SequenceBound(BackfillEnd.CopyUpTo(ts(4))),
        pageSize = 3,
      ).mksrc().take(3).runWith(Sink.seq).map { steps =>
        steps shouldBe Seq[BulkStorageBackfilling.Step](
          BulkStorageBackfilling.UpdatesCopied(ts(4)),
          BulkStorageBackfilling.WaitingForPeers(ts(4)),
          BulkStorageBackfilling.WaitingForPeers(ts(4)),
        )
        copier.copied.get() shouldBe empty
        progress.snapshots.get() shouldBe Some(TimestampWithMigrationId(ts(3), migrationId))
      }
    }

    "fail the stream when the peers disagree on a listing, so the service restarts" in {
      val badGateway = HttpErrorWithHttpCode(StatusCodes.BadGateway, "peers disagree")
      val disagreeing = new ListingWith(updates = Future.failed(badGateway))
      backfilling(new InMemoryProgress, new RecordingCopier, disagreeing).failed.map {
        _ shouldBe badGateway
      }
    }

    "keep the service stream open after completion, so the retrying service does not restart it" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      service(
        progress,
        copier,
        new FakeListing(() => folders, () => snapshots),
        new SequenceBound(BackfillEnd.HistoryComplete),
        pageSize = 3,
      ).serviceSource()
        .completionTimeout(300.millis)
        .runWith(Sink.ignore)
        .failed
        .map { failure =>
          failure shouldBe a[java.util.concurrent.TimeoutException]
          progress.complete.get() shouldBe 1
        }
    }

    "do nothing once complete" in {
      val progress = new InMemoryProgress
      progress.complete.set(1)
      val copier = new RecordingCopier
      backfilling(progress, copier).map { steps =>
        steps shouldBe empty
        copier.copied.get() shouldBe empty
      }
    }

    "wait while the peers have no objects yet" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      service(
        progress,
        copier,
        new FakeListing(() => Seq.empty, () => Seq.empty),
        new SequenceBound(BackfillEnd.CopyUpTo(ts(4))),
        pageSize = 3,
      ).mksrc().take(3).runWith(Sink.seq).map { steps =>
        steps shouldBe Seq.fill(3)(BulkStorageBackfilling.WaitingForPeers(CantonTimestamp.MinValue))
        copier.copied.get() shouldBe empty
        progress.complete.get() shouldBe 0
      }
    }

    "log an error and wait when no peer will ever hold the objects it needs" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      loggerFactory
        .assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
          service(
            progress,
            copier,
            new FakeListing(() => folders, () => snapshots, noPeerWillHold = true),
            new SequenceBound(BackfillEnd.CopyUpTo(ts(4))),
            pageSize = 3,
          ).mksrc()
            .take(2)
            .runWith(Sink.seq)
            .flatMap(steps =>
              pattern.after(500.millis, actorSystem.scheduler)(Future.successful(steps))
            ),
          logEntries => {
            logEntries.size should be >= 2
            forAll(logEntries)(
              _.errorMessage should include("No peer will ever hold update objects up to")
            )
          },
        )
        .map { steps =>
          steps shouldBe Seq.fill(2)(BulkStorageBackfilling.NoPeerHolds(CantonTimestamp.MinValue))
          copier.copied.get() shouldBe empty
          progress.complete.get() shouldBe 0
        }
    }

    "set the marker without copying when this Scan holds history from genesis" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      val listing = new FakeListing(() => folders, () => snapshots)
      backfilling(progress, copier, listing, new SequenceBound(BackfillEnd.HistoryComplete)).map {
        steps =>
          steps shouldBe Seq[BulkStorageBackfilling.Step](
            BulkStorageBackfilling.NothingToCopy,
            BulkStorageBackfilling.Completed,
          )
          copier.copied.get() shouldBe empty
          listing.updateListingsCallCount.get() shouldBe 0
          progress.complete.get() shouldBe 1
      }
    }

    "wait until the history start is known" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      backfilling(
        progress,
        copier,
        upperBound = new SequenceBound(
          BackfillEnd.NotYetKnown,
          BackfillEnd.NotYetKnown,
          BackfillEnd.HistoryComplete,
        ),
      ).map { steps =>
        steps shouldBe Seq[BulkStorageBackfilling.Step](
          BulkStorageBackfilling.WaitingForHistoryStart,
          BulkStorageBackfilling.WaitingForHistoryStart,
          BulkStorageBackfilling.NothingToCopy,
          BulkStorageBackfilling.Completed,
        )
        progress.complete.get() shouldBe 1
      }
    }

    "copy only the segments before the first own segment and the snapshots up to its start" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      backfilling(
        progress,
        copier,
        upperBound = new SequenceBound(BackfillEnd.CopyUpTo(ts(3))),
        pageSize = 10,
      ).map { _ =>
        copier.copied.get() shouldBe
          (folders(0)._2 ++ folders(1)._2 ++ snapshots(0)._2 ++ snapshots(1)._2).map(_.key)
        progress.updates.get() shouldBe Some(segment(2, 3))
        progress.snapshots.get() shouldBe Some(TimestampWithMigrationId(ts(3), migrationId))
        progress.complete.get() shouldBe 1
      }
    }

    "wait until the peers hold everything up to the first own segment" in {
      val progress = new InMemoryProgress
      val copier = new RecordingCopier
      val updateListingsCallCount = new AtomicInteger(0)
      def caughtUp = updateListingsCallCount.get() > 2
      val listing = new FakeListing(
        () => if (caughtUp) folders else folders.take(2),
        () => if (caughtUp) snapshots else snapshots.take(2),
        updateListingsCallCount,
      )
      backfilling(
        progress,
        copier,
        listing,
        new SequenceBound(BackfillEnd.CopyUpTo(ts(4))),
        pageSize = 10,
      ).map { steps =>
        steps should contain(BulkStorageBackfilling.WaitingForPeers(CantonTimestamp.MinValue))
        copier.copied.get() shouldBe
          (folders.flatMap(_._2) ++ snapshots.flatMap(_._2)).map(_.key)
        progress.updates.get() shouldBe Some(segment(3, 4))
        progress.snapshots.get() shouldBe Some(TimestampWithMigrationId(ts(4), migrationId))
        progress.complete.get() shouldBe 1
      }
    }
  }
}
