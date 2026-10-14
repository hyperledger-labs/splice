// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.syntax.*
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
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId

import java.io.OutputStream
import java.nio.file.StandardOpenOption.{APPEND, CREATE, TRUNCATE_EXISTING, WRITE}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.{DigestInputStream, MessageDigest}
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.Try
import scala.util.control.NonFatal

final class SvOnboardingSnapshotDownload private (
    val directory: Path,
    retryProvider: RetryProvider,
    config: SvOnboardingSnapshotsConfig,
) extends NamedLogging {

  override val loggerFactory: NamedLoggerFactory = retryProvider.loggerFactory

  import SvOnboardingSnapshotDownload.*

  private val file = directory.resolve("snapshot")
  private val metadataFile = directory.resolve("metadata.json")
  private val preparedId = new AtomicReference[Option[SvOnboardingSnapshotId]](None)

  private def writeMetadata(metadata: DownloadMetadata): Unit = {
    Files.createDirectories(directory)
    val temporary = directory.resolve("metadata.tmp")
    Files.writeString(temporary, metadata.asJson.noSpaces)
    Files.move(
      temporary,
      metadataFile,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING,
    )
    ()
  }

  private def readMetadata(id: SvOnboardingSnapshotId): Option[DownloadMetadata] =
    Try {
      val metadata =
        decode[DownloadMetadata](Files.readString(metadataFile)).fold(throw _, identity)
      Option.when(
        metadata.id == id.value &&
          Instant.parse(metadata.createdAt).plus(config.retention.asJava).isAfter(Instant.now())
      )(metadata)
    }.toOption.flatten

  def prepareAndDownload(
      prepare: => Future[SvOnboardingSnapshotId],
      downloadFrom: (SvOnboardingSnapshotId, Long) => Future[OnboardSvDownloadResponse],
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
      download(id, downloadFrom(id, _)).recoverWith {
        case error @ HttpCommandException(_, status, _)
            if status == StatusCodes.NotFound || status == StatusCodes.InternalServerError =>
          preparedId.set(None)
          Future.failed(error)
      }
    }
  }

  def download(
      id: SvOnboardingSnapshotId,
      downloadFrom: Long => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] =
    Future(blocking {
      val previous = readMetadata(id)
      if (
        previous.exists(metadata =>
          metadata.size.exists(size => Files.isRegularFile(file) && Files.size(file) == size) &&
            metadata.sha256.contains(sha256())
        )
      ) None
      else
        previous.filter(metadata =>
          metadata.size.isEmpty && metadata.sha256.isDefined && Files.isRegularFile(file)
        ) match {
          case Some(metadata) => Some(metadata -> Files.size(file))
          case None =>
            Files.deleteIfExists(file).discard
            val metadata = DownloadMetadata(id.value, Instant.now().toString, None, None, None)
            writeMetadata(metadata)
            Some(metadata -> 0L)
        }
    }).flatMap {
      case None => Future.successful(file)
      case Some((previous, offset)) =>
        downloadFrom(offset).flatMap(receive(previous, offset, _)).map(_ => file)
    }

  def delete(): Unit =
    File(directory).delete(swallowIOExceptions = true).discard

  private def receive(
      previous: DownloadMetadata,
      offset: Long,
      response: OnboardSvDownloadResponse,
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Unit] =
    response match {
      case OnboardSvDownloadResponse.OK(entity, reprDigest) =>
        withSha256(entity, reprDigest) { sha256 =>
          val metadata =
            previous.copy(sha256 = Some(sha256), length = entity.contentLengthOption, size = None)
          Future(blocking(writeMetadata(metadata)))
            .flatMap(_ => write(entity, append = false))
            .flatMap(_ => verify(metadata))
        }
      case OnboardSvDownloadResponse.PartialContent(entity, contentRange, reprDigest) =>
        withSha256(entity, reprDigest) { sha256 =>
          contentRange match {
            case Some(ContentRange.Default(first, _, length))
                if first == offset && previous.sha256.contains(sha256) &&
                  previous.length.forall(expected => length.contains(expected)) =>
              val metadata = previous.copy(length = length)
              Future(blocking(writeMetadata(metadata)))
                .flatMap(_ => write(entity, append = true))
                .flatMap(_ => verify(metadata))
            case _ =>
              entity.dataBytes.runWith(Sink.cancelled).discard
              restart(
                s"range $contentRange with digest $reprDigest does not continue the download at offset $offset"
              )
          }
        }
      case OnboardSvDownloadResponse.RangeNotSatisfiable =>
        if (previous.sha256.isDefined && previous.length.contains(offset)) verify(previous)
        else restart(s"range from offset $offset is not satisfiable")
    }

  private def withSha256(entity: ResponseEntity, reprDigest: Option[String])(
      next: String => Future[Unit]
  )(implicit mat: Materializer): Future[Unit] =
    reprDigest
      .collect { case ReprDigest(encoded) => encoded }
      .flatMap(encoded => Try(Base64.getDecoder.decode(encoded)).toOption)
      .filter(_.length == 32) match {
      case Some(bytes) => next(Base64.getEncoder.encodeToString(bytes))
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

  private def verify(metadata: DownloadMetadata)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      blocking {
        val size = Files.size(file)
        if (metadata.length.exists(_ != size))
          throw Status.UNAVAILABLE
            .withDescription(
              s"Downloaded $size bytes of onboarding snapshot, expected ${metadata.length}"
            )
            .asRuntimeException()
        val actual = sha256()
        if (!metadata.sha256.contains(actual)) {
          Files.delete(file)
          throw Status.UNAVAILABLE
            .withDescription(
              s"SHA-256 $actual of onboarding snapshot does not match Repr-Digest ${metadata.sha256}"
            )
            .asRuntimeException()
        }
        writeMetadata(metadata.copy(size = Some(size)))
      }
    }

  private def sha256(): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = new DigestInputStream(Files.newInputStream(file), digest)
    try in.transferTo(OutputStream.nullOutputStream()).discard
    finally in.close()
    Base64.getEncoder.encodeToString(digest.digest())
  }

  private def restart(reason: String)(implicit ec: ExecutionContext): Future[Unit] =
    Future(blocking(Files.deleteIfExists(file).discard)).flatMap(_ =>
      Future.failed(
        Status.UNAVAILABLE
          .withDescription(s"Restarting the download of the onboarding snapshot: $reason")
          .asRuntimeException()
      )
    )
}

object SvOnboardingSnapshotDownload {

  private val ReprDigest = "sha-256=:(.+):".r

  private final case class DownloadMetadata(
      id: String,
      createdAt: String,
      sha256: Option[String],
      length: Option[Long],
      size: Option[Long],
  )
  private implicit val encoder: Encoder[DownloadMetadata] = deriveEncoder
  private implicit val decoder: Decoder[DownloadMetadata] = deriveDecoder

  private def notConfigured =
    Status.FAILED_PRECONDITION
      .withDescription(
        "onboarding-snapshots.directory must point to persistent storage to join as an SV"
      )
      .asRuntimeException()

  def checkConfigured(config: SvOnboardingSnapshotsConfig): Future[Unit] =
    if (config.directory.isDefined) Future.unit else Future.failed(notConfigured)

  def apply(
      config: SvOnboardingSnapshotsConfig,
      name: String,
      retryProvider: RetryProvider,
  ): SvOnboardingSnapshotDownload = {
    val directory = config.directory.getOrElse(throw notConfigured)
    new SvOnboardingSnapshotDownload(
      (File(directory) / "downloads" / name).createDirectories().path,
      retryProvider,
      config,
    )
  }
}
