// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpRequest,
  StatusCode,
  StatusCodes,
}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.{Future, Promise}

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
    def download(id: SvOnboardingSnapshotId): Future[OnboardSvDownloadResponse] = {
      requests += id.value
      Future.successful(remaining.dequeue())
    }
  }

  private class PreparingSponsor(responses: Either[Throwable, OnboardSvDownloadResponse]*) {
    private val remaining = mutable.Queue(responses*)
    val prepares = new AtomicInteger()
    val downloads = mutable.ListBuffer.empty[String]
    def prepare(): Future[SvOnboardingSnapshotId] =
      Future.successful(SvOnboardingSnapshotId(s"id-${prepares.incrementAndGet()}"))
    def download(id: SvOnboardingSnapshotId): Future[OnboardSvDownloadResponse] = {
      downloads += id.value
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

    "prepare again when the sponsor no longer has a usable snapshot" in {
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
      val file =
        snapshotDownload.download(SvOnboardingSnapshotId("id"), sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.requests shouldBe Seq("id")
    }

    "recreate the download directory if cleanup removes it while waiting" in withDownload {
      snapshotDownload =>
        val preparing = httpError(StatusCodes.ServiceUnavailable)
        val sponsor = new PreparingSponsor(Left(preparing), Right(full()))
        def attempt = snapshotDownload.prepareAndDownload(sponsor.prepare(), sponsor.download)
        attempt.failed.futureValue shouldBe preparing
        File(snapshotDownload.directory).delete()
        Files.readAllBytes(attempt.futureValue) shouldBe content
        sponsor.prepares.get() shouldBe 1
    }

    "never write outside its directory" in File.usingTemporaryDirectory() { directory =>
      val snapshotDownload = SvOnboardingSnapshotDownload(
        SvOnboardingSnapshotsConfig(directory = Some(directory.path)),
        "test",
        retryProvider,
      )
      val file =
        snapshotDownload
          .download(SvOnboardingSnapshotId("../../unrelated"), new Sponsor(full()).download)
          .futureValue
      file.getParent shouldBe snapshotDownload.directory
      directory.listRecursively.map(_.name).toSeq should not contain "unrelated"
    }

    "preserve an existing directory below the configured one" in File.usingTemporaryDirectory() {
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
        stale.exists shouldBe true
        exports.exists shouldBe true
        snapshotDownload.delete()
        Files.exists(snapshotDownload.directory) shouldBe false
    }

    "reuse a complete download after restart only when prepare returns the same id" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory = Some(directory.path))
        def downloader = SvOnboardingSnapshotDownload(config, "acs", retryProvider)
        val id = SvOnboardingSnapshotId("id")
        val first = downloader
          .prepareAndDownload(Future.successful(id), new Sponsor(full()).download)
          .futureValue
        val reused = downloader
          .prepareAndDownload(
            Future.successful(id),
            _ => fail("must not download the completed snapshot again"),
          )
          .futureValue
        reused shouldBe first
        Files.readAllBytes(reused) shouldBe content
        val changed = new Sponsor(full())
        downloader
          .prepareAndDownload(Future.successful(SvOnboardingSnapshotId("new-id")), changed.download)
          .futureValue
        changed.requests shouldBe Seq("new-id")
      }

    "redownload an incomplete or truncated snapshot after restart" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory = Some(directory.path))
        def downloader = SvOnboardingSnapshotDownload(config, "acs", retryProvider)
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(4)
        val first = downloader
        interrupted.after(first, first.download(id, new Sponsor(interrupted.response).download))
        val complete = downloader.download(id, new Sponsor(full()).download).futureValue
        Files.readAllBytes(complete) shouldBe content
        Files.write(complete, Array[Byte](1))
        val sponsor = new Sponsor(full())
        Files.readAllBytes(downloader.download(id, sponsor.download).futureValue) shouldBe content
        sponsor.requests shouldBe Seq("id")
      }

    "redownload when completion metadata is corrupt or expired" in File.usingTemporaryDirectory() {
      directory =>
        val config = SvOnboardingSnapshotsConfig(directory = Some(directory.path))
        def downloader = SvOnboardingSnapshotDownload(config, "acs", retryProvider)
        val id = SvOnboardingSnapshotId("id")
        val first = downloader
        first.download(id, new Sponsor(full()).download).futureValue
        val metadata = first.directory.resolve("metadata.json")
        Seq("not json", """{"id":"id","createdAt":"1970-01-01T00:00:00Z","size":10}""").foreach {
          invalid =>
            Files.writeString(metadata, invalid)
            val sponsor = new Sponsor(full())
            Files.readAllBytes(
              downloader.download(id, sponsor.download).futureValue
            ) shouldBe content
            sponsor.requests shouldBe Seq("id")
        }
    }

    "not reuse a response that ended before its declared length" in withDownload {
      snapshotDownload =>
        val short = OnboardSvDownloadResponse.OK(
          HttpEntity.Default(
            ContentTypes.`application/octet-stream`,
            content.length.toLong,
            Source.single(ByteString(content.take(4))),
          )
        )
        val id = SvOnboardingSnapshotId("id")
        failsUnavailable(snapshotDownload.download(id, new Sponsor(short).download))
        val sponsor = new Sponsor(full())
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq("id")
    }

    "use a temporary directory when none is configured" in {
      val snapshotDownload =
        SvOnboardingSnapshotDownload(SvOnboardingSnapshotsConfig(), "acs", retryProvider)
      Files.isDirectory(snapshotDownload.directory) shouldBe true
      snapshotDownload.delete()
      Files.exists(snapshotDownload.directory) shouldBe false
    }
  }
}
