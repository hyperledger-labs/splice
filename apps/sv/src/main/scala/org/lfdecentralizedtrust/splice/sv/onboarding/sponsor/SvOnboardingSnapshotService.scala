// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import better.files.File
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  RunOnClosing,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import io.grpc.Status
import org.apache.pekko.Done
import org.apache.pekko.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.*

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Failure, Success, Try}

class SvOnboardingSnapshotService(
    config: SvOnboardingSnapshotsConfig,
    clock: Clock,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has {

  private[sponsor] val exportsDirectory: Path = config.directory match {
    case Some(directory) =>
      val downloads = File(directory) / "downloads"
      if (downloads.exists) downloads.delete()
      val exports = File(directory) / "exports"
      if (exports.exists) exports.delete()
      exports.createDirectories().path
    case None => Files.createTempDirectory("sv-onboarding-snapshots")
  }

  private val mutex = Mutex()
  private val snapshots = mutable.Map.empty[String, Snapshot]
  private val snapshotIds = mutable.Map.empty[SnapshotKey, String]
  private val pendingDeletions = mutable.Map.empty[String, Path]
  private val activeExports = mutable.Set.empty[String]
  private val exportsTerminated: Promise[Done] = Promise()

  private def shuttingDown: Boolean = isClosing || retryProvider.isClosing

  private def shutdownError =
    Status.UNAVAILABLE
      .withDescription("SV onboarding snapshot exports are shutting down")
      .asRuntimeException()

  private val queueTerminated: Promise[Done] = Promise()
  private val queue: BoundedSourceQueue[QueuedExport] =
    Source
      .queue[QueuedExport](config.queueSize.value)
      .mapAsyncUnordered(config.parallelism.value)(runExport)
      .toMat(Sink.onComplete { result =>
        queueTerminated
          .tryComplete(
            if (shuttingDown) Success(Done)
            else
              retryProvider.logTerminationAndRecoverOnShutdown(
                "onboarding snapshot export queue",
                logger,
              )(result)(TraceContext.empty)
          )
          .discard
      })(Keep.left)
      .run()

  retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name: String = "terminate onboarding snapshot export queue"
    override def done: Boolean = queueTerminated.isCompleted && exportsTerminated.isCompleted
    override def run()(implicit tc: TraceContext): Unit = stopExports()
  })(TraceContext.empty)

  private def stopExports(): Unit = blocking {
    mutex.exclusive {
      try queue.fail(shutdownError)
      catch {
        case _: IllegalStateException => ()
      }
      if (activeExports.isEmpty) exportsTerminated.trySuccess(Done).discard
    }
  }

  def prepare(key: SnapshotKey, exportTo: Path => Future[Unit])(implicit
      tc: TraceContext
  ): Future[String] = Future.fromTry(blocking {
    mutex.exclusive {
      if (shuttingDown) Failure(shutdownError)
      else
        snapshotIds
          .get(key)
          .filter(id =>
            snapshotState(id).exists {
              case SnapshotState.Failed(_) => false
              case _ => true
            }
          ) match {
          case Some(id) => Success(id)
          case None =>
            val id = UUID.randomUUID().toString
            val file = exportsDirectory.resolve(id)
            queue.offer(QueuedExport(id, file, exportTo, tc)) match {
              case Enqueued =>
                snapshots.update(id, Snapshot(key, clock.now, file, SnapshotState.Exporting))
                snapshotIds.update(key, id)
                logger.info(s"Queued onboarding snapshot export $id for $key")
                Success(id)
              case Dropped =>
                Failure(
                  Status.UNAVAILABLE
                    .withDescription(
                      s"Too many onboarding snapshot exports queued (max ${config.queueSize.value})"
                    )
                    .asRuntimeException()
                )
              case QueueClosed =>
                Failure(shutdownError)
              case QueueOfferResult.Failure(cause) => Failure(cause)
            }
        }
    }
  })

  def lookup(id: String): Option[SnapshotState] = blocking {
    mutex.exclusive {
      if (shuttingDown) None else snapshotState(id)
    }
  }

  private def snapshotState(id: String): Option[SnapshotState] =
    snapshots.get(id).map { snapshot =>
      snapshot.state match {
        case SnapshotState.Ready(file) if Files.notExists(file) =>
          val failed = SnapshotState.Failed("Snapshot file is missing")
          snapshots.update(id, snapshot.copy(state = failed))
          if (snapshotIds.get(snapshot.key).contains(id)) snapshotIds.remove(snapshot.key).discard
          failed
        case state => state
      }
    }

  def removeExpired(now: CantonTimestamp)(implicit tc: TraceContext): Future[Int] = {
    val toDelete = blocking {
      mutex.exclusive {
        val expired = snapshots.toSeq.filter { case (_, snapshot) =>
          snapshot.state != SnapshotState.Exporting &&
          snapshot.createdAt.plus(config.retention.asJava) <= now
        }
        expired.foreach { case (id, snapshot) =>
          snapshots.remove(id).discard
          if (snapshotIds.get(snapshot.key).contains(id)) snapshotIds.remove(snapshot.key).discard
          pendingDeletions.update(id, snapshot.file)
        }
        pendingDeletions.toSeq
      }
    }
    Future {
      blocking {
        toDelete.count { case (id, file) => deleteSnapshotFile(id, file) }
      }
    }
  }

  private def runExport(queued: QueuedExport): Future[Done] = {
    implicit val tc: TraceContext = queued.traceContext
    val start = blocking {
      mutex.exclusive {
        if (shuttingDown) false
        else {
          activeExports.add(queued.id).discard
          true
        }
      }
    }
    if (!start) Future.successful(Done)
    else {
      logger.info(s"Starting onboarding snapshot export ${queued.id}")
      Future
        .fromTry(Try(queued.exportTo(queued.file)))
        .flatten
        .transform { result =>
          finishExport(queued, result)
          Success(Done)
        }
        .andThen { case _ =>
          blocking {
            mutex.exclusive {
              activeExports.remove(queued.id).discard
              if (shuttingDown && activeExports.isEmpty)
                exportsTerminated.trySuccess(Done).discard
            }
          }
        }
    }
  }

  private def finishExport(queued: QueuedExport, result: Try[Unit])(implicit
      tc: TraceContext
  ): Unit = {
    blocking {
      mutex.exclusive {
        snapshots.get(queued.id).filter(_ => !shuttingDown).foreach { snapshot =>
          result match {
            case Success(()) =>
              snapshots.update(queued.id, snapshot.copy(state = SnapshotState.Ready(queued.file)))
            case Failure(ex) =>
              snapshots
                .update(queued.id, snapshot.copy(state = SnapshotState.Failed(ex.getMessage)))
              if (snapshotIds.get(snapshot.key).contains(queued.id))
                snapshotIds.remove(snapshot.key).discard
          }
        }
        if (result.isFailure) pendingDeletions.update(queued.id, queued.file)
      }
    }
    result match {
      case Success(_) => logger.info(s"Finished onboarding snapshot export ${queued.id}")
      case Failure(ex) =>
        if (shuttingDown)
          logger.info(s"Onboarding snapshot export ${queued.id} stopped by shutdown: $ex")
        else logger.warn(s"Onboarding snapshot export ${queued.id} failed", ex)
        deleteSnapshotFile(queued.id, queued.file).discard
    }
  }

  private def deleteSnapshotFile(id: String, file: Path)(implicit tc: TraceContext): Boolean =
    Try(blocking(Files.deleteIfExists(file))) match {
      case Success(deleted) =>
        val removed = blocking(mutex.exclusive(pendingDeletions.remove(id).isDefined))
        if (removed && deleted) logger.info(s"Removed onboarding snapshot file $id")
        removed
      case Failure(ex) =>
        logger.warn(s"Failed to delete onboarding snapshot file $id; cleanup will retry", ex)
        false
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit val tc: TraceContext = TraceContext.empty
    stopExports()
    Seq(
      AsyncCloseable(
        "waiting for onboarding snapshot exports to finish and cleaning up their directory",
        queueTerminated.future
          .transformWith(_ => exportsTerminated.future)
          .map { _ =>
            blocking(File(exportsDirectory).delete(swallowIOExceptions = true)).discard
          },
        timeouts.shutdownShort,
      )
    )
  }
}

object SvOnboardingSnapshotService {

  sealed trait SnapshotKey

  object SnapshotKey {
    final case class Acs(
        synchronizerId: SynchronizerId,
        targetParticipantId: ParticipantId,
        party: PartyId,
        activationTime: Instant,
    ) extends SnapshotKey
  }

  sealed trait SnapshotState

  object SnapshotState {
    case object Exporting extends SnapshotState
    final case class Ready(file: Path) extends SnapshotState
    final case class Failed(error: String) extends SnapshotState
  }

  private final case class Snapshot(
      key: SnapshotKey,
      createdAt: CantonTimestamp,
      file: Path,
      state: SnapshotState,
  )

  private final case class QueuedExport(
      id: String,
      file: Path,
      exportTo: Path => Future[Unit],
      traceContext: TraceContext,
  )
}
