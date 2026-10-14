// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.FileIO
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse,
  SnapshotPreparation,
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

final class SvOnboardingSnapshotDownload private (
    val directory: Path,
    preparationTimeout: FiniteDuration,
    retryProvider: RetryProvider,
) extends NamedLogging {

  override val loggerFactory: NamedLoggerFactory = retryProvider.loggerFactory

  private val preparationDeadline = preparationTimeout.fromNow
  private val lastPendingLog =
    new AtomicReference[Option[(Option[String], String, Deadline)]](None)

  private def preparationTimedOut = new RetryProvider.QuietNonRetryableException(
    s"Timed out waiting for the sponsor to prepare an onboarding snapshot after $preparationTimeout. " +
      "Check the sponsor's export queue and readiness, or increase onboarding-snapshots.preparation-timeout."
  )

  private def waitForPreparation[A](id: Option[String], state: String, delay: FiniteDuration)(
      next: => Future[A]
  )(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[A] = {
    val remaining = preparationDeadline.timeLeft
    if (remaining <= Duration.Zero) Future.failed(preparationTimedOut)
    else {
      val wait = delay.min(remaining)
      val message =
        s"Waiting for onboarding snapshot ${id.getOrElse("(ID not yet assigned)")}: state=$state; polling again in $wait."
      if (
        lastPendingLog.get().forall { case (previousId, previousState, nextLog) =>
          previousId != id || previousState != state || nextLog.isOverdue()
        }
      ) {
        logger.info(message)
        lastPendingLog.set(Some((id, state, 1.minute.fromNow)))
      } else logger.debug(message)
      retryProvider
        .waitUnlessShutdown(
          org.apache.pekko.pattern.after(wait, mat.system.scheduler)(Future.unit)
        )
        .onShutdown(
          throw new RetryProvider.QuietNonRetryableException(
            "Snapshot polling aborted due to shutdown"
          )
        )
        .flatMap(_ =>
          if (preparationDeadline.isOverdue()) Future.failed(preparationTimedOut) else next
        )
    }
  }

  private val file = directory.resolve("snapshot")
  private val preparedId = new AtomicReference[Option[String]](None)

  def prepareAndDownload(
      prepare: => Future[SnapshotPreparation],
      downloadFrom: String => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[Path] = {
    def prepareSnapshot(): Future[String] =
      prepare.flatMap {
        case SnapshotPreparation.Pending(state, retryAfter) =>
          waitForPreparation(None, state, retryAfter)(prepareSnapshot())
        case SnapshotPreparation.Prepared(id) =>
          preparedId.set(Some(id))
          Future.successful(id)
      }
    val prepared = preparedId
      .get()
      .fold(
        prepareSnapshot()
      )(Future.successful)
    prepared.flatMap { id =>
      download(id, downloadFrom).recoverWith {
        case error @ HttpCommandException(_, status, _)
            if status == StatusCodes.NotFound || status == StatusCodes.InternalServerError =>
          preparedId.set(None)
          Future.failed(error)
      }
    }
  }

  def download(
      id: String,
      downloadFrom: String => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[Path] =
    downloadFrom(id).flatMap {
      case OnboardSvDownloadResponse.Accepted(state, retryAfter) =>
        waitForPreparation(Some(id), state, retryAfter)(download(id, downloadFrom))
      case OnboardSvDownloadResponse.OK(entity) =>
        entity.dataBytes
          .runWith(FileIO.toPath(file))
          .map(_ => file)
          .recoverWith { case NonFatal(e) =>
            Future.failed(
              Status.UNAVAILABLE
                .withDescription(s"Download of onboarding snapshot was interrupted: $e")
                .withCause(e)
                .asRuntimeException()
            )
          }
    }

  def delete(): Unit =
    File(directory).delete(swallowIOExceptions = true).discard
}

object SvOnboardingSnapshotDownload {

  def apply(
      config: SvOnboardingSnapshotsConfig,
      name: String,
      retryProvider: RetryProvider,
  ): SvOnboardingSnapshotDownload =
    new SvOnboardingSnapshotDownload(
      config.directory match {
        case Some(directory) =>
          val downloads = File(directory) / "downloads" / name
          if (downloads.exists) downloads.delete()
          downloads.createDirectories().path
        case None => Files.createTempDirectory(s"sv-onboarding-$name")
      },
      config.preparationTimeout.underlying,
      retryProvider,
    )
}
