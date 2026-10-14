// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.testing.{InMemoryMetricsFactory, MetricValues}
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.stream.StreamTcpException
import org.lfdecentralizedtrust.splice.environment.BaseAppConnection
import org.lfdecentralizedtrust.splice.metrics.ScanConnectionMetrics
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.{
  BftOutcome,
  DataAvailabilityResponse,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.DataAvailabilityResponse.{
  Available,
  Never,
  NotYet,
}
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class BftCallExecutorTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem
    with MetricValues {

  private implicit val mc: MetricsContext = MetricsContext.Empty

  val tcpFailure = Future.failed(new StreamTcpException("Connection reset by peer"))
  val notFoundFailure = Future.failed(
    new BaseAppConnection.UnexpectedHttpJsonResponse(
      StatusCodes.NotFound,
      io.circe.Json.obj("error" -> io.circe.Json.fromString("not found")),
    )
  )

  class Target(val idx: Int) extends HasUrl {
    val url = Uri(s"https://$idx.example.com")
  }
  private def url(idx: Int): Uri = new Target(idx).url

  class Mocks[T](results: Seq[Future[T]]) {
    def connections(): Seq[Target] = results.indices.map(new Target(_))
    def call(i: Target): Future[T] = results(i.idx)
  }

  private def delayedResponse[T](resp: Future[T]) =
    org.apache.pekko.pattern.after(200.millis, actorSystem.scheduler)(
      resp
    )

  "When targetSuccess is 1, BftCallExecutor.executeCall" should {

    "not let a single error response decide the call when n == 2" in {

      val mocks =
        new Mocks[Boolean](Seq(delayedResponse(Future.successful(true)), tcpFailure))

      BftCallExecutor
        .executeCall(
          mocks.call,
          mocks.connections(),
          nTargetSuccess = 1,
          logger,
        )
        .futureValue
        ._1 should be(true)
    }

    // Unlike a transport exception, an http failure still counts towards the quorum.
    // Although this behaviour is mostly a result of the tech-debt of the
    // inability for the scan endpoints to specify what responses are expected (like 404)
    "but let a single http failure decide the call when n == 2" in {
      val mocks = new Mocks[Boolean](
        Seq(delayedResponse(Future.successful(true)), notFoundFailure)
      )

      BftCallExecutor
        .executeCall(mocks.call, mocks.connections(), nTargetSuccess = 1, logger)
        .failed
        .futureValue shouldBe a[BaseAppConnection.UnexpectedHttpJsonResponse]
    }

    "Forward the error response when n == 1" in {
      val mocks = new Mocks[Boolean](Seq(tcpFailure))

      BftCallExecutor
        .executeCall(mocks.call, mocks.connections(), nTargetSuccess = 1, logger)
        .failed
        .futureValue shouldBe a[StreamTcpException]
    }

    "fall through to ConsensusNotReached when all scans throw  error response" in {
      val mocks = new Mocks[Boolean]((0 until 3).map(_ => tcpFailure))

      BftCallExecutor
        .executeCall(mocks.call, mocks.connections(), nTargetSuccess = 1, logger)
        .failed
        .futureValue shouldBe a[BftCallExecutor.ConsensusNotReached[?]]

    }
  }

  "BftCallExecutor.executeCall consensus outcome reporting" should {
    def recordedLabels(metrics: ScanConnectionMetrics) =
      metrics.bftPerConnectionConsensus.valuesWithContext.toSeq.map { case (context, value) =>
        context.labels -> value
      }

    def metricsReportAgreement(metrics: ScanConnectionMetrics, idx: Int) =
      recordedLabels(metrics) should contain(
        Map(
          "request" -> "mock",
          "scan_connection" -> s"$idx.example.com",
          "consensus" -> "agree",
        ) -> 1L
      )

    def metricsReportDisagreeingSuccess(metrics: ScanConnectionMetrics, idx: Int) =
      recordedLabels(metrics) should contain(
        Map(
          "request" -> "mock",
          "scan_connection" -> s"$idx.example.com",
          "consensus" -> "disagree",
          "success" -> "true",
        ) -> 1L
      )

    def metricsReportDisagreeingFailure(
        metrics: ScanConnectionMetrics,
        idx: Int,
        httpStatusCode: Int,
    ) =
      recordedLabels(metrics) should contain(
        Map(
          "request" -> "mock",
          "scan_connection" -> s"$idx.example.com",
          "consensus" -> "disagree",
          "success" -> "false",
          "http_status" -> httpStatusCode.toString,
        ) -> 1L
      )

    "record per-connection agreement and disagreement with the consensus result" in {
      val metrics = new ScanConnectionMetrics(new InMemoryMetricsFactory)
      implicit val mc: MetricsContext = MetricsContext("request" -> "mock")
      val mocks =
        new Mocks(Seq(Future.successful(true), Future.successful(true), Future.successful(false)))
      BftCallExecutor
        .executeCall(
          mocks.call,
          mocks.connections(),
          nTargetSuccess = 2,
          logger,
          connectionMetrics = Some(metrics),
        )
        .futureValue
        ._1 should be(true)

      eventually() {
        metricsReportAgreement(metrics, 0)
        metricsReportAgreement(metrics, 1)
        metricsReportDisagreeingSuccess(metrics, 2)
      }

    }

    "record the http status and success=false for a disagreeing error response" in {
      val metrics = new ScanConnectionMetrics(new InMemoryMetricsFactory)
      implicit val mc: MetricsContext = MetricsContext("request" -> "mock")
      val mocks =
        new Mocks(Seq(Future.successful(true), Future.successful(true), notFoundFailure))

      BftCallExecutor
        .executeCall(
          mocks.call,
          mocks.connections(),
          nTargetSuccess = 2,
          logger,
          connectionMetrics = Some(metrics),
        )
        .futureValue
        ._1 should be(true)

      eventually() {
        metricsReportAgreement(metrics, 0)
        metricsReportAgreement(metrics, 1)
        metricsReportDisagreeingFailure(metrics, 2, 404)
      }
    }

    "record agreements for every connection when all return the same successful response" in {
      val metrics = new ScanConnectionMetrics(new InMemoryMetricsFactory)
      implicit val mc: MetricsContext = MetricsContext("request" -> "mock")
      val mocks = new Mocks((0 to 2).map(_ => Future.successful(true)))

      BftCallExecutor
        .executeCall(
          mocks.call,
          mocks.connections(),
          nTargetSuccess = 2,
          logger,
          connectionMetrics = Some(metrics),
        )
        .futureValue
        ._1 should be(true)

      eventually() {
        metricsReportAgreement(metrics, 0)
        metricsReportAgreement(metrics, 1)
        metricsReportAgreement(metrics, 2)
      }
    }
  }

  "BftCallExecutor.findScansWithAvailableData" should {
    "report not yet available when available + not-yet may be enough" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          Future.successful(Available: DataAvailabilityResponse),
          Future.successful(NotYet: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
        )
      )

      BftCallExecutor
        .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 2)
        .futureValue shouldBe Left(
        BftOutcome.NotYetAvailable(
          Seq(url(0)),
          Seq(url(1)),
          Seq(url(2)),
          unreachable = 0,
          required = 2,
        )
      )
    }

    "report not yet available when available + not-yet is not enough, but not-yet is not empty" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          Future.successful(Available: DataAvailabilityResponse),
          Future.successful(NotYet: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
        )
      )

      BftCallExecutor
        .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 3)
        .futureValue shouldBe Left(
        BftOutcome.NotYetAvailable(
          Seq(url(0)),
          Seq(url(1)),
          Seq(url(2)),
          unreachable = 0,
          required = 3,
        )
      )
    }

    "return the available scans when all others will never have the data" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          Future.successful(Available: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
        )
      )

      BftCallExecutor
        .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 3)
        .futureValue
        .map(_.map(_.idx)) shouldBe Right(Seq(0))
    }

    "report never available when all scans will never have the data" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          Future.successful(Never: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
        )
      )

      inside(
        BftCallExecutor
          .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 3)
          .futureValue
      ) { case Left(BftOutcome.NeverAvailable(never)) =>
        never should contain theSameElementsAs Seq(url(0), url(1), url(2))
      }
    }

    "report not yet available when every reachable scan will never have the data but a scan is unreachable" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          Future.successful(Never: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
        )
      )

      inside(
        BftCallExecutor
          .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 3, unreachable = 1)
          .futureValue
      ) { case Left(notYet: BftOutcome.NotYetAvailable) =>
        notYet.unreachable shouldBe 1
        notYet.never should contain theSameElementsAs Seq(url(0), url(1), url(2))
      }
    }

    "return scans with data if there are enough" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          Future.successful(NotYet: DataAvailabilityResponse),
          Future.successful(Available: DataAvailabilityResponse),
          Future.successful(Available: DataAvailabilityResponse),
          Future.successful(Available: DataAvailabilityResponse),
          Future.successful(Never: DataAvailabilityResponse),
        )
      )

      inside(
        BftCallExecutor
          .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 2)
          .futureValue
      ) { case Right(scans) =>
        scans should have size 2
        scans.forall(r => r.idx >= 1 && r.idx <= 3) shouldBe true
      }
    }

    "treat all exceptions as not-yet" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          notFoundFailure,
          tcpFailure,
          Future.successful(Available: DataAvailabilityResponse),
        )
      )

      inside(
        BftCallExecutor
          .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 2)
          .futureValue
      ) { case Left(notYet: BftOutcome.NotYetAvailable) =>
        notYet.available shouldBe Seq(url(2))
        notYet.notYet should contain theSameElementsAs Seq(url(0), url(1))
        notYet.never shouldBe empty
      }
    }

    "if enough scans do have data, return them despite exceptions" in {
      val mocks = new Mocks[DataAvailabilityResponse](
        Seq(
          notFoundFailure,
          tcpFailure,
          Future.successful(Available: DataAvailabilityResponse),
        )
      )

      BftCallExecutor
        .findScansWithAvailableData(mocks.connections(), logger, mocks.call, 1)
        .futureValue
        .map(_.map(_.idx)) shouldBe Right(Seq(2))

    }
  }
}
