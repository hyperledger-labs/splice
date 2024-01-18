package com.daml.network.scan.admin.api.client

import com.daml.network.admin.http.HttpErrorWithHttpCode
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.{BaseAppConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.{DomainScans, SvcScan}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpResponse,
  StatusCodes,
  Uri,
}
import org.mockito.exceptions.base.MockitoAssertionError
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Duration
import scala.concurrent.Future

// mock verification triggers this
@SuppressWarnings(Array("com.digitalasset.canton.DiscardedFuture"))
class BftScanConnectionTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)

  val clock = new SimClock(loggerFactory = loggerFactory)

  def getMockedConnections(n: Int): Seq[ScanConnection] = (0 until n).map { n =>
    val m = mock[ScanConnection]
    when(m.config).thenReturn(
      ScanAppClientConfig(NetworkAppClientConfig(s"https://$n.example.com"))
    )
    m
  }
  def makeMockReturn(mock: ScanConnection, returns: PartyId): Unit = {
    when(mock.getSvcPartyId()).thenReturn(Future.successful(returns))
  }
  def makeMockFail(mock: ScanConnection, failure: Throwable): Unit = {
    when(mock.getSvcPartyId()).thenReturn(Future.failed(failure))
  }
  def getBft(
      initialConnections: Seq[ScanConnection],
      connectionBuilder: Uri => Future[ScanConnection] = _ =>
        Future.failed(new RuntimeException("Shouldn't be refreshing!")),
  ) = new BftScanConnection(
    new BftScanConnection.Bft(
      initialConnections,
      connectionBuilder,
      NonNegativeFiniteDuration.ofSeconds(1),
      retryProvider,
      loggerFactory,
    ),
    clock,
    retryProvider,
    loggerFactory,
  )
  val partyIdA = PartyId.tryFromProtoPrimitive("whatever::a")
  val partyIdB = PartyId.tryFromProtoPrimitive("whatever::b")

  "BftScanConnection" should {

    "return the agreed response when all agree" in {
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturn(_, partyIdA))
      val bft = getBft(connections)

      for {
        svcPartyId <- bft.getSvcPartyId()
      } yield svcPartyId should be(partyIdA)
    }

    "return the agreed response when 2f+1 agree and log disagreements" in {
      val connections = getMockedConnections(n = 4)
      val disagreeing = connections.head
      makeMockReturn(disagreeing, partyIdB)
      val agreeing = connections.drop(1)
      agreeing.foreach(makeMockReturn(_, partyIdA))

      val bft = getBft(connections)

      for {
        svcPartyId <- bft.getSvcPartyId()
      } yield svcPartyId should be(partyIdA)
    }

    "forward the failure if the agreement is a failure" in {
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturn(_, partyIdA))
      val bft = getBft(connections)

      val failure = new BaseAppConnection.UnexpectedHttpResponse(
        HttpResponse(
          StatusCodes.NotFound,
          entity = HttpEntity(ContentTypes.`application/json`, """{"error":"not_found"}"""),
        )
      )
      connections.foreach(makeMockFail(_, failure))

      for {
        failure <- bft.getSvcPartyId().failed
      } yield failure should be(failure)
    }

    "fail when consensus cannot be reached" in {
      val connections = getMockedConnections(n = 4)
      connections.zipWithIndex.foreach { case (connMock, idx) =>
        makeMockReturn(connMock, PartyId.tryFromProtoPrimitive(s"whatever::$idx"))
      }
      val bft = getBft(connections)

      loggerFactory.assertLogs(
        for {
          failure <- bft.getSvcPartyId().failed
        } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
          code should be(StatusCodes.BadGateway)
          message should include("Failed to reach consensus from 3 Scan nodes.") // 2f+1 = 3
        },
        _.warningMessage should include("Consensus not reached."),
      )
    }

    "periodically refresh the list of scans" in {
      val domainId = DomainId.tryFromString("domain::id")
      val connections = getMockedConnections(n = 2)

      connections.foreach(makeMockReturn(_, partyIdA))

      connections.foreach { connection =>
        when(connection.getCoinRulesDomain).thenReturn(() => _ => Future.successful(domainId))
        when(connection.listSvcScans()).thenReturn(
          Future.successful(
            Seq(
              DomainScans(
                domainId,
                scans = connections.zipWithIndex.map { case (_, n) =>
                  SvcScan(s"https://$n.example.com", n.toString)
                },
                Map.empty,
              )
            )
          )
        )
      }

      // we initialize with just the first one, and the second one will be "built" when we refresh
      val bft = getBft(connections.take(1), _ => Future.successful(connections(1)))
      clock.advance(Duration.ofSeconds(2))

      // eventually the refresh goes through and the second connection is used
      eventually() {
        val result = bft.getSvcPartyId().futureValue
        try { verify(connections(1), atLeast(1)).getSvcPartyId() }
        catch { case cause: MockitoAssertionError => fail("Mockito fail", cause) }
        result should be(partyIdA)
      }
    }

  }

}
