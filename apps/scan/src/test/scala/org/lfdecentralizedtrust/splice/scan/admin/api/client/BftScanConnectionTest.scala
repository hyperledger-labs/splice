// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import com.daml.ledger.api.v2.{CommandsOuterClass, TraceContextOuterClass}
import com.daml.ledger.javaapi.data as javaApi
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.metrics.api.testing.MetricValues
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
import org.apache.pekko.stream.StreamTcpException
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
  BulkObjectsAvailability,
  BulkStorageBucket,
  DamlValueEncoding,
  ErrorResponse,
  GetBulkObjectsProgressResponse,
  GetRewardAccountingActivityTotalsResponse,
  GetRewardAccountingBatchResponse,
  GetRewardAccountingRootHashResponse,
  RewardAccountingActivityTotalsCannotProvide,
  RewardAccountingActivityTotalsOk,
  RewardAccountingActivityTotalsUndetermined,
  RewardAccountingBatchOfBatches,
  RewardAccountingRootHashCannotProvide,
  RewardAccountingRootHashOk,
  RewardAccountingRootHashUndetermined,
}

import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.{
  DataAvailabilityResponse,
  NoScanWillHaveData,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.Bft
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.{
  BulkStorageObjects,
  DomainScans,
  DsoScan,
}
import org.lfdecentralizedtrust.splice.scan.config.{ScanAppClientConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{
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

// mock verification triggers this
@SuppressWarnings(Array("com.digitalasset.canton.DiscardedFuture"))
class BftScanConnectionTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem
    with MetricValues {

  val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)

  val clock = new SimClock(loggerFactory = loggerFactory)

  val synchronizerId = SynchronizerId.tryFromString("domain::id")

  private def scanUrl(n: Int) = s"https://$n.example.com"

  def getMockedConnections(n: Int): Seq[SingleScanConnection] = {
    val connections = (0 until n).map { n =>
      val m = mock[SingleScanConnection]
      when(m.config).thenReturn(
        ScanAppClientConfig(NetworkAppClientConfig(scanUrl(n)))
      )
      when(m.url).thenReturn(Uri(scanUrl(n)))
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
                  java.util.Optional.empty(),
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
  def makeMockReturnMigrationId(mock: SingleScanConnection, migrationId: Long): Unit = {
    when(mock.getMigrationId()).thenReturn(Future.successful(migrationId))
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
          ByteString.EMPTY,
          0L,
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
        _ => Future.unit,
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
  val notFoundFailure = new BaseAppConnection.UnexpectedHttpJsonResponse(
    StatusCodes.NotFound,
    io.circe.Json.obj("error" -> io.circe.Json.fromString("not found")),
  )
  val notFoundCommandFailure = HttpCommandException(
    HttpRequest(),
    StatusCodes.NotFound,
    HttpCommandException.ErrorResponseBody(ErrorResponse("Whatever thing was not found")),
  )
  val tcpFailure = new StreamTcpException("Connection reset by peer")

  val partyIdA = PartyId.tryFromProtoPrimitive("whatever::a")
  val partyIdB = PartyId.tryFromProtoPrimitive("whatever::b")

  private def rootHashOk(round: Long, hash: String): GetRewardAccountingRootHashResponse =
    GetRewardAccountingRootHashResponse(
      RewardAccountingRootHashOk(status = "Ok", roundNumber = round, rootHash = hash)
    )
  def makeMockReturnRootHashOk(mock: SingleScanConnection, round: Long, hash: String): Unit =
    when(mock.getRewardAccountingRootHash(round))
      .thenReturn(Future.successful(rootHashOk(round, hash)))
  def makeMockReturnRootHashUndetermined(mock: SingleScanConnection, round: Long): Unit =
    when(mock.getRewardAccountingRootHash(round)).thenReturn(
      Future.successful(
        GetRewardAccountingRootHashResponse(
          RewardAccountingRootHashUndetermined(status = "Undetermined")
        )
      )
    )
  def makeMockReturnRootHashCannotProvide(mock: SingleScanConnection, round: Long): Unit =
    when(mock.getRewardAccountingRootHash(round)).thenReturn(
      Future.successful(
        GetRewardAccountingRootHashResponse(
          RewardAccountingRootHashCannotProvide(status = "CannotProvide")
        )
      )
    )
  private def activityTotalsOk(
      round: Long,
      totalAppActivityWeight: Long,
      activePartiesCount: Long,
      activityRecordsCount: Long,
  ): GetRewardAccountingActivityTotalsResponse =
    GetRewardAccountingActivityTotalsResponse(
      RewardAccountingActivityTotalsOk(
        status = "Ok",
        roundNumber = round,
        totalAppActivityWeight = totalAppActivityWeight,
        activePartiesCount = activePartiesCount,
        activityRecordsCount = activityRecordsCount,
        totalAppRewardMintingAllowance = "0",
        totalAppRewardThresholded = "0",
        totalAppRewardUnclaimed = "0",
        rewardedAppProviderPartiesCount = 0L,
      )
    )
  def makeMockReturnActivityTotalsOk(
      mock: SingleScanConnection,
      round: Long,
      totalAppActivityWeight: Long,
      activePartiesCount: Long,
      activityRecordsCount: Long,
  ): Unit =
    when(mock.getRewardAccountingActivityTotals(round))
      .thenReturn(
        Future.successful(
          activityTotalsOk(round, totalAppActivityWeight, activePartiesCount, activityRecordsCount)
        )
      )
  def makeMockReturnActivityTotalsUndetermined(mock: SingleScanConnection, round: Long): Unit =
    when(mock.getRewardAccountingActivityTotals(round)).thenReturn(
      Future.successful(
        GetRewardAccountingActivityTotalsResponse(
          RewardAccountingActivityTotalsUndetermined(status = "Undetermined")
        )
      )
    )
  def makeMockReturnActivityTotalsCannotProvide(mock: SingleScanConnection, round: Long): Unit =
    when(mock.getRewardAccountingActivityTotals(round)).thenReturn(
      Future.successful(
        GetRewardAccountingActivityTotalsResponse(
          RewardAccountingActivityTotalsCannotProvide(status = "CannotProvide")
        )
      )
    )
  private val rewardAccountingBatchResponse: GetRewardAccountingBatchResponse =
    GetRewardAccountingBatchResponse(
      RewardAccountingBatchOfBatches(batchType = "BatchOfBatches", childHashes = Vector("aa", "bb"))
    )
  def makeMockReturnBatch(
      mock: SingleScanConnection,
      round: Long,
      hash: String,
      resp: Option[GetRewardAccountingBatchResponse],
  ): Unit =
    when(mock.getRewardAccountingBatch(round, hash)).thenReturn(Future.successful(resp))
  def makeMockFailBatch(
      mock: SingleScanConnection,
      round: Long,
      hash: String,
      failure: Throwable,
  ): Unit =
    when(mock.getRewardAccountingBatch(round, hash)).thenReturn(Future.failed(failure))

  "BftScanConnection" should {

    "map bulk progress answers to data availability" in {
      def availability(answer: BulkObjectsAvailability) =
        BftScanConnection.dataAvailability(GetBulkObjectsProgressResponse(answer))

      availability(BulkObjectsAvailability.Available) shouldBe DataAvailabilityResponse.Available
      availability(BulkObjectsAvailability.Processing) shouldBe DataAvailabilityResponse.NotYet
      availability(BulkObjectsAvailability.Backfilling) shouldBe DataAvailabilityResponse.Never
    }

    "return the agreed response when all agree" in {
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturn(_, partyIdA))
      val bft = getBft(connections)

      for {
        dsoPartyId <- bft.getDsoPartyId()
      } yield dsoPartyId should be(partyIdA)
    }

    "return the agreed migration id when 2f+1 agree" in {
      val connections = getMockedConnections(n = 4)
      val disagreeing = connections.head
      makeMockReturnMigrationId(disagreeing, 5L)
      val agreeing = connections.drop(1)
      agreeing.foreach(makeMockReturnMigrationId(_, 3L))
      val bft = getBft(connections)

      for {
        migrationId <- bft.getMigrationId()
      } yield migrationId should be(3L)
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
      // sanity check
      bft.scanList.scanConnections.open.map(_.config.adminApi.url).toSet should be(
        connections.take(1).map(_.config.adminApi.url).toSet
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
        bft.scanList.scanConnections.open.map(_.config.adminApi.url).toSet should be(
          connections.map(_.config.adminApi.url).toSet
        )
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

  "BftScanConnection for bulk storage listing" should {
    val availableAt = ctime(2)
    val page = BulkStorageObjects.UpdateObjectsPage(
      Seq(ObjectKeyAndChecksum("s/updates_compact_json_0.zstd", "checksum-0")),
      None,
    )

    def makeMockReturnCommittedProgress(
        mock: SingleScanConnection,
        availability: BulkObjectsAvailability,
    ): Unit =
      when(
        mock.getBulkObjectsProgress(eqTo(availableAt), eqTo(BulkStorageBucket.Committed))(
          any[ExecutionContext],
          any[TraceContext],
        )
      ).thenReturn(Future.successful(GetBulkObjectsProgressResponse(availability)))

    def makeMockReturnUpdateObjects(mock: SingleScanConnection): Unit =
      when(
        mock.listBulkUpdateHistoryObjects(
          any[CantonTimestamp],
          any[CantonTimestamp],
          any[Int],
          any[Option[String]],
          any[Option[DamlValueEncoding]],
        )(any[ExecutionContext], any[TraceContext])
      ).thenReturn(Future.successful(page))

    def listUpdates(bft: BftScanConnection) =
      bft.listBulkUpdateHistoryObjectsWithPeers(
        ctime(1),
        availableAt,
        10,
        availableAt,
        ScanStorageConfig.Encoding.CompactJson,
      )

    "list from the scans that committed the objects up to the given time, and return them" in {
      val connections = getMockedConnections(n = 4)
      connections.foreach { connection =>
        makeMockReturnCommittedProgress(connection, BulkObjectsAvailability.Available)
        makeMockReturnUpdateObjects(connection)
      }

      listUpdates(getBft(connections)).map { case (listed, peers) =>
        listed shouldBe page
        peers should not be empty
        connections.map(_.url) should contain allElementsOf peers
      }
    }

    "list from the founder alone when every other scan is backfilling" in {
      val connections = getMockedConnections(n = 4)
      makeMockReturnCommittedProgress(connections(0), BulkObjectsAvailability.Available)
      makeMockReturnUpdateObjects(connections(0))
      connections
        .drop(1)
        .foreach(
          makeMockReturnCommittedProgress(_, BulkObjectsAvailability.Backfilling)
        )

      listUpdates(getBft(connections)).map { case (listed, peers) =>
        listed shouldBe page
        peers shouldBe List(connections(0).url)
      }
    }

    "report that no scan will have the objects when every scan is backfilling" in {
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturnCommittedProgress(_, BulkObjectsAvailability.Backfilling))

      listUpdates(getBft(connections)).failed.map(_ shouldBe a[NoScanWillHaveData])
    }
  }

  "BftScanConnection.getRewardAccountingRootHash" should {

    "propagates BadGateway when no quorum agrees on a hash" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      connections.zipWithIndex.foreach { case (c, i) =>
        makeMockReturnRootHashOk(c, round, s"hash$i")
      }
      val bft = getBft(connections)

      // n=4, f=1, targetSuccess=2. All 4 WithData peers are sampled from cache;
      // each returns a distinct hash → no consensus → BadGateway.
      loggerFactory.assertLogs(
        for {
          failure <- bft.getRewardAccountingRootHash(round).failed
        } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
          code should be(StatusCodes.BadGateway)
          message should include("Failed to reach consensus from 4 Scan nodes")
        },
        _.warningMessage should include("Consensus not reached."),
      )
    }

    "propagates BadGateway when every peer returns CannotProvide" in {
      // All 4 peers opt out via CannotProvide → connectionsForConsensus is empty
      // → enoughAvailableScans = false → BadGateway. The "not enough scans"
      // message is logged at INFO (not WARN) for reward-accounting endpoints
      // where this is expected during bootstrap.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturnRootHashCannotProvide(_, round))
      val bft = getBft(connections)

      for {
        failure <- bft.getRewardAccountingRootHash(round).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }

    "propagates BadGateway when every peer returns Undetermined" in {
      // All 4 Undetermined → probe classifies each as Unavailable → all kept
      // in `n` (n=4, f=1, targetSuccess=2) but none contributes a cached
      // response → 0 WithData scans to sample → enoughAvailableScans=false
      // → BadGateway. "Not enough scans" is logged at INFO for these endpoints.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturnRootHashUndetermined(_, round))
      val bft = getBft(connections)

      for {
        failure <- bft.getRewardAccountingRootHash(round).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }

    "propagates BadGateway when there are no peer scans" in {
      // Empty scan list → no probes → connectionsForConsensus empty → BadGateway.
      // "Not enough scans" is logged at INFO for these endpoints.
      val bft = getBft(Seq.empty)

      for {
        failure <- bft.getRewardAccountingRootHash(1L).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }

    "logs disagreements at WARN level" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      makeMockReturnRootHashOk(connections(1), round, "aabb")
      makeMockReturnRootHashOk(connections(2), round, "ccdd")
      makeMockReturnRootHashOk(connections(3), round, "ccdd")
      val bft = getBft(connections)

      loggerFactory
        .assertEventuallyLogsSeq(SuppressionRule.Level(Level.WARN))(
          bft.getRewardAccountingRootHash(round),
          logs =>
            logs.exists(log =>
              log.level == Level.WARN && log.message.contains(
                "disagreed with consensus"
              )
            ) should be(true),
        )
        .map(_ => succeed)
    }

    "returns the sole Ok when every other peer explicitly said it has no hash" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      makeMockReturnRootHashCannotProvide(connections(1), round)
      makeMockReturnRootHashCannotProvide(connections(2), round)
      makeMockReturnRootHashCannotProvide(connections(3), round)
      val bft = getBft(connections)

      for {
        resp <- bft.getRewardAccountingRootHashWithScanUris(round)
      } yield inside(resp) {
        case (
              GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(ok),
              uris,
            ) =>
          ok.roundNumber should be(round)
          ok.rootHash should be("aabb")
          uris should have size 1
      }
    }

    "reaches consensus when two peers agree and the rest explicitly opt out" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      makeMockReturnRootHashOk(connections(1), round, "aabb")
      makeMockReturnRootHashCannotProvide(connections(2), round)
      makeMockReturnRootHashUndetermined(connections(3), round)
      val bft = getBft(connections)

      for {
        resp <- bft.getRewardAccountingRootHashWithScanUris(round)
      } yield inside(resp) {
        case (
              GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(ok),
              _,
            ) =>
          ok.rootHash should be("aabb")
      }
    }

    "reaches consensus despite a peer whose probe fails" in {
      // SV0 returns Ok (cached in phase 1). SV1's probe fails with a
      // transport error → classified as Unavailable (counted in `n` but
      // NOT re-queried in phase 2). SV2/SV3 opt out via CannotProvide →
      // dropped from `n`. n = 1 + 1 = 2, f = 0, targetSuccess = 1: SV0's
      // cached Ok is the only response required, no consensus WARN.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      when(connections(1).getRewardAccountingRootHash(round))
        .thenReturn(Future.failed(tcpFailure))
      makeMockReturnRootHashCannotProvide(connections(2), round)
      makeMockReturnRootHashCannotProvide(connections(3), round)
      val bft = getBft(connections)

      bft.getRewardAccountingRootHash(round).map { resp =>
        inside(resp) {
          case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(ok) =>
            ok.rootHash should be("aabb")
        }
      }
    }

    "propagates BadGateway when a lone Ok cannot meet BFT quorum against Undetermined peers" in {
      // 1 Ok + 3 Undetermined → probe classifies as {WithData, Unavailable*3}.
      // Unavailable peers count in `n`: n=4, f=1, targetSuccess=2. Only SV0
      // (WithData) has a cached response → connections=[SV0] with
      // requestsToDo=1. `enoughAvailableScans` sees 1 < 2 → BadGateway.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      makeMockReturnRootHashUndetermined(connections(1), round)
      makeMockReturnRootHashUndetermined(connections(2), round)
      makeMockReturnRootHashUndetermined(connections(3), round)
      val bft = getBft(connections)

      for {
        failure <- bft.getRewardAccountingRootHash(round).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }

    "logs a WARN when Oks from the probed subset disagree on the payload" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      makeMockReturnRootHashOk(connections(1), round, "ccdd")
      makeMockReturnRootHashCannotProvide(connections(2), round)
      makeMockReturnRootHashCannotProvide(connections(3), round)
      val bft = getBft(connections)

      loggerFactory
        .assertEventuallyLogsSeq(SuppressionRule.Level(Level.WARN))(
          bft.getRewardAccountingRootHash(round),
          logs =>
            logs.exists(log =>
              log.level == Level.WARN && log.message.contains(
                "disagreed with consensus"
              )
            ) should be(true),
        )
        .map(_ => succeed)
    }

    "refuses a lone Ok when unreachable peers push BFT quorum above 1" in {
      // Mirrors the integration test's dummy-SV scenario: some peers'
      // connections fail to open (scanConnections.failed > 0), so they
      // never get probed but still count in `n`. The lone Ok cannot
      // meet the raised targetSuccess and BadGateway propagates.
      // "Not enough scans" is logged at INFO for these endpoints.
      val round = 42L
      val connections = getMockedConnections(n = 2)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      makeMockReturnRootHashCannotProvide(connections(1), round)
      val failedConnections = Map(
        Uri("https://failed-a.example.com") -> new RuntimeException("Failed"),
        Uri("https://failed-b.example.com") -> new RuntimeException("Failed"),
        Uri("https://failed-c.example.com") -> new RuntimeException("Failed"),
      )
      val bft = getBft(connections, initialFailedConnections = failedConnections)

      for { failure <- bft.getRewardAccountingRootHash(round).failed } yield inside(failure) {
        case HttpErrorWithHttpCode(code, _) =>
          code should be(StatusCodes.BadGateway)
      }
    }

    "reaches consensus on a single Ok when other peers give a mix of CannotProvide, Undetermined, and probe failure" in {
      // Exercises all three probe classifications in one call:
      //   Ok → WithData, CannotProvide → WithoutData (dropped from n),
      //   Undetermined → Unavailable, and a probe future failure →
      //   Unavailable. Single Ok wins because n = 3 (1 withData + 2
      //   unavailable) keeps f = 0.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnRootHashOk(connections(0), round, "aabb")
      makeMockReturnRootHashCannotProvide(connections(1), round)
      makeMockReturnRootHashUndetermined(connections(2), round)
      when(connections(3).getRewardAccountingRootHash(round))
        .thenReturn(Future.failed(tcpFailure))
      val bft = getBft(connections)

      bft.getRewardAccountingRootHash(round).map { resp =>
        inside(resp) {
          case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(ok) =>
            ok.rootHash should be("aabb")
        }
      }
    }

  }

  "BftScanConnection.getRewardAccountingActivityTotals" should {

    "propagates BadGateway when no quorum agrees on the totals" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      connections.zipWithIndex.foreach { case (c, i) =>
        makeMockReturnActivityTotalsOk(c, round, 100L + i, 10L + i, 5L + i)
      }
      val bft = getBft(connections)

      // n=4, f=1, targetSuccess=2. All 4 WithData peers are sampled from cache;
      // each returns distinct totals → no consensus → BadGateway.
      loggerFactory.assertLogs(
        for {
          failure <- bft.getRewardAccountingActivityTotals(round).failed
        } yield inside(failure) { case HttpErrorWithHttpCode(code, message) =>
          code should be(StatusCodes.BadGateway)
          message should include("Failed to reach consensus from 4 Scan nodes")
        },
        _.warningMessage should include("Consensus not reached."),
      )
    }

    "propagates BadGateway when every peer returns CannotProvide" in {
      // All 4 peers opt out via CannotProvide → connectionsForConsensus is empty
      // → enoughAvailableScans = false → BadGateway. "Not enough scans" is
      // logged at INFO for these endpoints.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturnActivityTotalsCannotProvide(_, round))
      val bft = getBft(connections)

      for {
        failure <- bft.getRewardAccountingActivityTotals(round).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }

    "propagates BadGateway when every peer returns Undetermined" in {
      // All 4 Undetermined → probe classifies each as Unavailable → all kept
      // in `n` (n=4, f=1, targetSuccess=2) but none contributes a cached
      // response → 0 WithData scans to sample → enoughAvailableScans=false
      // → BadGateway. "Not enough scans" is logged at INFO for these endpoints.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      connections.foreach(makeMockReturnActivityTotalsUndetermined(_, round))
      val bft = getBft(connections)

      for {
        failure <- bft.getRewardAccountingActivityTotals(round).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }

    "propagates BadGateway when there are no peer scans" in {
      // Empty scan list → no probes → connectionsForConsensus empty → BadGateway.
      // "Not enough scans" is logged at INFO for these endpoints.
      val bft = getBft(Seq.empty)

      for {
        failure <- bft.getRewardAccountingActivityTotals(1L).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }

    "logs disagreements at WARN level" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnActivityTotalsOk(connections(0), round, 100L, 10L, 5L)
      makeMockReturnActivityTotalsOk(connections(1), round, 100L, 10L, 5L)
      makeMockReturnActivityTotalsOk(connections(2), round, 200L, 20L, 9L)
      makeMockReturnActivityTotalsOk(connections(3), round, 200L, 20L, 9L)
      val bft = getBft(connections)

      loggerFactory
        .assertEventuallyLogsSeq(SuppressionRule.Level(Level.WARN))(
          bft.getRewardAccountingActivityTotals(round),
          logs =>
            logs.exists(log =>
              log.level == Level.WARN && log.message.contains(
                "disagreed with consensus"
              )
            ) should be(true),
        )
        .map(_ => succeed)
    }

    "returns the sole Ok when every other peer explicitly said it has no data" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnActivityTotalsOk(connections(0), round, 100L, 10L, 5L)
      makeMockReturnActivityTotalsCannotProvide(connections(1), round)
      makeMockReturnActivityTotalsCannotProvide(connections(2), round)
      makeMockReturnActivityTotalsCannotProvide(connections(3), round)
      val bft = getBft(connections)

      for {
        resp <- bft.getRewardAccountingActivityTotalsWithScanUris(round)
      } yield inside(resp) {
        case (
              GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(ok),
              uris,
            ) =>
          ok.roundNumber should be(round)
          ok.totalAppActivityWeight should be(100L)
          uris should have size 1
      }
    }

    "reaches consensus when two peers agree and the rest explicitly opt out" in {
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnActivityTotalsOk(connections(0), round, 100L, 10L, 5L)
      makeMockReturnActivityTotalsOk(connections(1), round, 100L, 10L, 5L)
      makeMockReturnActivityTotalsCannotProvide(connections(2), round)
      makeMockReturnActivityTotalsUndetermined(connections(3), round)
      val bft = getBft(connections)

      for {
        resp <- bft.getRewardAccountingActivityTotalsWithScanUris(round)
      } yield inside(resp) {
        case (
              GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(ok),
              _,
            ) =>
          ok.totalAppActivityWeight should be(100L)
      }
    }

    "reaches consensus despite a peer whose probe fails" in {
      // SV0 returns Ok (cached in phase 1). SV1's probe fails with a
      // transport error → classified as Unavailable (counted in `n` but
      // NOT re-queried in phase 2). SV2/SV3 opt out via CannotProvide →
      // dropped from `n`. n = 1 + 1 = 2, f = 0, targetSuccess = 1: SV0's
      // cached Ok is the only response required, no consensus WARN.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnActivityTotalsOk(connections(0), round, 100L, 10L, 5L)
      when(connections(1).getRewardAccountingActivityTotals(round))
        .thenReturn(Future.failed(tcpFailure))
      makeMockReturnActivityTotalsCannotProvide(connections(2), round)
      makeMockReturnActivityTotalsCannotProvide(connections(3), round)
      val bft = getBft(connections)

      bft.getRewardAccountingActivityTotals(round).map { resp =>
        inside(resp) {
          case GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(ok) =>
            ok.totalAppActivityWeight should be(100L)
        }
      }
    }

    "propagates BadGateway when a lone Ok cannot meet BFT quorum against Undetermined peers" in {
      // 1 Ok + 3 Undetermined → probe classifies as {WithData, Unavailable*3}.
      // Unavailable peers count in `n`: n=4, f=1, targetSuccess=2. Only SV0
      // (WithData) has a cached response → connections=[SV0] with
      // requestsToDo=1. `enoughAvailableScans` sees 1 < 2 → BadGateway.
      val round = 42L
      val connections = getMockedConnections(n = 4)
      makeMockReturnActivityTotalsOk(connections(0), round, 100L, 10L, 5L)
      makeMockReturnActivityTotalsUndetermined(connections(1), round)
      makeMockReturnActivityTotalsUndetermined(connections(2), round)
      makeMockReturnActivityTotalsUndetermined(connections(3), round)
      val bft = getBft(connections)

      for {
        failure <- bft.getRewardAccountingActivityTotals(round).failed
      } yield inside(failure) { case HttpErrorWithHttpCode(code, _) =>
        code should be(StatusCodes.BadGateway)
      }
    }
  }

  "BftScanConnection.getRewardAccountingBatch" should {

    "returns None when no scan has the batch" in {
      val round = 7L
      val hash = "abcdabcd"
      val connections = getMockedConnections(n = 3)
      connections.foreach(makeMockReturnBatch(_, round, hash, None))
      val bft = getBft(connections)

      for {
        resp <- bft.getRewardAccountingBatch(round, hash)
      } yield resp should be(None)
    }

    "on multiple retries, we can obtain batch even in prescense of failing scans" in {
      val round = 7L
      val hash = "abcdabcd"
      val connections = getMockedConnections(n = 3)
      makeMockFailBatch(connections(0), round, hash, tcpFailure)
      makeMockReturnBatch(connections(1), round, hash, Some(rewardAccountingBatchResponse))
      makeMockReturnBatch(connections(2), round, hash, None)
      val bft = getBft(connections)

      def attempt(remaining: Int): Future[Option[GetRewardAccountingBatchResponse]] =
        bft.getRewardAccountingBatch(round, hash).flatMap {
          case Some(resp) => Future.successful(Some(resp))
          case None if remaining > 1 => attempt(remaining - 1)
          case None => Future.successful(None)
        }

      for {
        // Each call queries a single random scan, and it does not retry internally.
        // So if the caller attempts 100 times, we should hit a batch with very high probability.
        resp <- attempt(100)
      } yield resp should be(Some(rewardAccountingBatchResponse))
    }
  }

}
