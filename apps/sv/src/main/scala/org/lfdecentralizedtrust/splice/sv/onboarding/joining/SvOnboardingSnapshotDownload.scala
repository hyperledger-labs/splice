// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.FileIO
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

final class SvOnboardingSnapshotDownload private (
    val directory: Path,
    retryProvider: RetryProvider,
) extends NamedLogging {

  override val loggerFactory: NamedLoggerFactory = retryProvider.loggerFactory

  private val file = directory.resolve("snapshot")
  private val preparedId = new AtomicReference[Option[SvOnboardingSnapshotId]](None)

  def prepareAndDownload(
      prepare: => Future[SvOnboardingSnapshotId],
      downloadFrom: SvOnboardingSnapshotId => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] = {
    def prepareSnapshot(): Future[SvOnboardingSnapshotId] =
      prepare.map { id =>
        preparedId.set(Some(id))
        id
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
      id: SvOnboardingSnapshotId,
      downloadFrom: SvOnboardingSnapshotId => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] =
    downloadFrom(id).flatMap { case OnboardSvDownloadResponse.OK(entity) =>
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
      retryProvider,
    )
}
