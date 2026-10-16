// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.google.protobuf.ByteString
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.{ContentRange, ResponseEntity, StatusCodes}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.joining.SvOnboardingSnapshotDownload.Representation

import java.io.OutputStream
import java.nio.file.StandardOpenOption.{APPEND, CREATE, TRUNCATE_EXISTING, WRITE}
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.Try
import scala.util.control.NonFatal

final class SvOnboardingSnapshotDownload private (
    val directory: Path,
    retryProvider: RetryProvider,
) extends NamedLogging {

  override val loggerFactory: NamedLoggerFactory = retryProvider.loggerFactory

  private val file = directory.resolve("snapshot")
  private val preparedId = new AtomicReference[Option[String]](None)
  private val downloading = new AtomicReference[Option[Representation]](None)

  def prepareAndDownload(
      prepare: => Future[String],
      downloadFrom: (String, Long) => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] = {
    def prepareSnapshot(): Future[String] =
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
      download(id, downloadFrom(id, _)).recoverWith {
        case error @ HttpCommandException(_, status, _)
            if status == StatusCodes.NotFound || status == StatusCodes.InternalServerError =>
          preparedId.set(None)
          Future.failed(error)
      }
    }
  }

  def download(
      id: String,
      downloadFrom: Long => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] =
    for {
      offset <- Future {
        blocking {
          downloading.get() match {
            case Some(representation) if representation.id == id && Files.exists(file) =>
              Files.size(file)
            case _ =>
              downloading.set(None)
              Files.deleteIfExists(file).discard
              0L
          }
        }
      }
      response <- downloadFrom(offset)
      _ <- response match {
        case OnboardSvDownloadResponse.OK(entity, reprDigest) =>
          withSha256(entity, reprDigest) { sha256 =>
            val representation = Representation(id, entity.contentLengthOption, sha256)
            downloading.set(Some(representation))
            write(entity, append = false).flatMap(_ => verify(representation))
          }
        case OnboardSvDownloadResponse.PartialContent(entity, contentRange, reprDigest) =>
          withSha256(entity, reprDigest) { sha256 =>
            contentRange match {
              case Some(ContentRange.Default(first, _, length))
                  if first == offset && downloading
                    .get()
                    .exists(previous =>
                      previous.id == id && previous.sha256 == sha256 &&
                        previous.length.forall(expected => length.contains(expected))
                    ) =>
                val representation = Representation(id, length, sha256)
                downloading.set(Some(representation))
                write(entity, append = true).flatMap(_ => verify(representation))
              case _ =>
                entity.dataBytes.runWith(Sink.cancelled).discard
                restart(
                  s"range $contentRange with digest $reprDigest does not continue the download at offset $offset"
                )
            }
          }
        case OnboardSvDownloadResponse.RangeNotSatisfiable =>
          downloading.get() match {
            case Some(representation)
                if representation.id == id && representation.length.contains(offset) =>
              verify(representation)
            case _ => restart(s"range from offset $offset is not satisfiable")
          }
      }
    } yield file

  def delete(): Unit =
    File(directory).delete(swallowIOExceptions = true).discard

  private def withSha256(entity: ResponseEntity, reprDigest: Option[String])(
      next: ByteString => Future[Unit]
  )(implicit mat: Materializer): Future[Unit] =
    reprDigest
      .collect { case SvOnboardingSnapshotDownload.ReprDigest(encoded) => encoded }
      .flatMap(encoded => Try(Base64.getDecoder.decode(encoded)).toOption)
      .filter(_.length == 32) match {
      case Some(bytes) => next(ByteString.copyFrom(bytes))
      case None =>
        entity.dataBytes.runWith(Sink.cancelled).discard
        val reason =
          if (reprDigest.isEmpty) "is missing the required Repr-Digest header"
          else "has an invalid SHA-256 Repr-Digest header"
        Future.failed(
          new RetryProvider.QuietNonRetryableException(
            s"Onboarding snapshot response $reason. Check the sponsor or proxy configuration."
          )
        )
    }

  private def write(entity: ResponseEntity, append: Boolean)(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    entity.dataBytes
      .runWith(
        FileIO.toPath(
          file,
          if (append) Set(WRITE, APPEND, CREATE) else Set(WRITE, TRUNCATE_EXISTING, CREATE),
        )
      )
      .map(_ => ())
      .recoverWith { case NonFatal(e) =>
        Future.failed(
          Status.UNAVAILABLE
            .withDescription(s"Download of onboarding snapshot was interrupted: $e")
            .withCause(e)
            .asRuntimeException()
        )
      }

  private def verify(representation: Representation)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      blocking {
        val size = Files.size(file)
        if (representation.length.exists(_ != size))
          throw Status.UNAVAILABLE
            .withDescription(
              s"Downloaded $size bytes of onboarding snapshot, expected ${representation.length}"
            )
            .asRuntimeException()
        val actual = sha256()
        if (actual != representation.sha256) {
          downloading.set(None)
          Files.delete(file)
          throw Status.UNAVAILABLE
            .withDescription(
              s"SHA-256 ${Base64.getEncoder.encodeToString(actual.toByteArray)} of onboarding snapshot " +
                s"does not match Repr-Digest ${Base64.getEncoder.encodeToString(representation.sha256.toByteArray)}"
            )
            .asRuntimeException()
        }
      }
    }

  private def sha256(): ByteString = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = new DigestInputStream(Files.newInputStream(file), digest)
    try in.transferTo(OutputStream.nullOutputStream()).discard
    finally in.close()
    ByteString.copyFrom(digest.digest())
  }

  private def restart(reason: String)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      blocking {
        downloading.set(None)
        Files.deleteIfExists(file).discard
      }
    }.flatMap(_ =>
      Future.failed(
        Status.UNAVAILABLE
          .withDescription(s"Restarting the download of the onboarding snapshot: $reason")
          .asRuntimeException()
      )
    )
}

object SvOnboardingSnapshotDownload {

  private val ReprDigest = "sha-256=:(.+):".r

  private final case class Representation(
      id: String,
      length: Option[Long],
      sha256: ByteString,
  )

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
