// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{PartyId, SequencerId, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.provide
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.sv_public.SvPublicResource
import org.lfdecentralizedtrust.splice.http.v0.sv_public_stream.{
  SvPublicStreamHandler,
  SvPublicStreamResource,
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.{
  SnapshotKey,
  SnapshotState,
}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.{Future, Promise}

class OnboardSvSnapshotHandlerTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  private val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
  private val snapshotDirectory = better.files.File.newTemporaryDirectory()
  private val snapshotService = new SvOnboardingSnapshotService(
    SvOnboardingSnapshotsConfig(directory = Some(snapshotDirectory.path)),
    new SimClock(loggerFactory = loggerFactory),
    retryProvider,
    loggerFactory,
  )

  override def afterAll(): Unit = {
    retryProvider.close()
    snapshotService.close()
    snapshotDirectory.delete()
    super.afterAll()
  }

  private val handler = new SvPublicStreamHandler[TraceContext] {
    private def unsupported[T] = Future.failed[T](new UnsupportedOperationException)
    override def onboardSvSequencer(
        respond: SvPublicStreamResource.OnboardSvSequencerResponse.type
    )(body: definitions.OnboardSvSequencerRequest)(extracted: TraceContext) = unsupported
    override def onboardSvDownload(
        respond: SvPublicStreamResource.OnboardSvDownloadResponse.type
    )(id: String)(extracted: TraceContext) =
      HttpSvPublicHandler.onboardSvDownloadResponse(snapshotService, SvOnboardingSnapshotId(id))
  }

  private val route = Route.seal(
    SvPublicStreamResource.routes(handler, _ => provide(traceContext))
  )

  private val content = "0123456789".getBytes

  private def prepare(name: String, exportTo: Path => Future[Unit]): SvOnboardingSnapshotId =
    snapshotService
      .prepare(
        SnapshotKey.Acs(
          SynchronizerId.tryFromString("synchronizer::dummy"),
          PartyId.tryFromProtoPrimitive(s"$name::dummy"),
          Instant.EPOCH,
        ),
        exportTo,
      )
      .futureValue

  private def writeSnapshot(file: Path): Future[Unit] = {
    Files.write(file, content)
    Future.unit
  }

  private def verifyNothingPrepared(service: SvOnboardingSnapshotService) =
    verify(service, never).prepare(any[SnapshotKey], any[Path => Future[Unit]])(
      any[TraceContext]
    )

  private def download(id: SvOnboardingSnapshotId) =
    Get(s"/api/sv/v0/onboard/sv/download/${id.value}")

  "onboardSvDownload" should {

    "report failed and unknown ids as missing and pending exports as unavailable" in {
      val failed =
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          {
            val id = prepare(
              "failed",
              _ =>
                Future.failed(new RuntimeException("/snapshots/private/export (Permission denied)")),
            )
            eventually()(snapshotService.lookup(id) shouldBe None)
            id
          },
          forExactly(1, _)(_.warningMessage should include("failed")),
        )
      download(failed) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        val body = responseAs[String]
        body should include(s"Onboarding snapshot $failed not found")
        body should not include "/snapshots/private"
        body should not include "Permission denied"
      }

      download(SvOnboardingSnapshotId("unknown")) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
      }

      val exported = Promise[Unit]()
      try {
        val exporting = prepare(
          "exporting",
          file => {
            Files.write(file, content)
            exported.future
          },
        )
        val queued = prepare("queued", writeSnapshot)
        Seq(exporting, queued).foreach { id =>
          download(id) ~> route ~> check {
            status shouldBe StatusCodes.ServiceUnavailable
            responseAs[String] should include("still being exported")
          }
        }
      } finally exported.trySuccess(()).discard
    }

    "return a generic error when a completed snapshot file is missing" in {
      val id = prepare("missing-file", writeSnapshot)
      val file = eventually() {
        inside(snapshotService.lookup(id)) { case Some(SnapshotState.Ready(file)) => file }
      }
      Files.delete(file)
      download(id) ~> route ~> check {
        status shouldBe StatusCodes.InternalServerError
        val body = responseAs[String]
        body should include(s"Export of onboarding snapshot $id failed")
        body should not include file.toString
      }
    }
  }

  "onboardSvPartyMigrationPrepare" should {

    "return the proposal serial without exporting when the proposal was not found" in {
      val service = mock[SvOnboardingSnapshotService]
      HttpSvPublicHandler
        .onboardSvPartyMigrationPrepareResponse(
          service,
          Left(DsoPartyHosting.RequiredProposalNotFound(PositiveInt.tryCreate(3))),
          (_, file) => writeSnapshot(file),
        )
        .futureValue shouldBe SvPublicResource.OnboardSvPartyMigrationPrepareResponse
        .BadRequest(
          definitions.ProposalNotFoundErrorResponse(
            definitions.ProposalNotFoundErrorResponse.ProposalNotFound(BigInt(3))
          )
        )
      verifyNothingPrepared(service)
    }

    "return 503 without exporting while the DSO party is not yet hosted" in {
      val service = mock[SvOnboardingSnapshotService]
      HttpSvPublicHandler
        .onboardSvPartyMigrationPrepareResponse(
          service,
          Right(None),
          (_, file) => writeSnapshot(file),
        )
        .futureValue shouldBe SvPublicResource.OnboardSvPartyMigrationPrepareResponse
        .ServiceUnavailable(
          definitions.ErrorResponse("The DSO party is not yet hosted on the candidate participant")
        )
      verifyNothingPrepared(service)
    }
  }

  "onboardSvSequencerPrepare" should {

    "return 503 without exporting while the sequencer is not yet observed" in {
      val service = mock[SvOnboardingSnapshotService]
      inside(
        HttpSvPublicHandler
          .onboardSvSequencerPrepareResponse(
            service,
            SnapshotKey.SequencerOnboardingState(
              SynchronizerId.tryFromString("synchronizer::dummy"),
              SequencerId(UniqueIdentifier.tryFromProtoPrimitive("sequencer::dummy")),
            ),
            observed = false,
            writeSnapshot,
          )
          .futureValue
      ) { case SvPublicResource.OnboardSvSequencerPrepareResponseServiceUnavailable(response) =>
        response.error should include("is not yet observed by the sponsor")
      }
      verifyNothingPrepared(service)
    }
  }

  "unavailableOnGrpcFailure" should {

    "turn gRPC failures of a readiness check into 503 without exposing internal details" in {
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        HttpSvPublicHandler
          .unavailableOnGrpcFailure("check", logger)(
            Future.failed(
              io.grpc.Status.UNIMPLEMENTED
                .withDescription("Readiness check failed at internal-sequencer:5009")
                .withCause(new RuntimeException("Internal readiness failure"))
                .asRuntimeException()
            )
          )
          .failed
          .futureValue should matchPattern {
          case HttpErrorWithHttpCode(
                StatusCodes.ServiceUnavailable,
                "check failed: UNIMPLEMENTED",
              ) =>
        },
        entries =>
          forExactly(1, entries) { entry =>
            entry.message should include("check failed")
            inside(entry.throwable) { case Some(e) =>
              e.getMessage should include("internal-sequencer:5009")
            }
          },
      )
    }
  }
}
