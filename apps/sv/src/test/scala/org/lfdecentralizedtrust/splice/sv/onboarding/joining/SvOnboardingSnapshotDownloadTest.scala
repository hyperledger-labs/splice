// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.PositiveFiniteDuration
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.http.scaladsl.model.{
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
  OnboardSvDownloadResponse,
  SnapshotPreparation,
}
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvPublicAppClient
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.nio.file.{Files, Path}
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

  private def full() =
    OnboardSvDownloadResponse.OK(HttpEntity(ContentTypes.`application/octet-stream`, content))

  private class Interrupted(bytes: Int) {
    private val cut = Promise[ByteString]()
    private val data = Source.single(ByteString(content.take(bytes))) ++ Source.future(cut.future)
    val response = OnboardSvDownloadResponse.OK(
      HttpEntity.Default(ContentTypes.`application/octet-stream`, content.length.toLong, data)
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
    val requests = mutable.ListBuffer.empty[String]
    def download(id: String): Future[OnboardSvDownloadResponse] = {
      requests += id
      Future.successful(remaining.dequeue())
    }
  }

  private class PreparingSponsor(responses: Either[Throwable, OnboardSvDownloadResponse]*) {
    private val remaining = mutable.Queue(responses*)
    val prepares = new AtomicInteger()
    val downloads = mutable.ListBuffer.empty[String]
    def prepare(): Future[SnapshotPreparation] =
      Future.successful(SnapshotPreparation.Prepared(s"id-${prepares.incrementAndGet()}"))
    def download(id: String): Future[OnboardSvDownloadResponse] = {
      downloads += id
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

  "SvOnboardingSnapshotDownload" should {

    "poll prerequisites and export readiness without consuming failure retries" in withDownload {
      snapshotDownload =>
        val preparations = new AtomicInteger()
        def prepare: Future[SnapshotPreparation] = Future.successful(
          if (preparations.incrementAndGet() <= 2)
            SnapshotPreparation.Pending("waiting_for_prerequisites", 10.millis)
          else SnapshotPreparation.Prepared("id-1")
        )
        val sponsor = new PreparingSponsor(
          Right(OnboardSvDownloadResponse.Accepted("preparing", 10.millis)),
          Right(OnboardSvDownloadResponse.Accepted("preparing", 10.millis)),
          Right(full()),
        )
        val started = Deadline.now
        val file = retryProvider
          .retry(
            RetryFor.WaitingOnInitDependencyLong.copy(maxRetries = 0),
            "snapshot_polling",
            "prepare and download a snapshot",
            snapshotDownload.prepareAndDownload(prepare, sponsor.download),
            logger,
          )
          .futureValue
        Files.readAllBytes(file) shouldBe content
        preparations.get() shouldBe 3
        sponsor.downloads shouldBe Seq("id-1", "id-1", "id-1")
        (Deadline.now - started) should be >= 40.millis
    }

    "propagate a real service failure after pending responses and reuse the prepared id" in withDownload {
      snapshotDownload =>
        val error = httpError(StatusCodes.ServiceUnavailable)
        val sponsor = new PreparingSponsor(
          Right(OnboardSvDownloadResponse.Accepted("preparing", 10.millis)),
          Left(error),
          Right(full()),
        )
        def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)
        attempt.failed.futureValue shouldBe error
        Files.readAllBytes(attempt.futureValue) shouldBe content
        sponsor.prepares.get() shouldBe 1
    }

    "bound pending waits and retain the deadline across prepare, download and reprepare" in File
      .usingTemporaryDirectory() { directory =>
        val snapshotDownload = SvOnboardingSnapshotDownload(
          SvOnboardingSnapshotsConfig(
            directory = Some(directory.path),
            preparationTimeout = PositiveFiniteDuration.ofSeconds(1),
          ),
          "timeout",
          retryProvider,
        )
        val prepares = new AtomicInteger()
        def pendingPrepare = {
          prepares.incrementAndGet()
          Future.successful(SnapshotPreparation.Pending("waiting_for_prerequisites", 1.hour))
        }
        val sponsor = new PreparingSponsor(
          Right(OnboardSvDownloadResponse.Accepted("preparing", 1.millis)),
          Left(httpError(StatusCodes.NotFound)),
          Right(OnboardSvDownloadResponse.Accepted("preparing", 1.millis)),
          Right(full()),
        )
        def assertTimedOut(result: Future[Path]) = inside(result.failed.futureValue) {
          case error: RetryProvider.QuietNonRetryableException =>
            error.getMessage should include("preparation-timeout")
        }
        assertTimedOut(snapshotDownload.prepareAndDownload(pendingPrepare, sponsor.download))
        prepares.get() shouldBe 1
        sponsor.downloads shouldBe empty
        def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)
        assertTimedOut(attempt)
        sponsor.downloads.toSeq shouldBe Seq("id-1")
        inside(attempt.failed.futureValue) { case error: HttpCommandException =>
          error.status shouldBe StatusCodes.NotFound
        }
        assertTimedOut(attempt)
        sponsor.prepares.get() shouldBe 2
        sponsor.downloads.toSeq shouldBe Seq("id-1", "id-1", "id-2")
      }

    "stop pending prepare and download polling on shutdown" in {
      Seq(false, true).foreach { pendingDownload =>
        val pollingRetryProvider =
          RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
        File.usingTemporaryDirectory() { directory =>
          val snapshotDownload = SvOnboardingSnapshotDownload(
            SvOnboardingSnapshotsConfig(directory = Some(directory.path)),
            "shutdown",
            pollingRetryProvider,
          )
          val polled = Promise[Unit]()
          val polls = new AtomicInteger()
          def prepare: Future[SnapshotPreparation] = {
            if (pendingDownload) Future.successful(SnapshotPreparation.Prepared("id"))
            else {
              polls.incrementAndGet()
              polled.trySuccess(())
              Future.successful(SnapshotPreparation.Pending("waiting_for_prerequisites", 1.hour))
            }
          }
          val result = snapshotDownload.prepareAndDownload(
            prepare,
            _ => {
              polls.incrementAndGet()
              polled.trySuccess(())
              Future.successful(OnboardSvDownloadResponse.Accepted("preparing", 1.hour))
            },
          )
          try {
            polled.future.futureValue
            pollingRetryProvider.close()
            result.failed.futureValue.getMessage should include("shutdown")
            polls.get() shouldBe 1
          } finally pollingRetryProvider.close()
        }
      }
    }

    "keep the prepared id across temporary service failures" in withDownload { snapshotDownload =>
      val preparing = httpError(StatusCodes.ServiceUnavailable)
      val sponsor = new PreparingSponsor(Left(preparing), Left(preparing), Right(full()))
      def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)

      attempt.failed.futureValue shouldBe preparing
      attempt.failed.futureValue shouldBe preparing
      Files.readAllBytes(attempt.futureValue) shouldBe content
      sponsor.prepares.get() shouldBe 1
      sponsor.downloads shouldBe Seq("id-1", "id-1", "id-1")
    }

    "download again from the start with the same prepared id after an interrupted download" in withDownload {
      snapshotDownload =>
        val interrupted = new Interrupted(4)
        val sponsor = new PreparingSponsor(Right(interrupted.response), Right(full()))
        def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)

        interrupted.after(snapshotDownload, attempt)
        Files.readAllBytes(attempt.futureValue) shouldBe content
        sponsor.prepares.get() shouldBe 1
        sponsor.downloads shouldBe Seq("id-1", "id-1")
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
          sponsor.downloads shouldBe Seq("id-1", "id-1", "id-2")
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
      sponsor.downloads shouldBe Seq("id-1")
    }

    "download the whole snapshot" in withDownload { snapshotDownload =>
      val sponsor = new Sponsor(full())
      val file = snapshotDownload.download("id", sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.requests shouldBe Seq("id")
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
