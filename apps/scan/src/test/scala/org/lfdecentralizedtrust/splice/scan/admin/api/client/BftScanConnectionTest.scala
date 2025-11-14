package org.lfdecentralizedtrust.splice.scan.admin.api.client

import com.daml.ledger.api.v2.{CommandsOuterClass, TraceContextOuterClass}
import com.daml.ledger.javaapi.data as javaApi
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.apache.pekko.http.scaladsl.model.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules as amuletrulesCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.environment.{
  BaseAppConnection,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  ErrorResponse,
  RoundPartyTotals as HttpRoundPartyTotals,
  RoundTotals as HttpRoundTotals,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.Bft
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.{
  DomainScans,
  DsoScan,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  Contract,
  ContractWithState,
  DomainRecordTimeRange,
  FactoryChoiceWithDisclosures,
  SpliceUtil,
}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind
import org.mockito.exceptions.base.MockitoAssertionError
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.event.Level

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

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

  val synchronizerId = SynchronizerId.tryFromString("domain::id")

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
      when(
        connection.getAmuletRulesWithState(
          any[Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]]
        )(any[ExecutionContext], any[TraceContext])
      )
        .thenReturn(
          Future.successful(
            ContractWithState(
              Contract(
                amuletrulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
                new amuletrulesCodegen.AmuletRules.ContractId("whatever"),
                new amuletrulesCodegen.AmuletRules(
                  partyIdA.toProtoPrimitive,
                  SpliceUtil.defaultAmuletConfigSchedule(
                    NonNegativeFiniteDuration(Duration.ofMinutes(10)),
                    10,
                    synchronizerId,
                  ),
                  false,
                ),
                ByteString.EMPTY,
                Instant.EPOCH,
              ),
              ContractState.Assigned(synchronizerId), // ...except this
            )
          )
        )
      when(connection.listDsoScans()(any[TraceContext])).thenReturn(
        Future.successful(
          Seq(
            DomainScans(
              synchronizerId,
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
  def makeMockReturnMigrationInfo(
      mock: SingleScanConnection,
      migrationId: Long,
      info: Option[SourceMigrationInfo],
  ): Unit = {
    when(mock.getMigrationInfo(migrationId)).thenReturn(Future.successful(info))
  }
  def makeMockFailMigrationInfo(
      mock: SingleScanConnection,
      migrationId: Long,
      failure: Throwable,
  ): Unit = {
    when(mock.getMigrationInfo(migrationId)).thenReturn(Future.failed(failure))
  }
  def makeMockReturnUpdatesBefore(
      mock: SingleScanConnection,
      migrationId: Long,
      before: CantonTimestamp,
      atOrAfter: CantonTimestamp,
      updates: Seq[UpdateHistoryResponse],
      count: Int,
  ): Unit = {
    when(mock.getUpdatesBefore(migrationId, synchronizerId, before, Some(atOrAfter), count))
      .thenReturn(Future.successful(updates))
  }
  def makeMockReturnImportUpdates(
      mock: SingleScanConnection,
      migrationId: Long,
      after: String,
      updates: Seq[UpdateHistoryResponse],
      count: Int,
  ): Unit = {
    when(mock.getImportUpdates(migrationId, after, count))
      .thenReturn(Future.successful(updates))
  }
  def makeMockFailUpdatesBefore(
      mock: SingleScanConnection,
      before: CantonTimestamp,
      failure: Throwable,
  ): Unit = {
    when(mock.getUpdatesBefore(0, synchronizerId, before, None, 2))
      .thenReturn(Future.failed(failure))
  }
  private def jtime(n: Int) = Instant.EPOCH.plusSeconds(n.toLong)
  private def ctime(n: Int) = CantonTimestamp.assertFromInstant(jtime(n))
  private def testUpdate(n: Int): UpdateHistoryResponse = {
    UpdateHistoryResponse(
      update = TransactionTreeUpdate(
        tree = new javaApi.Transaction(
          s"updateId$n",
          s"commandId$n",
          s"workflowId$n",
          jtime(n),
          java.util.Collections.emptyList(),
          n.toLong,
          synchronizerId.toProtoPrimitive,
          TraceContextOuterClass.TraceContext.getDefaultInstance,
          jtime(n),
        )
      ),
      synchronizerId = synchronizerId,
    )
  }
  val refreshSeconds = 10000L
  def getBft(
      initialConnections: Seq[SingleScanConnection],
      connectionBuilder: Uri => Future[SingleScanConnection] = _ =>
        Future.failed(new RuntimeException("Shouldn't be refreshing!")),
      initialFailedConnections: Map[Uri, Throwable] = Map.empty,
  ) = {
    new BftScanConnection(
      mock[SpliceLedgerClient],
      NonNegativeFiniteDuration.ofSeconds(1),
      new BftScanConnection.AllDsoScansBft(
        initialConnections,
        initialFailedConnections,
        connectionBuilder,
        Bft.getScansInDsoRules,
        NonNegativeFiniteDuration.ofSeconds(refreshSeconds),
        retryProvider,
        loggerFactory,
      ),
      clock,
      retryProvider,
      loggerFactory,
    )
  }
  val notFoundFailure = new BaseAppConnection.UnexpectedHttpResponse(
    HttpResponse(
      StatusCodes.NotFound,
      entity = HttpEntity(ContentTypes.`application/json`, """{"error":"not_found"}"""),
    )
  )
  val notFoundCommandFailure = HttpCommandException(
    HttpRequest(),
    StatusCodes.NotFound,
    HttpCommandException.ErrorResponseBody(ErrorResponse("Whatever thing was not found")),
  )
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
      val bft = getBft(connections)

      connections.foreach(makeMockFail(_, notFoundFailure))

      for {
        failure <- bft.getDsoPartyId().failed
      } yield failure should be(notFoundFailure)
    }

    "forward the failure if the agreement is a command failure" in {
      val connections = getMockedConnections(n = 4)
      val bft = getBft(connections)

      connections.foreach(makeMockFail(_, notFoundCommandFailure))

      for {
        failure <- bft.getDsoPartyId().failed
      } yield failure should be(notFoundCommandFailure)
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
          message should include("Failed to reach consensus from 3 Scan nodes") // 2f+1 = 3
        },
        _.warningMessage should include("Consensus not reached."),
      )
    }

    "periodically refresh the list of scans" in {
      val connections = getMockedConnections(n = 2)

      connections.foreach(makeMockReturn(_, partyIdA))

      // we initialize with just the first one, and the second one will be "built" when we refresh
      val refreshCalled = new AtomicInteger(0)
      val bft = getBft(
        connections.take(1),
        _ => {
          refreshCalled.incrementAndGet()
          Future.successful(connections(1))
        },
      )
      clock.advance(Duration.ofSeconds(refreshSeconds + 1))
      // even after advancing it shouldn't refresh yet, as that's less than refreshSeconds
      clock.advance(Duration.ofSeconds(1))
      clock.advance(Duration.ofSeconds(1))
      clock.advance(Duration.ofSeconds(1))
      clock.advance(Duration.ofSeconds(1))

      // eventually the refresh goes through and the second connection is used
      eventually() {
        refreshCalled.intValue() should be(1)
        val result = bft.getDsoPartyId().futureValue
        try { verify(connections(1), atLeast(1)).getDsoPartyId() }
        catch { case cause: MockitoAssertionError => fail("Mockito fail", cause) }
        result should be(partyIdA)
      }
    }

    "refresh the list of scans faster if there are not enough available scans" in {
      val connections = getMockedConnections(n = 4)
      val connectionsMap = connections.map(c => c.config.adminApi.url -> c).toMap

      connections.foreach(makeMockReturn(_, partyIdA))
      val refreshCalled = connections.map(_.config.adminApi.url -> new AtomicInteger(0)).toMap

      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
        {
          // all failed until retried enough times
          val bft = getBft(
            Seq.empty,
            uri => {
              val calls = refreshCalled(uri).incrementAndGet()
              if (calls > 3) {
                Future.successful(connectionsMap(uri))
              } else {
                Future.failed(new RuntimeException("some'rror"))
              }
            },
            initialFailedConnections = connections
              .map(connection => connection.config.adminApi.url -> new RuntimeException("Failed"))
              .toMap,
          )
          // trigger the first refresh, this is only required in tests, prod code retries already on BftScanConnection init
          clock.advance(Duration.ofSeconds(refreshSeconds + 1))
          // and then refresh until it's called enough times
          eventually() {
            clock.advance(Duration.ofSeconds(1))
            forAll(refreshCalled) { case (_, calls) =>
              calls.intValue() should be >= 3
            }
          }

          // eventually the refresh goes through and the second connection is used
          eventually() {
            bft.scanList.scanConnections.open should have size 4
            bft.scanList.scanConnections.failed should be(0)
            val result = bft.getDsoPartyId().futureValue
            try { verify(connections(1), atLeast(1)).getDsoPartyId() }
            catch { case cause: MockitoAssertionError => fail("Mockito fail", cause) }
            result should be(partyIdA)
          }
        },
        entries =>
          forAll(entries) { entry =>
            entry.warningMessage should include("Failed to connect to scan").or(
              include("which are fewer than the necessary")
            )
          },
      )

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
            s"Only 1 scan instances can be used (out of 4 configured ones), which are fewer than the necessary 2 to achieve BFT guarantees."
          )
        },
        _.warningMessage should include(
          s"Only 1 scan instances can be used (out of 4 configured ones), which are fewer than the necessary 2 to achieve BFT guarantees."
        ),
      )
    }

    "work with partial failures" in {
      // f = (2ok + 2bad - 1) / 3 = 1
      // 2 Scans is JUST enough for f+1=2
      val connections = getMockedConnections(n = 2)
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

    "reach consensus for token standard transfer factory" in {
      val transfer = new transferinstructionv1.Transfer(
        "sender",
        "receiver",
        BigDecimal(2).bigDecimal,
        new holdingv1.InstrumentId("admin", "Amulet"),
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(60),
        java.util.List.of(),
        new metadatav1.Metadata(java.util.Map.of()),
      )
      def arg() = new transferinstructionv1.TransferFactory_Transfer(
        "admin",
        transfer,
        new metadatav1.ExtraArgs(
          new metadatav1.ChoiceContext(java.util.Map.of()),
          new metadatav1.Metadata(java.util.Map.of()),
        ),
      )

      val connections = getMockedConnections(n = 4)
      connections.foreach { connMock =>
        when(
          connMock.getTransferFactory(any[transferinstructionv1.TransferFactory_Transfer])(
            any[ExecutionContext],
            any[TraceContext],
          )
        )
          .thenReturn(Future.successful {
            val disclosedContracts = Seq(
              CommandsOuterClass.DisclosedContract
                .newBuilder()
                .setContractId("disclosed")
                .setCreatedEventBlob(
                  ByteString.copyFrom("let's pretend that this is a proper blob".getBytes("UTF-8"))
                )
                .setSynchronizerId("sync")
                .setTemplateId(holdingv1.Holding.TEMPLATE_ID.toProto)
                .build()
            )
            (
              FactoryChoiceWithDisclosures(
                new transferinstructionv1.TransferFactory.ContractId("factory"),
                arg(),
                disclosedContracts,
              ),
              TransferKind.Direct,
            )
          })
      }

      val bft = getBft(connections)
      for {
        _ <- bft.getTransferFactory(arg())
      } yield succeed
    }

  }

  "BftScanConnection for backfilling" should {
    "return the migration info response when all agree" in {
      val connections = getMockedConnections(n = 4)
      val infoResponse =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange = Map(synchronizerId -> DomainRecordTimeRange(ctime(1), ctime(2))),
            lastImportUpdateId = Some("updateId1"),
            complete = true,
            importUpdatesComplete = true,
          )
        )
      connections.foreach(makeMockReturnMigrationInfo(_, 0, infoResponse))
      val bft = getBft(connections)

      for {
        migrationInfo <- bft.getMigrationInfo(0)
      } yield migrationInfo should be(infoResponse)
    }

    "return the union migration info response when they don't agree" in {
      val connections = getMockedConnections(n = 4)
      def infoResponse(start: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = if (complete) Some(0) else None,
            recordTimeRange = Map(synchronizerId -> DomainRecordTimeRange(ctime(start), ctime(10))),
            lastImportUpdateId = Some("updateId1"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )
      makeMockReturnMigrationInfo(connections(0), 1, None)
      makeMockReturnMigrationInfo(connections(1), 1, infoResponse(1, true))
      makeMockReturnMigrationInfo(connections(2), 1, infoResponse(2, false))
      makeMockReturnMigrationInfo(connections(3), 1, infoResponse(3, false))
      val bft = getBft(connections)

      for {
        migrationInfo <- bft.getMigrationInfo(1)
      } yield migrationInfo should be(
        Some(
          SourceMigrationInfo(
            previousMigrationId = Some(0),
            recordTimeRange = Map(synchronizerId -> DomainRecordTimeRange(ctime(1), ctime(10))),
            lastImportUpdateId = Some("updateId1"),
            complete = true,
            importUpdatesComplete = true,
          )
        )
      )
    }

    "return the updates response when all agree" in {
      val connections = getMockedConnections(n = 4)
      val infoResponse =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange = Map(synchronizerId -> DomainRecordTimeRange(ctime(1), ctime(2))),
            lastImportUpdateId = Some("updateId1"),
            complete = true,
            importUpdatesComplete = true,
          )
        )
      connections.foreach(makeMockReturnMigrationInfo(_, 0, infoResponse))
      val updatesResponse = (1 to 2).map(testUpdate)
      connections.foreach(makeMockReturnUpdatesBefore(_, 0, ctime(3), ctime(1), updatesResponse, 2))
      val bft = getBft(connections)

      for {
        migrationInfo <- bft.getUpdatesBefore(0, synchronizerId, ctime(3), None, 2)
      } yield migrationInfo should be(updatesResponse)
    }

    "return the updates response when they have different time ranges" in {
      val connections = getMockedConnections(n = 4)
      def infoResponse(first: Int, last: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange =
              Map(synchronizerId -> DomainRecordTimeRange(ctime(first), ctime(last))),
            lastImportUpdateId = Some("updateId1"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )

      // We'll be asking for up to 10 updates before time 5.
      // The BFT algorithm will only ask peer scans for updates between time 3 and 5,
      // because that is the intersection of time ranges from the scans that have some data.
      val updates3to5 = (3 to 5).map(testUpdate)

      // SV0: doesn't know anything - should not be used
      makeMockReturnMigrationInfo(connections(0), 0, None)

      // SV1: has complete history (1 to 10)
      makeMockReturnMigrationInfo(connections(1), 0, infoResponse(1, 10, true))
      makeMockReturnUpdatesBefore(connections(1), 0, ctime(5), ctime(3), updates3to5, 10)

      // SV2: has partial history (3 to 10)
      makeMockReturnMigrationInfo(connections(2), 0, infoResponse(3, 10, false))
      makeMockReturnUpdatesBefore(connections(2), 0, ctime(5), ctime(3), updates3to5, 10)

      // SV3: has partial history (7 to 10) - should not be used
      makeMockReturnMigrationInfo(connections(3), 0, infoResponse(7, 10, false))

      val bft = getBft(connections)

      for {
        migrationInfo <- bft.getUpdatesBefore(0, synchronizerId, ctime(5), None, 10)
      } yield migrationInfo should be(updates3to5)
    }

    "return the updates response when only one scan has data" in {
      val connections = getMockedConnections(n = 4)
      def infoResponse(first: Int, last: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange =
              Map(synchronizerId -> DomainRecordTimeRange(ctime(first), ctime(last))),
            lastImportUpdateId = Some("updateId1"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )

      val updates1to5 = (1 to 5).map(testUpdate)

      // SV1: has complete history (1 to 10)
      makeMockReturnMigrationInfo(connections(0), 0, infoResponse(1, 10, true))
      makeMockReturnUpdatesBefore(connections(0), 0, ctime(5), ctime(1), updates1to5, 10)

      // SV2-4: has partial history (7 to 10) - should not be used
      makeMockReturnMigrationInfo(connections(1), 0, infoResponse(7, 10, false))
      makeMockReturnMigrationInfo(connections(2), 0, infoResponse(7, 10, false))
      makeMockReturnMigrationInfo(connections(3), 0, infoResponse(7, 10, false))

      val bft = getBft(connections)

      // It's ok to accept the answer from a single scan, because all other scans claim to have no data.
      for {
        migrationInfo <- bft.getUpdatesBefore(0, synchronizerId, ctime(5), None, 10)
      } yield migrationInfo should be(updates1to5)
    }

    "return the updates response when just enough scans have data and the rest is unavailable" in {
      val connections = getMockedConnections(n = 3)
      def infoResponse(first: Int, last: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange =
              Map(synchronizerId -> DomainRecordTimeRange(ctime(first), ctime(last))),
            lastImportUpdateId = Some("updateId1"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )

      val updates1to5 = (1 to 5).map(testUpdate)

      // SV1-3: has complete history (1 to 10)
      (0 to 2).foreach { i =>
        makeMockReturnMigrationInfo(connections(i), 0, infoResponse(1, 10, true))
        makeMockReturnUpdatesBefore(connections(i), 0, ctime(5), ctime(1), updates1to5, 10)
      }

      // SV4-5: failed
      val failedConnections = Map(
        Uri("https://failure4.example.com") -> new RuntimeException("Failed"),
        Uri("https://failure5.example.com") -> new RuntimeException("Failed"),
      )

      val bft = getBft(
        connections,
        initialFailedConnections = failedConnections,
      )

      // It's ok to accept the matching answer from the two scans, because we have f=1.
      for {
        migrationInfo <- bft.getUpdatesBefore(0, synchronizerId, ctime(5), None, 10)
      } yield migrationInfo should be(updates1to5)
    }

    "fail when not enough scans have data and the rest is unavailable" in {
      val connections = getMockedConnections(n = 2)
      def infoResponse(first: Int, last: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange =
              Map(synchronizerId -> DomainRecordTimeRange(ctime(first), ctime(last))),
            lastImportUpdateId = Some("updateId1"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )

      val updates1to5 = (1 to 5).map(testUpdate)

      // SV1-2: has complete history (1 to 10)
      (0 to 1).foreach { i =>
        makeMockReturnMigrationInfo(connections(i), 0, infoResponse(1, 10, true))
        makeMockReturnUpdatesBefore(connections(i), 0, ctime(5), ctime(1), updates1to5, 10)
      }

      // SV3-7: failed
      val failedConnections = Map(
        Uri("https://failure3.example.com") -> new RuntimeException("Failed"),
        Uri("https://failure4.example.com") -> new RuntimeException("Failed"),
        Uri("https://failure5.example.com") -> new RuntimeException("Failed"),
        Uri("https://failure6.example.com") -> new RuntimeException("Failed"),
        Uri("https://failure7.example.com") -> new RuntimeException("Failed"),
      )

      val bft = getBft(
        connections,
        initialFailedConnections = failedConnections,
      )

      // Can't accept the matching answer from the two remaining scans, we have f=2, and they could be both malicious
      for {
        failure <- bft.getUpdatesBefore(0, synchronizerId, ctime(5), None, 10).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
        code should be(StatusCodes.BadGateway)
        message should include(
          s"Only 2 scan instances can be used (out of 7 configured ones), which are fewer than the necessary 3 to achieve BFT guarantees."
        )
      }
    }

    "fail when consensus cannot be reached for import updates info" in {
      val connections = getMockedConnections(n = 7) // f=2
      def infoResponse(last: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange = Map(synchronizerId -> DomainRecordTimeRange(ctime(1), ctime(10))),
            lastImportUpdateId = Some(s"updateId${last}"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )

      def mockResponses(connection: Int, last: Int) = {
        makeMockReturnMigrationInfo(connections(connection), 0, infoResponse(last, true))
      }

      // Two scans return last id = 2
      mockResponses(0, 2)
      mockResponses(1, 2)
      // Two scans return last id = 3
      mockResponses(2, 3)
      mockResponses(3, 3)
      // Two scan returns last id = 4
      mockResponses(4, 4)
      mockResponses(5, 4)
      // One scan returns last id = 5
      mockResponses(6, 5)

      val bft = getBft(connections)

      // Note: getUpdatesBefore() doesn't produce WARN logs, so we don't need to suppress them
      for {
        failure <- bft.getMigrationInfo(0).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
        code should be(StatusCodes.BadGateway)
        message should include("Failed to reach consensus from 5 Scan nodes")
      }
    }

    "fail when consensus cannot be reached for updates" in {
      val connections = getMockedConnections(n = 7) // f=2
      def infoResponse(first: Int, last: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange =
              Map(synchronizerId -> DomainRecordTimeRange(ctime(first), ctime(last))),
            lastImportUpdateId = Some("updateId1"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )

      def mockResponses(connection: Int, updates: Seq[Int]) = {
        makeMockReturnMigrationInfo(connections(connection), 0, infoResponse(1, 10, true))
        makeMockReturnUpdatesBefore(
          connections(connection),
          0,
          ctime(5),
          ctime(1),
          updates.map(testUpdate),
          10,
        )
      }

      // Two scans return updates [1,2,3,5]
      mockResponses(0, Seq(1, 2, 3, 5))
      mockResponses(1, Seq(1, 2, 3, 5))
      // Two scans return updates [1,3,4,5]
      mockResponses(2, Seq(1, 3, 4, 5))
      mockResponses(3, Seq(1, 3, 4, 5))
      // Two scans return updates [1,2,3,4,5]
      mockResponses(4, Seq(1, 2, 3, 4, 5))
      mockResponses(5, Seq(1, 2, 3, 4, 5))
      // One scans returns updates [1,5]
      mockResponses(6, Seq(1, 5))

      val bft = getBft(connections)

      // Note: getUpdatesBefore() doesn't produce WARN logs, so we don't need to suppress them
      for {
        failure <- bft.getUpdatesBefore(0, synchronizerId, ctime(5), None, 10).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
        code should be(StatusCodes.BadGateway)
        message should include("Failed to reach consensus from 5 Scan nodes")
      }
    }

    "fail when consensus cannot be reached for import updates" in {
      val connections = getMockedConnections(n = 7) // f=2
      def infoResponse(last: Int, complete: Boolean) =
        Some(
          SourceMigrationInfo(
            previousMigrationId = None,
            recordTimeRange = Map(),
            lastImportUpdateId = Some(s"updateId${last}"),
            complete = complete,
            importUpdatesComplete = complete,
          )
        )

      def mockResponses(connection: Int, last: Int, updates: Seq[Int]) = {
        makeMockReturnMigrationInfo(connections(connection), 0, infoResponse(last, true))
        makeMockReturnImportUpdates(
          connections(connection),
          0,
          "",
          updates.map(testUpdate),
          10,
        )
      }

      // Two scans return updates [1,2,3,5]
      mockResponses(0, 5, Seq(1, 2, 3, 5))
      mockResponses(1, 5, Seq(1, 2, 3, 5))
      // Two scans return updates [1,3,4,5]
      mockResponses(2, 5, Seq(1, 3, 4, 5))
      mockResponses(3, 5, Seq(1, 3, 4, 5))
      // Two scans return updates [1,2,3,4,5]
      mockResponses(4, 5, Seq(1, 2, 3, 4, 5))
      mockResponses(5, 5, Seq(1, 2, 3, 4, 5))
      // One scans returns updates [1,5]
      mockResponses(6, 5, Seq(1, 5))

      val bft = getBft(connections)

      // Note: getImportUpdates() doesn't produce WARN logs, so we don't need to suppress them
      for {
        failure <- bft.getImportUpdates(0, "", 10).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
        code should be(StatusCodes.BadGateway)
        message should include("Failed to reach consensus from 5 Scan nodes")
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

    "get BFT round aggregates from scans that report having the round aggregate ignoring balance fields" in {
      val round = 0L
      def randomValue = BigDecimal(Random.nextInt(50) + 1)
      def mkRoundTotals() = RoundTotals(
        closedRound = round,
        closedRoundEffectiveAt = CantonTimestamp.MinValue,
        appRewards = BigDecimal(100),
        validatorRewards = BigDecimal(150),
        changeToInitialAmountAsOfRoundZero = randomValue,
        changeToHoldingFeesRate = randomValue,
        cumulativeAppRewards = BigDecimal(1100),
        cumulativeValidatorRewards = BigDecimal(1150),
        cumulativeChangeToInitialAmountAsOfRoundZero = randomValue,
        cumulativeChangeToHoldingFeesRate = randomValue,
        totalAmuletBalance = randomValue,
      )
      def encodeRoundTotals(rt: RoundTotals) = {
        HttpRoundTotals(
          closedRound = rt.closedRound,
          closedRoundEffectiveAt = Codec.OffsetDateTime.instance.encode(rt.closedRoundEffectiveAt),
          appRewards = Codec.encode(rt.appRewards),
          validatorRewards = Codec.encode(rt.validatorRewards),
          changeToInitialAmountAsOfRoundZero = Codec.encode(rt.changeToInitialAmountAsOfRoundZero),
          changeToHoldingFeesRate = Codec.encode(rt.changeToHoldingFeesRate),
          cumulativeAppRewards = Codec.encode(rt.cumulativeAppRewards),
          cumulativeValidatorRewards = Codec.encode(rt.cumulativeValidatorRewards),
          cumulativeChangeToInitialAmountAsOfRoundZero =
            Codec.encode(rt.cumulativeChangeToInitialAmountAsOfRoundZero),
          cumulativeChangeToHoldingFeesRate = Codec.encode(rt.cumulativeChangeToHoldingFeesRate),
          totalAmuletBalance = Codec.encode(rt.totalAmuletBalance),
        )
      }
      def encodeRoundPartyTotals(rpt: RoundPartyTotals) = {
        HttpRoundPartyTotals(
          closedRound = rpt.closedRound,
          party = rpt.party,
          appRewards = Codec.encode(rpt.appRewards),
          validatorRewards = Codec.encode(rpt.validatorRewards),
          trafficPurchased = rpt.trafficPurchased,
          trafficPurchasedCcSpent = Codec.encode(rpt.trafficPurchasedCcSpent),
          trafficNumPurchases = rpt.trafficNumPurchases,
          cumulativeAppRewards = Codec.encode(rpt.cumulativeAppRewards),
          cumulativeValidatorRewards = Codec.encode(rpt.cumulativeValidatorRewards),
          cumulativeChangeToInitialAmountAsOfRoundZero =
            Codec.encode(rpt.cumulativeChangeToInitialAmountAsOfRoundZero),
          cumulativeChangeToHoldingFeesRate = Codec.encode(rpt.cumulativeChangeToHoldingFeesRate),
          cumulativeTrafficPurchased = rpt.cumulativeTrafficPurchased,
          cumulativeTrafficPurchasedCcSpent = Codec.encode(rpt.cumulativeTrafficPurchasedCcSpent),
          cumulativeTrafficNumPurchases = rpt.cumulativeTrafficNumPurchases,
        )
      }
      def mkRoundPartyTotals() = RoundPartyTotals(
        closedRound = round,
        party = "party-id",
        appRewards = BigDecimal(10),
        validatorRewards = BigDecimal(20),
        trafficPurchased = 10L,
        trafficPurchasedCcSpent = BigDecimal(30),
        trafficNumPurchases = 30L,
        cumulativeAppRewards = BigDecimal(40),
        cumulativeValidatorRewards = BigDecimal(50),
        cumulativeChangeToInitialAmountAsOfRoundZero = randomValue,
        cumulativeChangeToHoldingFeesRate = randomValue,
        cumulativeTrafficPurchased = 50L,
        cumulativeTrafficPurchasedCcSpent = BigDecimal(70),
        cumulativeTrafficNumPurchases = 70L,
      )
      def mkRoundAggregateUsingDecoder() = RoundAggregate(
        ScanRoundAggregatesDecoder.decodeRoundTotal(encodeRoundTotals(mkRoundTotals())).value,
        Vector(
          ScanRoundAggregatesDecoder
            .decodeRoundPartyTotals(encodeRoundPartyTotals(mkRoundPartyTotals()))
            .value
        ),
      )
      def mkRoundAggregateWithoutDecoder() = RoundAggregate(
        mkRoundTotals(),
        Vector(mkRoundPartyTotals()),
      )
      val roundAggregateZeroBalanceValues = mkRoundAggregateWithoutDecoder().copy(
        roundTotals = mkRoundAggregateWithoutDecoder().roundTotals.copy(
          changeToInitialAmountAsOfRoundZero = zero,
          changeToHoldingFeesRate = zero,
          cumulativeChangeToInitialAmountAsOfRoundZero = zero,
          cumulativeChangeToHoldingFeesRate = zero,
          totalAmuletBalance = zero,
        ),
        roundPartyTotals = mkRoundAggregateWithoutDecoder().roundPartyTotals.map(
          _.copy(
            cumulativeChangeToInitialAmountAsOfRoundZero = zero,
            cumulativeChangeToHoldingFeesRate = zero,
          )
        ),
      )

      def getConnections(roundAggregateResponse: () => RoundAggregate) = {
        val connections = getMockedConnections(n = 10)
        connections.foreach { mock =>
          when(mock.getAggregatedRounds())
            .thenReturn(Future.successful(Some(RoundRange(round, round))))
          when(mock.getRoundAggregate(round))
            .thenReturn(Future.successful(Some(roundAggregateResponse())))
        }
        connections
      }

      val bft = getBft(getConnections(() => mkRoundAggregateUsingDecoder()))
      val con =
        new ScanAggregatesConnection(bft, retryProvider, retryProvider.loggerFactory)
      val result = con.getRoundAggregate(round).futureValue
      result shouldBe Some(roundAggregateZeroBalanceValues)

      // not using the decoder should fail on the randomized balance values.
      val bftFail = getBft(getConnections(() => mkRoundAggregateWithoutDecoder()))
      val conFail =
        new ScanAggregatesConnection(bftFail, retryProvider, retryProvider.loggerFactory)
      val resultFail = conFail.getRoundAggregate(round).failed.futureValue
      resultFail shouldBe an[BftScanConnection.ConsensusNotReached]
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
