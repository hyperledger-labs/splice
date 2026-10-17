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
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.syntax.*
import io.grpc.Status
import org.apache.pekko.Done
import org.apache.pekko.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{Materializer, QueueOfferResult}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}
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
    case Some(directory) => (File(directory) / "exports").createDirectories().path
    case None => Files.createTempDirectory("sv-onboarding-snapshots")
  }

  private val mutex = Mutex()
  private val jobs = mutable.Map.empty[SvOnboardingSnapshotId, Snapshot]

  private def shuttingDown: Boolean = isClosing || retryProvider.isClosing

  private def shutdownError =
    Status.UNAVAILABLE
      .withDescription("SV onboarding snapshot exports are shutting down")
      .asRuntimeException()

  private def directory(id: SvOnboardingSnapshotId): Path = exportsDirectory.resolve(id.value)

  private def storedIds: Seq[SvOnboardingSnapshotId] =
    File(exportsDirectory).children.flatMap { entry =>
      val name = entry.name
      Try(UUID.fromString(name)).toOption
        .filter(_.toString == name)
        .map(_ => SvOnboardingSnapshotId(name))
    }.toSeq

  private def readSnapshot(
      id: SvOnboardingSnapshotId
  )(implicit tc: TraceContext): Option[Snapshot] = {
    val validId = Try(UUID.fromString(id.value)).toOption.exists(_.toString == id.value)
    if (!validId) None
    else {
      val metadata = directory(id).resolve("metadata.json")
      if (!Files.isRegularFile(metadata)) None
      else
        Try {
          val persisted =
            decode[PersistedSnapshot](Files.readString(metadata)).fold(throw _, identity)
          val file = directory(id).resolve("snapshot")
          val state: SnapshotState =
            if (Files.notExists(file)) SnapshotState.Failed("Snapshot file is missing")
            else if (!Files.isRegularFile(file) || Files.size(file) != persisted.size)
              SnapshotState.Failed("Snapshot file size has changed")
            else SnapshotState.Ready(file)
          Snapshot(
            SnapshotKey.Acs(
              SynchronizerId.tryFromString(persisted.synchronizerId),
              PartyId.tryFromProtoPrimitive(persisted.party),
              Instant.parse(persisted.activationTime),
            ),
            CantonTimestamp.assertFromInstant(Instant.parse(persisted.createdAt)),
            state,
          )
        } match {
          case Success(snapshot) => Some(snapshot)
          case Failure(error) =>
            logger.warn(s"Cannot recover onboarding snapshot $id; it will be removed", error)
            None
        }
    }
  }

  private def deleteSnapshot(id: SvOnboardingSnapshotId)(implicit tc: TraceContext): Boolean =
    Try {
      val dir = directory(id)
      if (Files.isDirectory(dir)) {
        Seq("metadata.json", "metadata.tmp", "snapshot").foreach(name =>
          Files.deleteIfExists(dir.resolve(name)).discard
        )
      }
      Files.deleteIfExists(dir)
    } match {
      case Success(_) =>
        logger.info(s"Removed onboarding snapshot file $id")
        true
      case Failure(error) =>
        logger.warn(s"Failed to delete onboarding snapshot file $id; cleanup will retry", error)
        false
    }

  private def removeExpiredNow(now: CantonTimestamp)(implicit tc: TraceContext): Int = {
    storedIds.count { id =>
      if (jobs.contains(id)) false
      else {
        val keep = readSnapshot(id).exists { snapshot =>
          snapshot.state.isInstanceOf[SnapshotState.Ready] &&
          snapshot.createdAt.plus(config.retention.asJava) > now
        }
        !keep && deleteSnapshot(id)
      }
    }
  }

  private def removeExpiredDownloads(now: Instant)(implicit tc: TraceContext): Int =
    config.directory.toList
      .flatMap { root =>
        val downloads = File(root) / "downloads"
        if (downloads.isDirectory) downloads.children.toSeq else Seq.empty
      }
      .count { download =>
        Try {
          val expired = (Iterator.single(download) ++ download.listRecursively).forall(file =>
            !Files
              .getLastModifiedTime(file.path)
              .toInstant
              .plus(config.retention.asJava)
              .isAfter(now)
          )
          if (expired) download.delete().discard
          expired
        } match {
          case Success(removed) => removed
          case Failure(error) =>
            logger.warn(
              s"Failed to clean up onboarding download ${download.name}; cleanup will retry",
              error,
            )
            false
        }
      }

  blocking(removeExpiredNow(clock.now)(TraceContext.empty)).discard
  blocking(removeExpiredDownloads(Instant.now())(TraceContext.empty)).discard

  private val (queue, queueTerminated) = Source
    .queue[QueuedExport](config.queueSize.value)
    .mapAsyncUnordered(config.parallelism.value)(runExport)
    .toMat(Sink.ignore)(Keep.both)
    .run()

  queueTerminated.onComplete { result =>
    if (!shuttingDown)
      retryProvider
        .logTerminationAndRecoverOnShutdown("onboarding snapshot export queue", logger)(result)(
          TraceContext.empty
        )
        .discard
  }

  retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name: String = "terminate onboarding snapshot export queue"
    override def done: Boolean = queueTerminated.isCompleted
    override def run()(implicit tc: TraceContext): Unit = stopExports()
  })(TraceContext.empty)

  private def stopExports(): Unit =
    try queue.complete()
    catch {
      case _: IllegalStateException => ()
    }

  def prepare(key: SnapshotKey, exportTo: Path => Future[Unit])(implicit
      tc: TraceContext
  ): Future[SvOnboardingSnapshotId] = Future.fromTry(Try(blocking {
    mutex.exclusive {
      if (shuttingDown) throw shutdownError
      val existing = jobs
        .collectFirst {
          case (id, snapshot) if snapshot.key == key =>
            id
        }
        .orElse {
          storedIds.find(id =>
            readSnapshot(id).exists(snapshot =>
              snapshot.key == key && snapshot.state.isInstanceOf[SnapshotState.Ready] &&
                snapshot.createdAt.plus(config.retention.asJava) > clock.now
            )
          )
        }
      existing.getOrElse {
        val id = SvOnboardingSnapshotId(UUID.randomUUID().toString)
        val file = directory(id).resolve("snapshot")
        queue.offer(QueuedExport(id, file, exportTo, tc)) match {
          case Enqueued =>
            jobs.update(id, Snapshot(key, clock.now, SnapshotState.Exporting))
            logger.info(s"Queued onboarding snapshot export $id for $key")
            id
          case Dropped =>
            throw Status.UNAVAILABLE
              .withDescription(
                s"Too many onboarding snapshot exports queued (max ${config.queueSize.value})"
              )
              .asRuntimeException()
          case QueueClosed => throw shutdownError
          case QueueOfferResult.Failure(cause) => throw cause
        }
      }
    }
  }))

  def lookup(id: SvOnboardingSnapshotId): Option[SnapshotState] = blocking {
    mutex.exclusive {
      if (shuttingDown) None
      else jobs.get(id).orElse(readSnapshot(id)(TraceContext.empty)).map(_.state)
    }
  }

  def removeExpired(now: CantonTimestamp)(implicit tc: TraceContext): Future[Int] = Future {
    blocking {
      val exportsRemoved = mutex.exclusive(removeExpiredNow(now))
      exportsRemoved + removeExpiredDownloads(Instant.now())
    }
  }

  private def runExport(queued: QueuedExport): Future[Done] = {
    implicit val tc: TraceContext = queued.traceContext
    val start = blocking(mutex.exclusive(!shuttingDown))
    if (!start) Future.successful(Done)
    else {
      logger.info(s"Starting onboarding snapshot export ${queued.id}")
      Future
        .fromTry(Try {
          Files.createDirectories(queued.file.getParent)
          queued.exportTo(queued.file)
        })
        .flatten
        .transform { result =>
          blocking {
            mutex.exclusive {
              jobs.get(queued.id).foreach { snapshot =>
                val published = result.flatMap { _ =>
                  Try {
                    val SnapshotKey.Acs(synchronizerId, party, activationTime) = snapshot.key
                    val metadata = PersistedSnapshot(
                      synchronizerId.toProtoPrimitive,
                      party.toProtoPrimitive,
                      activationTime.toString,
                      snapshot.createdAt.toInstant.toString,
                      Files.size(queued.file),
                    )
                    val temporary = directory(queued.id).resolve("metadata.tmp")
                    Files.writeString(temporary, metadata.asJson.noSpaces)
                    Files.move(
                      temporary,
                      directory(queued.id).resolve("metadata.json"),
                      StandardCopyOption.ATOMIC_MOVE,
                    )
                    ()
                  }
                }
                jobs.remove(queued.id).discard
                published match {
                  case Success(_) =>
                    logger.info(s"Finished onboarding snapshot export ${queued.id}")
                  case Failure(error) =>
                    if (shuttingDown)
                      logger.info(
                        s"Onboarding snapshot export ${queued.id} stopped by shutdown: $error"
                      )
                    else logger.warn(s"Onboarding snapshot export ${queued.id} failed", error)
                    if (Files.exists(directory(queued.id))) deleteSnapshot(queued.id).discard
                }
              }
            }
          }
          Success(Done)
        }
    }
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit val tc: TraceContext = TraceContext.empty
    stopExports()
    Seq(
      AsyncCloseable(
        "waiting for onboarding snapshot exports to finish",
        queueTerminated.map { done =>
          if (config.directory.isEmpty)
            blocking(File(exportsDirectory).delete(swallowIOExceptions = true)).discard
          done
        },
        timeouts.shutdownShort,
      )
    )
  }
}

object SvOnboardingSnapshotService {
  sealed trait SnapshotKey
  object SnapshotKey {
    final case class Acs(synchronizerId: SynchronizerId, party: PartyId, activationTime: Instant)
        extends SnapshotKey
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
      state: SnapshotState,
  )
  private final case class QueuedExport(
      id: SvOnboardingSnapshotId,
      file: Path,
      exportTo: Path => Future[Unit],
      traceContext: TraceContext,
  )

  private final case class PersistedSnapshot(
      synchronizerId: String,
      party: String,
      activationTime: String,
      createdAt: String,
      size: Long,
  )
  private implicit val encoder: Encoder[PersistedSnapshot] = deriveEncoder
  private implicit val decoder: Decoder[PersistedSnapshot] = deriveDecoder
}
