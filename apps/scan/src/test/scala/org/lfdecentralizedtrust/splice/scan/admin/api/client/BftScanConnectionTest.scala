package org.lfdecentralizedtrust.splice.scan.admin.api.client

import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.{
  BaseAppConnection,
  SpliceLedgerClient,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.{
  DomainScans,
  DsoScan,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, Contract, ContractWithState}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpResponse,
  StatusCodes,
  Uri,
}
import org.mockito.exceptions.base.MockitoAssertionError
import org.scalatest.wordspec.AsyncWordSpec

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules as amuletrulesCodegen
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.tracing.TraceContext
import org.slf4j.event.Level

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

  val domainId = DomainId.tryFromString("domain::id")

  def getMockedConnections(n: Int): Seq[SingleScanConnection] = {
    val connections = (0 until n).map { n =>
      val m = mock[SingleScanConnection]
      when(m.config).thenReturn(
        ScanAppClientConfig(NetworkAppClientConfig(s"https://$n.example.com"))
      )
      m
    }
    connections.foreach { connection =>
      // all of this is noise...
      when(connection.getAmuletRulesWithState(eqTo(None))(any[ExecutionContext], any[TraceContext]))
        .thenReturn(
          Future.successful(
            ContractWithState(
              Contract(
                amuletrulesCodegen.AmuletRules.TEMPLATE_ID,
                new amuletrulesCodegen.AmuletRules.ContractId("whatever"),
                new amuletrulesCodegen.AmuletRules(
                  partyIdA.toProtoPrimitive,
                  SpliceUtil.defaultAmuletConfigSchedule(
                    NonNegativeFiniteDuration(Duration.ofMinutes(10)),
                    10,
                    domainId,
                  ),
                  false,
                ),
                ByteString.EMPTY,
                Instant.EPOCH,
              ),
              ContractState.Assigned(domainId), // ...except this
            )
          )
        )
      when(connection.listDsoScans()(any[TraceContext])).thenReturn(
        Future.successful(
          Seq(
            DomainScans(
              domainId,
              scans = connections.zipWithIndex.map { case (_, n) =>
                DsoScan(s"https://$n.example.com", n.toString)
              },
              Map.empty,
            )
          )
        )
      )
    }
    connections
  }
  def makeMockReturn(mock: SingleScanConnection, returns: PartyId): Unit = {
    when(mock.getDsoPartyId()).thenReturn(Future.successful(returns))
  }
  def makeMockFail(mock: SingleScanConnection, failure: Throwable): Unit = {
    when(mock.getDsoPartyId()).thenReturn(Future.failed(failure))
  }
  def getBft(
      initialConnections: Seq[SingleScanConnection],
      connectionBuilder: Uri => Future[SingleScanConnection] = _ =>
        Future.failed(new RuntimeException("Shouldn't be refreshing!")),
      initialFailedConnections: Map[Uri, Throwable] = Map.empty,
  ) = {
    new BftScanConnection(
      mock[SpliceLedgerClient],
      NonNegativeFiniteDuration.ofSeconds(1),
      new BftScanConnection.Bft(
        initialConnections,
        initialFailedConnections,
        connectionBuilder,
        NonNegativeFiniteDuration.ofSeconds(1),
        retryProvider,
        loggerFactory,
      ),
      clock,
      retryProvider,
      loggerFactory,
    )
  }
  val partyIdA = PartyId.tryFromProtoPrimitive("whatever::a")
  val partyIdB = PartyId.tryFromProtoPrimitive("whatever::b")

  "BftScanConnection" should {

    "return the agreed response when all agree" in {
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturn(_, partyIdA))
      val bft = getBft(connections)

      for {
        dsoPartyId <- bft.getDsoPartyId()
      } yield dsoPartyId should be(partyIdA)
    }

    "return the agreed response when 2f+1 agree and log disagreements" in {
      val connections = getMockedConnections(n = 4)
      val disagreeing = connections.head
      makeMockReturn(disagreeing, partyIdB)
      val agreeing = connections.drop(1)
      agreeing.foreach(makeMockReturn(_, partyIdA))

      val bft = getBft(connections)

      for {
        dsoPartyId <- bft.getDsoPartyId()
      } yield dsoPartyId should be(partyIdA)
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
        failure <- bft.getDsoPartyId().failed
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
          failure <- bft.getDsoPartyId().failed
        } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
          code should be(StatusCodes.BadGateway)
          message should include("Failed to reach consensus from 3 Scan nodes.") // 2f+1 = 3
        },
        _.warningMessage should include("Consensus not reached."),
      )
    }

    "periodically refresh the list of scans" in {
      val connections = getMockedConnections(n = 2)

      connections.foreach(makeMockReturn(_, partyIdA))

      // we initialize with just the first one, and the second one will be "built" when we refresh
      val bft = getBft(connections.take(1), _ => Future.successful(connections(1)))
      clock.advance(Duration.ofSeconds(2))

      // eventually the refresh goes through and the second connection is used
      eventually() {
        val result = bft.getDsoPartyId().futureValue
        try { verify(connections(1), atLeast(1)).getDsoPartyId() }
        catch { case cause: MockitoAssertionError => fail("Mockito fail", cause) }
        result should be(partyIdA)
      }
    }

    "fail if too many Scans failed to connect" in {
      // f = (1ok + 3bad - 1) / 3 = 1
      // 1 Scan is not enough for f+1=2
      val connections = getMockedConnections(n = 1)
      val bft = getBft(
        connections,
        initialFailedConnections = Map(
          Uri("https://failure1.example.com") -> new RuntimeException("Failed"),
          Uri("https://failure2.example.com") -> new RuntimeException("Failed"),
          Uri("https://failure3.example.com") -> new RuntimeException("Failed"),
        ),
      )

      loggerFactory.assertLogs(
        for {
          failure <- bft.getDsoPartyId().failed
        } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
          code should be(StatusCodes.BadGateway)
          message should include(
            s"Only 1 scan instances are reachable (out of 4 configured ones), which are fewer than the necessary 2 to achieve BFT guarantees."
          )
        },
        _.warningMessage should include(
          s"Only 1 scan instances are reachable (out of 4 configured ones), which are fewer than the necessary 2 to achieve BFT guarantees."
        ),
      )
    }

    "work with partial failures" in {
      // f = (2ok + 2bad - 1) / 3 = 1
      // 2 Scans is JUST enough for f+1=2
      val connections = getMockedConnections(n = 3)
      connections.foreach(makeMockReturn(_, partyIdA))
      val bft = getBft(
        connections,
        initialFailedConnections = Map(
          Uri("https://failure1.example.com") -> new RuntimeException("Failed"),
          Uri("https://failure2.example.com") -> new RuntimeException("Failed"),
        ),
      )

      for {
        dsoPartyId <- bft.getDsoPartyId()
      } yield dsoPartyId should be(partyIdA)
    }

    "retry on failure" in {
      val connections = getMockedConnections(n = 4)
      val bft = getBft(connections)

      connections.zipWithIndex.foreach { case (mock, n) =>
        val failure = new RuntimeException(s"Mock #$n Failed. Hopefully only once.")
        // fail once, then succeed
        when(mock.getDsoPartyId()).thenReturn(Future.failed(failure), Future.successful(partyIdA))
      }

      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
        for {
          result <- bft.getDsoPartyId()
        } yield result should be(partyIdA),
        logs => {
          logs.exists(log =>
            log.level == Level.INFO && log.message.contains(
              "Consensus not reached. Will be retried."
            )
          ) should be(true)
        },
      )
    }

    "use all available connections on failures" in {
      val connections = getMockedConnections(n = 4)
      connections.zipWithIndex.foreach { case (connMock, idx) =>
        makeMockReturn(connMock, PartyId.tryFromProtoPrimitive(s"whatever::$idx"))
      }
      val bft = getBft(connections)
      bft.getDsoPartyId().failed.map { _ =>
        connections.foreach(mockConnection => verify(mockConnection, atLeast(1)).getDsoPartyId())
        succeed
      }
    }
  }

  "ScanAggregatesConnection" should {
    import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator.*
    val round = 0L
    val roundTotals = RoundTotals(round)
    val roundPartyTotals = RoundPartyTotals(round, "party-id")
    val roundAggregate = RoundAggregate(roundTotals, Vector(roundPartyTotals))

    "get round aggregates from scans that report having the round aggregate" in {
      val connections = getMockedConnections(n = 10)
      connections.zipWithIndex.foreach { case (mock, index) =>
        if (index < 2) {
          when(mock.getAggregatedRounds())
            .thenReturn(Future.successful(Some(RoundRange(round, round))))
          when(mock.getRoundAggregate(round)).thenReturn(Future.successful(Some(roundAggregate)))
        } else {
          when(mock.getAggregatedRounds())
            .thenReturn(Future.successful(None))
          val failure = new RuntimeException(s"Mock #$n Failed getting round aggregate.")
          when(mock.getRoundAggregate(round)).thenReturn(Future.failed(failure))
        }
      }
      val bft = getBft(connections)
      val con =
        new ScanAggregatesConnection(bft, retryProvider, retryProvider.loggerFactory)
      val result = con.getRoundAggregate(round).futureValue
      result shouldBe Some(roundAggregate)
    }

    "Not get round aggregates from scans that report having the round aggregate if too many fail" in {
      val connections = getMockedConnections(n = 10)
      connections.zipWithIndex.foreach { case (mock, index) =>
        when(mock.getAggregatedRounds())
          .thenReturn(Future.successful(Some(RoundRange(round, round))))

        if (index < 2) {
          when(mock.getRoundAggregate(round)).thenReturn(Future.successful(Some(roundAggregate)))
        } else {
          val failure = new RuntimeException(s"Mock #$n Failed getting round aggregate.")
          when(mock.getRoundAggregate(round)).thenReturn(Future.failed(failure))
        }
      }
      val bft = getBft(connections)
      val con =
        new ScanAggregatesConnection(bft, retryProvider, retryProvider.loggerFactory)
      con
        .getRoundAggregate(round)
        .failed
        .futureValue shouldBe a[BftScanConnection.ConsensusNotReached]
    }

    "Not get round aggregates from scans if too many disagree, while reporting to have the aggregated round" in {
      val connections = getMockedConnections(n = 4)

      connections.zipWithIndex.foreach { case (mock, index) =>
        when(mock.getAggregatedRounds())
          .thenReturn(Future.successful(Some(RoundRange(round, round))))
        val diffRoundPartyTotals =
          RoundPartyTotals(round, "party-id", appRewards = BigDecimal(index))
        val diffRoundAggregate = RoundAggregate(roundTotals, Vector(diffRoundPartyTotals))

        when(mock.getRoundAggregate(round)).thenReturn(Future.successful(Some(diffRoundAggregate)))
      }
      val bft = getBft(connections)
      val con =
        new ScanAggregatesConnection(bft, retryProvider, retryProvider.loggerFactory)

      con
        .getRoundAggregate(round)
        .failed
        .futureValue shouldBe a[BftScanConnection.ConsensusNotReached]
    }
  }
}
