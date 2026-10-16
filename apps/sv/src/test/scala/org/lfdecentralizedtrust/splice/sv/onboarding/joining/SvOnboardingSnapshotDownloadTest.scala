// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.model.{
  ContentRange,
  ContentTypes,
  HttpEntity,
  HttpRequest,
  StatusCode,
  StatusCodes,
  Uri,
}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvPublicAppClient
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*

class SvOnboardingSnapshotDownloadTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)

  override def afterAll(): Unit = {
    retryProvider.close()
    super.afterAll()
  }

  private val content = "0123456789".getBytes
  private def digest(bytes: Array[Byte]): String =
    s"sha-256=:${Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))}:"

  private val reprDigest = digest(content)
  private val otherReprDigest = digest(content.reverse)

  private def full(bytes: Array[Byte] = content, digest: Option[String] = Some(reprDigest)) =
    OnboardSvDownloadResponse.OK(
      HttpEntity(ContentTypes.`application/octet-stream`, bytes),
      digest,
    )

  private def from(
      offset: Int,
      length: Long = content.length.toLong,
      digest: String = reprDigest,
  ) =
    OnboardSvDownloadResponse.PartialContent(
      HttpEntity(ContentTypes.`application/octet-stream`, content.drop(offset)),
      Some(ContentRange(offset.toLong, length - 1, length)),
      Some(digest),
    )

  private class Interrupted(bytes: Int, includeLength: Boolean = true) {
    private val cut = Promise[ByteString]()
    private val data = Source.single(ByteString(content.take(bytes))) ++ Source.future(cut.future)
    val response = OnboardSvDownloadResponse.OK(
      if (includeLength)
        HttpEntity.Default(ContentTypes.`application/octet-stream`, content.length.toLong, data)
      else HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, data),
      Some(reprDigest),
    )
    def after(snapshotDownload: SvOnboardingSnapshotDownload, result: Future[Path]) = {
      val file = snapshotDownload.directory.resolve("snapshot")
      eventually() {
        Files.exists(file) shouldBe true
        Files.size(file) shouldBe bytes.toLong
      }
      cut.failure(new IOException("cut"))
      failsUnavailable(result)
    }
  }

  private class Sponsor(responses: OnboardSvDownloadResponse*) {
    private val remaining = mutable.Queue(responses*)
    val offsets = mutable.ListBuffer.empty[Long]
    def download(offset: Long): Future[OnboardSvDownloadResponse] = {
      offsets += offset
      Future.successful(remaining.dequeue())
    }
  }

  private class PendingBody(length: Long) {
    val cancelled = Promise[Done]()
    val entity = HttpEntity.Default(
      ContentTypes.`application/octet-stream`,
      length,
      Source.maybe[ByteString].watchTermination() { (_, finished) =>
        cancelled.completeWith(finished)
      },
    )
  }

  private class PreparingSponsor(responses: Either[Throwable, OnboardSvDownloadResponse]*) {
    private val remaining = mutable.Queue(responses*)
    val prepares = new AtomicInteger()
    val downloads = mutable.ListBuffer.empty[(String, Long)]
    def prepare(): Future[String] =
      Future.successful(s"id-${prepares.incrementAndGet()}")
    def download(id: String, offset: Long): Future[OnboardSvDownloadResponse] = {
      downloads += ((id, offset))
      remaining.dequeue().fold(Future.failed, Future.successful)
    }
  }

  private def httpError(status: StatusCode) = HttpCommandException(
    HttpRequest(),
    status,
    HttpCommandException.ErrorResponseBody(definitions.ErrorResponse("snapshot response")),
  )

  private def withDownload(test: SvOnboardingSnapshotDownload => Unit): Unit =
    File.usingTemporaryDirectory() { directory =>
      test(
        SvOnboardingSnapshotDownload(
          SvOnboardingSnapshotsConfig(directory = Some(directory.path)),
          "test",
          retryProvider,
        )
      )
    }

  private def failsUnavailable(result: Future[Path]) =
    inside(result.failed.futureValue) { case e: StatusRuntimeException =>
      e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
    }

  private def snapshotExists(snapshotDownload: SvOnboardingSnapshotDownload) =
    Files.exists(snapshotDownload.directory.resolve("snapshot"))

  private def downloadWithRetries(
      snapshotDownload: SvOnboardingSnapshotDownload,
      sponsor: Sponsor,
  ): Future[Path] =
    retryProvider.retry(
      RetryFor.WaitingOnInitDependencyLong.copy(
        maxRetries = 1,
        initialDelay = 1.millis,
        maxDelay = 1.millis,
      ),
      "snapshot_download",
      "download an onboarding snapshot",
      snapshotDownload.download("id", sponsor.download),
      logger,
    )

  "SvOnboardingSnapshotDownload" should {

    "keep the prepared id across temporary service failures" in withDownload { snapshotDownload =>
      val preparing = httpError(StatusCodes.ServiceUnavailable)
      val sponsor = new PreparingSponsor(Left(preparing), Left(preparing), Right(full()))
      def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)

      attempt.failed.futureValue shouldBe preparing
      attempt.failed.futureValue shouldBe preparing
      Files.readAllBytes(attempt.futureValue) shouldBe content
      sponsor.prepares.get() shouldBe 1
      sponsor.downloads shouldBe Seq(("id-1", 0L), ("id-1", 0L), ("id-1", 0L))
    }

    "resume with the same prepared id after an interrupted download" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(4)
        val sponsor = new PreparingSponsor(Right(interrupted.response), Right(from(4)))
        def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)

        interrupted.after(snapshotDownload, attempt)
        Files.readAllBytes(attempt.futureValue) shouldBe content
        sponsor.prepares.get() shouldBe 1
        sponsor.downloads shouldBe Seq(("id-1", 0L), ("id-1", 4L))
    }

    "prepare again when a download id is missing or its export has failed" in {
      Seq[StatusCode](StatusCodes.NotFound, StatusCodes.InternalServerError).foreach { status =>
        withDownload { snapshotDownload =>
          val interrupted = new Interrupted(4)
          val error = httpError(status)
          val sponsor =
            new PreparingSponsor(Right(interrupted.response), Left(error), Right(full()))
          def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)

          interrupted.after(snapshotDownload, attempt)
          attempt.failed.futureValue shouldBe error
          Files.readAllBytes(attempt.futureValue) shouldBe content
          sponsor.prepares.get() shouldBe 2
          sponsor.downloads shouldBe Seq(("id-1", 0L), ("id-1", 4L), ("id-2", 0L))
        }
      }
    }

    "retry preparation after a failed prepare request" in withDownload { snapshotDownload =>
      val preparing = httpError(StatusCodes.ServiceUnavailable)
      val sponsor = new PreparingSponsor(Right(full()))
      snapshotDownload
        .prepareAndDownload(Future.failed(preparing), sponsor.download)
        .failed
        .futureValue shouldBe preparing
      sponsor.downloads shouldBe empty
      val file =
        snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.downloads shouldBe Seq(("id-1", 0L))
    }

    "download the whole snapshot and verify its digest" in withDownload { snapshotDownload =>
      val sponsor = new Sponsor(full())
      val file = snapshotDownload.download("id", sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.offsets shouldBe Seq(0L)
    }

    val unusableDigests = Seq(
      None,
      Some("sha-512=:AAAA:"),
      Some("sha-256=:!invalid!:"),
      Some("sha-256=:AAAA:"),
    )

    "reject missing or invalid digests before writing or retrying a full response" in {
      unusableDigests.foreach { header =>
        withDownload { snapshotDownload =>
          val body = new PendingBody(content.length.toLong)
          val sponsor = new Sponsor(OnboardSvDownloadResponse.OK(body.entity, header), full())
          inside(downloadWithRetries(snapshotDownload, sponsor).failed.futureValue) {
            case error: RetryProvider.QuietNonRetryableException =>
              error.getMessage should include(
                if (header.isEmpty) "missing the required Repr-Digest header"
                else "invalid SHA-256 Repr-Digest header"
              )
              error.getMessage should include("Check the sponsor or proxy configuration")
          }
          body.cancelled.future.futureValue shouldBe Done
          snapshotExists(snapshotDownload) shouldBe false
          sponsor.offsets shouldBe Seq(0L)
        }
      }
    }

    "reject missing or invalid digests without changing or retrying a partial download" in {
      unusableDigests.foreach { header =>
        withDownload { snapshotDownload =>
          val interrupted = new Interrupted(4)
          val body = new PendingBody(content.length.toLong - 4L)
          val partial = OnboardSvDownloadResponse.PartialContent(
            body.entity,
            Some(ContentRange(4L, content.length.toLong - 1L, content.length.toLong)),
            header,
          )
          val sponsor = new Sponsor(interrupted.response, partial, full())
          interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))

          downloadWithRetries(snapshotDownload, sponsor).failed.futureValue shouldBe
            a[RetryProvider.QuietNonRetryableException]
          body.cancelled.future.futureValue shouldBe Done
          Files.readAllBytes(snapshotDownload.directory.resolve("snapshot")) shouldBe content
            .take(4)
          sponsor.offsets shouldBe Seq(0L, 4L)
        }
      }
    }

    "accept a Repr-Digest without base64 padding" in withDownload { snapshotDownload =>
      val interrupted = new Interrupted(4)
      val sponsor =
        new Sponsor(interrupted.response, from(4, digest = reprDigest.stripSuffix("=:") + ":"))
      interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))
      val file = snapshotDownload.download("id", sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.offsets shouldBe Seq(0L, 4L)
    }

    "retry a checksum mismatch when the digest metadata is valid" in withDownload {
      snapshotDownload =>
        val sponsor = new Sponsor(full(digest = Some(otherReprDigest)), full())
        val file =
          downloadWithRetries(snapshotDownload, sponsor).futureValue
        Files.readAllBytes(file) shouldBe content
        sponsor.offsets shouldBe Seq(0L, 0L)
    }

    "resume an interrupted download from the bytes already downloaded" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(4)
        val sponsor = new Sponsor(interrupted.response, from(4))
        interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))
        val file = snapshotDownload.download("id", sponsor.download).futureValue
        Files.readAllBytes(file) shouldBe content
        sponsor.offsets shouldBe Seq(0L, 4L)
    }

    "verify an already complete file when the resumed range returns 416" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(content.length)
        val sponsor =
          new Sponsor(interrupted.response, OnboardSvDownloadResponse.RangeNotSatisfiable)
        interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))

        val file = snapshotDownload.download("id", sponsor.download).futureValue
        Files.readAllBytes(file) shouldBe content
        sponsor.offsets shouldBe Seq(0L, content.length.toLong)
    }

    "reject an already complete file with a bad digest after 416" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(content.length)
        val sponsor =
          new Sponsor(interrupted.response, OnboardSvDownloadResponse.RangeNotSatisfiable)
        interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))
        Files.write(snapshotDownload.directory.resolve("snapshot"), content.reverse)

        failsUnavailable(snapshotDownload.download("id", sponsor.download))
        snapshotExists(snapshotDownload) shouldBe false
        sponsor.offsets shouldBe Seq(0L, content.length.toLong)
    }

    "restart after 416 when the expected total length is unknown" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(content.length, includeLength = false)
        val sponsor =
          new Sponsor(interrupted.response, OnboardSvDownloadResponse.RangeNotSatisfiable)
        interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))

        failsUnavailable(snapshotDownload.download("id", sponsor.download))
        snapshotExists(snapshotDownload) shouldBe false
        sponsor.offsets shouldBe Seq(0L, content.length.toLong)
    }

    "verify the digest when Content-Length is absent" in withDownload { snapshotDownload =>
      def response(digest: Option[String]) = OnboardSvDownloadResponse.OK(
        HttpEntity.Chunked.fromData(
          ContentTypes.`application/octet-stream`,
          Source.single(ByteString(content)),
        ),
        digest,
      )
      val sponsor = new Sponsor(response(Some(reprDigest)), response(Some(otherReprDigest)))
      val file = snapshotDownload.download("valid", sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content

      failsUnavailable(snapshotDownload.download("invalid", sponsor.download))
      snapshotExists(snapshotDownload) shouldBe false
    }

    "resume a download whose initial response had no Content-Length" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(4, includeLength = false)
        val sponsor = new Sponsor(interrupted.response, from(4))
        interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))
        val file = snapshotDownload.download("id", sponsor.download).futureValue
        Files.readAllBytes(file) shouldBe content
        sponsor.offsets shouldBe Seq(0L, 4L)
    }

    "remember the total length learned from a partial response" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(4, includeLength = false)
        val partial = OnboardSvDownloadResponse.PartialContent(
          HttpEntity(ContentTypes.`application/octet-stream`, content.slice(4, 6)),
          Some(ContentRange(4L, 5L, content.length.toLong)),
          Some(reprDigest),
        )
        val sponsor = new Sponsor(interrupted.response, partial, from(6, length = 11), full())
        interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))

        failsUnavailable(snapshotDownload.download("id", sponsor.download))
        Files.size(snapshotDownload.directory.resolve("snapshot")) shouldBe 6L

        failsUnavailable(snapshotDownload.download("id", sponsor.download))
        snapshotExists(snapshotDownload) shouldBe false
        val file = snapshotDownload.download("id", sponsor.download).futureValue
        Files.readAllBytes(file) shouldBe content
        sponsor.offsets shouldBe Seq(0L, 4L, 6L, 0L)
    }

    "rewrite the snapshot when the sponsor ignores the range" in withDownload { snapshotDownload =>
      val interrupted = new Interrupted(4)
      val sponsor = new Sponsor(interrupted.response, full())
      interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))
      val file = snapshotDownload.download("id", sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.offsets shouldBe Seq(0L, 4L)
    }

    "start over when a resumed response does not continue the same snapshot" in withDownload {
      snapshotDownload =>
        val resumed = Seq[OnboardSvDownloadResponse](
          OnboardSvDownloadResponse.RangeNotSatisfiable,
          from(0),
          from(4, length = 8),
          from(4, digest = otherReprDigest),
        )
        resumed.foreach { response =>
          val interrupted = new Interrupted(4)
          val sponsor = new Sponsor(interrupted.response, response)
          interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))
          failsUnavailable(snapshotDownload.download("id", sponsor.download))
          snapshotExists(snapshotDownload) shouldBe false
          sponsor.offsets shouldBe Seq(0L, 4L)
        }
    }

    "cancel a rejected partial response without waiting for its body" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(4)
        val body = new PendingBody(content.length.toLong - 4L)
        val rejected = OnboardSvDownloadResponse.PartialContent(
          body.entity,
          Some(ContentRange(4L, content.length.toLong - 1L, content.length.toLong)),
          Some(otherReprDigest),
        )
        val sponsor = new Sponsor(interrupted.response, rejected, full())
        interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))

        failsUnavailable(snapshotDownload.download("id", sponsor.download))
        body.cancelled.future.futureValue shouldBe Done
        snapshotExists(snapshotDownload) shouldBe false
        snapshotDownload.download("id", sponsor.download).futureValue
        sponsor.offsets shouldBe Seq(0L, 4L, 0L)
    }

    "start over when the sponsor returns a new id" in withDownload { snapshotDownload =>
      val interrupted = new Interrupted(4)
      val sponsor = new Sponsor(interrupted.response, full())
      interrupted.after(snapshotDownload, snapshotDownload.download("id", sponsor.download))
      snapshotDownload.download("new-id", sponsor.download).futureValue
      sponsor.offsets shouldBe Seq(0L, 0L)
    }

    "delete the snapshot when its digest does not match" in withDownload { snapshotDownload =>
      val sponsor = new Sponsor(
        full(digest = Some(otherReprDigest)),
        full(content.reverse),
      )
      (1 to 2).foreach { _ =>
        failsUnavailable(snapshotDownload.download("id", sponsor.download))
        snapshotExists(snapshotDownload) shouldBe false
      }
    }

    "never write outside its directory" in File.usingTemporaryDirectory() { directory =>
      val snapshotDownload = SvOnboardingSnapshotDownload(
        SvOnboardingSnapshotsConfig(directory = Some(directory.path)),
        "test",
        retryProvider,
      )
      val file =
        snapshotDownload.download("../../unrelated", new Sponsor(full()).download).futureValue
      file.getParent shouldBe snapshotDownload.directory
      directory.listRecursively.map(_.name).toSeq should not contain "unrelated"
    }

    "use a fresh directory below the configured one" in File.usingTemporaryDirectory() {
      directory =>
        val stale = (directory / "downloads" / "acs" / "stale").createIfNotExists(
          createParents = true
        )
        val exports = (directory / "exports").createDirectories()
        val snapshotDownload = SvOnboardingSnapshotDownload(
          SvOnboardingSnapshotsConfig(directory = Some(directory.path)),
          "acs",
          retryProvider,
        )
        snapshotDownload.directory shouldBe (directory / "downloads" / "acs").path
        stale.exists shouldBe false
        exports.exists shouldBe true
        snapshotDownload.delete()
        Files.exists(snapshotDownload.directory) shouldBe false
    }

    "use a temporary directory when none is configured" in {
      val snapshotDownload =
        SvOnboardingSnapshotDownload(SvOnboardingSnapshotsConfig(), "acs", retryProvider)
      Files.isDirectory(snapshotDownload.directory) shouldBe true
      snapshotDownload.delete()
      Files.exists(snapshotDownload.directory) shouldBe false
    }
  }

  "onboardingSnapshotsNotSupported" should {

    def notFound(message: String) =
      HttpCommandException(
        HttpRequest(),
        StatusCodes.NotFound,
        HttpCommandException.ErrorResponseBody(definitions.ErrorResponse(message)),
      )
    val sponsor = Uri("http://sponsor")
    val missingEndpoint = notFound(
      "The requested resource could not be found: http://sponsor/api/sv/v0/onboard/sv/party-migration/prepare"
    )
    val notSupported = HttpSvPublicAppClient.onboardingSnapshotsNotSupported(sponsor, Future.unit)

    "fail without retrying when an active sponsor does not have the prepare endpoints" in {
      notSupported(missingEndpoint).failed.futureValue shouldBe
        HttpSvPublicAppClient.OnboardingSnapshotsNotSupported(sponsor)
    }

    "keep the missing-endpoint error retryable while the sponsor is initializing" in {
      val recovering = HttpSvPublicAppClient.onboardingSnapshotsNotSupported(
        sponsor,
        Future.failed(
          Status.FAILED_PRECONDITION.withDescription("Node is not active").asRuntimeException()
        ),
      )
      recovering(missingEndpoint).failed.futureValue shouldBe missingEndpoint
    }

    "keep the missing-endpoint error retryable when the sponsor health check fails" in {
      val recovering = HttpSvPublicAppClient.onboardingSnapshotsNotSupported(
        sponsor,
        Future.failed(new IOException("connection reset during startup")),
      )
      recovering(missingEndpoint).failed.futureValue shouldBe missingEndpoint
    }

    "keep other not found errors retryable" in {
      notSupported.isDefinedAt(
        notFound("Candidate party is not an sv and no `SvOnboardingConfirmed` for the candidate")
      ) shouldBe false
    }
  }
}
