package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.AppRewardCoupon
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.StoreTest.testTxLogConfig
import org.lfdecentralizedtrust.splice.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.daml.lf.data.Time
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.IMPORT_ACS_WORKFLOW_ID_PREFIX
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.DestinationHistory
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement.NeedsBackfilling
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsJdbcTypes,
  AcsRowData,
  DbMultiDomainAcsStore,
  IndexColumnValue,
  SplicePostgresTest,
}
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.time.Instant
import scala.concurrent.Future

class TxLogBackfillingStoreTest
    extends StoreTest
    with SplicePostgresTest
    with HasActorSystem
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  // "Source" operations are usually performed by ScanHistoryBackfillingTrigger
  "TxLogBackfilling" should {

    "do nothing if source is not initialized" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)

        // At this point some ingestion service should call `history.ingestionSink.initialize()`
        // This test simulates the history ingestion being delayed.

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(store))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()

        // TxLogBackfillingTrigger performing backfilling
        result <- backfillOnce(store, history)
        _ = result shouldBe HistoryBackfilling.Outcome.MoreWorkAvailableLater
      } yield succeed
    }

    "do nothing if destination is not initialized" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- history.ingestionSink.initialize()

        // At this point some ingestion service should call `store.ingestionSink.initialize()`
        // This test simulates the store ingestion being delayed.

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history))

        // TxLogBackfillingTrigger performing backfilling
        result <- backfillOnce(store, history)
        _ = result shouldBe HistoryBackfilling.Outcome.MoreWorkAvailableLater
      } yield succeed
    }

    "do nothing if source backfilling is not initialized" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history =
        mkUpdateHistory(dsoParty, migrationId = migrationId, backfillingRequired = NeedsBackfilling)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)
        _ <- history.ingestionSink.initialize()

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history))

        // At this point ScanHistoryBackfillingTrigger should call `history.initializeBackfilling()`
        // This test simulates that trigger operation being delayed

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()

        // TxLogBackfillingTrigger performing backfilling
        result <- backfillOnce(store, history)
        _ = result shouldBe HistoryBackfilling.Outcome.MoreWorkAvailableLater
      } yield succeed
    }

    "complete backfilling if history is complete" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)
        _ <- history.ingestionSink.initialize()

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(store, history))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()
        _ <- assertEntries(store, Seq(1))

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store, history)
        _ <- assertEntries(store, Seq(1))
      } yield succeed
    }

    "backfill one entry" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)
        _ <- history.ingestionSink.initialize()

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history))
        _ <- sync1.createMulti(c(2), recordTime = t(2))(Seq(store, history))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()
        _ <- assertEntries(store, Seq(2))

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store, history)
        _ <- assertEntries(store, Seq(1, 2))
      } yield succeed
    }

    "backfill two entries from different synchronizers" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)
        _ <- history.ingestionSink.initialize()

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history))
        _ <- sync2.createMulti(c(2), recordTime = t(2))(Seq(history))
        _ <- sync2.createMulti(c(3), recordTime = t(3))(Seq(store, history))
        _ <- sync1.createMulti(c(4), recordTime = t(4))(Seq(store, history))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()
        _ <- assertEntries(store, Seq(4, 3)) // sorted by synchronizer, then record time

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store, history)

        _ <- assertEntries(store, Seq(1, 4, 2, 3)) // sorted by synchronizer, then record time
      } yield succeed
    }

    "backfill update producing many entries" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)
        _ <- history.ingestionSink.initialize()

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history))
        _ <- sync1.ingestMulti(offset =>
          mkCreateTx(
            offset = offset,
            createRequests = (2 to 10).map(i => c(i)),
            effectiveAt = t(2),
            createdEventSignatories = Seq(dsoParty),
            synchronizerId = sync1,
            workflowId = "",
            recordTime = t(2),
          )
        )(Seq(history))
        _ <- sync1.createMulti(c(11), recordTime = t(11))(Seq(store, history))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()
        _ <- assertEntries(store, Seq(11))

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store, history)
        _ <- assertEntries(store, Seq(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))
      } yield succeed
    }

    "backfill across migration ids" in {
      val migrationId1 = 1L
      val history1 = mkUpdateHistory(dsoParty, migrationId = migrationId1)
      val migrationId2 = 2L
      val store2 = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId2)
      val history2 = mkUpdateHistory(dsoParty, migrationId = migrationId2)
      for {
        // Migration 1 (only history)
        _ <- history1.ingestionSink.initialize()
        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history1))
        _ <- sync1.createMulti(c(2), recordTime = t(2))(Seq(history1))

        // Migration 2
        _ <- store2.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store2)
        _ <- history2.ingestionSink.initialize()

        _ <- sync1.createMulti(
          c(11),
          recordTime = importUpdateRecordTime.toInstant,
          workflowId = IMPORT_ACS_WORKFLOW_ID_PREFIX + "_1",
        )(
          Seq(history2)
        )
        _ <- sync1.createMulti(
          c(22),
          recordTime = importUpdateRecordTime.toInstant,
          workflowId = IMPORT_ACS_WORKFLOW_ID_PREFIX + "_2",
        )(
          Seq(history2)
        )
        _ <- sync2.createMulti(c(3), recordTime = t(3))(Seq(history2))
        _ <- sync1.createMulti(c(4), recordTime = t(4))(Seq(history2))
        _ <- sync1.createMulti(c(5), recordTime = t(5))(Seq(store2, history2))
        _ <- sync2.createMulti(c(6), recordTime = t(6))(Seq(store2, history2))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store2.initializeTxLogBackfilling()
        _ <- assertEntries(store2, Seq(5, 6))

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store2, history2)
        _ <- assertEntries(
          store2,
          Seq(1, 2, 4, 5, 3, 6), // sorted by synchronizer, then record time
        )
      } yield succeed
    }

    "handle data ingested before txlog backfilling was introduced " in {
      val migrationId1 = 1L
      val history1 = mkUpdateHistory(dsoParty, migrationId = migrationId1)
      val migrationId2 = 2L
      val store2 = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId2)
      val history2 = mkUpdateHistory(dsoParty, migrationId = migrationId2)
      for {
        // Migration 1 (only history)
        _ <- history1.ingestionSink.initialize()
        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history1))
        _ <- sync1.createMulti(c(2), recordTime = t(2))(Seq(history1))

        // Migration 2
        _ <- store2.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store2)
        _ <- history2.ingestionSink.initialize()

        _ <- sync1.createMulti(
          c(1),
          recordTime = importUpdateRecordTime.toInstant,
          workflowId = IMPORT_ACS_WORKFLOW_ID_PREFIX + "_1",
        )(
          Seq(history2)
        )
        _ <- sync1.createMulti(
          c(2),
          recordTime = importUpdateRecordTime.toInstant,
          workflowId = IMPORT_ACS_WORKFLOW_ID_PREFIX + "_2",
        )(
          Seq(history2)
        )
        _ <- sync1.createMulti(c(3), recordTime = t(3))(Seq(history2))
        _ <- sync1.createMulti(c(4), recordTime = t(4))(Seq(store2, history2))

        // Reset all tables introduced in V038__txlog_backfilling.sql, to simulate
        // all of the above happening before that migration was applied.
        _ <- storage
          .update(sqlu"truncate table txlog_backfilling_status", "truncate1")
          .failOnShutdown
        _ <- storage
          .update(sqlu"truncate table txlog_first_ingested_update", "truncate1")
          .failOnShutdown

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store2.initializeTxLogBackfilling()
        _ <- assertEntries(store2, Seq(4))

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store2, history2)
        _ <- assertEntries(
          store2,
          Seq(1, 2, 3, 4), // sorted by synchronizer, then record time
        )
      } yield succeed
    }

    "handle trigger initializing before first update" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)
        _ <- history.ingestionSink.initialize()

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()

        // Backfilling can't do anything until the first update is ingested
        result <- backfillOnce(store, history)
        _ = result shouldBe HistoryBackfilling.Outcome.MoreWorkAvailableLater
        _ <- assertEntries(store, Seq.empty)

        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history))
        _ <- sync1.createMulti(c(2), recordTime = t(2))(Seq(store, history))
        _ <- assertEntries(store, Seq(2))

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store, history)
        _ <- assertEntries(store, Seq(1, 2))
      } yield succeed
    }

    "report correct info when backfilling across import updates" in {
      val migrationId1 = 1L
      val history1 = mkUpdateHistory(dsoParty, migrationId = migrationId1)
      val migrationId2 = 2L
      val store2 = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId2)
      val history2 = mkUpdateHistory(dsoParty, migrationId = migrationId2)
      for {
        // Migration 1 (only history)
        _ <- history1.ingestionSink.initialize()
        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history1))
        _ <- sync1.createMulti(c(2), recordTime = t(2))(Seq(history1))

        // Migration 2
        _ <- store2.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store2)
        _ <- history2.ingestionSink.initialize()

        _ <- sync1.createMulti(
          c(1),
          recordTime = importUpdateRecordTime.toInstant,
          workflowId = IMPORT_ACS_WORKFLOW_ID_PREFIX + "_1",
        )(
          Seq(history2)
        )
        _ <- sync1.createMulti(
          c(2),
          recordTime = importUpdateRecordTime.toInstant,
          workflowId = IMPORT_ACS_WORKFLOW_ID_PREFIX + "_2",
        )(
          Seq(history2)
        )
        _ <- sync1.createMulti(c(3), recordTime = t(3))(Seq(history2))
        _ <- sync1.createMulti(c(4), recordTime = t(4))(Seq(store2, history2))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store2.initializeTxLogBackfilling()

        // backfilling info ignores the import updates
        info1 <- store2.destinationHistory.backfillingInfo
        _ = info1.value.migrationId shouldBe migrationId2
        _ = info1.value.backfilledAt shouldBe Map(
          sync1 -> CantonTimestamp.assertFromInstant(t(4))
        )

        // first iteration: processes one regular update and skips the import updates
        workDone1 <- backfillOnce(store2, history2)
        _ = workDone1 shouldBe HistoryBackfilling.Outcome.MoreWorkAvailableNow(
          DestinationHistory.InsertResult(1L, 1L, CantonTimestamp.assertFromInstant(t(3)))
        )
        // second iteration: continues with regular updates from migration 1
        workDone2 <- backfillOnce(store2, history2)
        _ = workDone2 shouldBe HistoryBackfilling.Outcome.MoreWorkAvailableNow(
          DestinationHistory.InsertResult(2L, 2L, CantonTimestamp.assertFromInstant(t(1)))
        )
      } yield succeed
    }

    "backfill if parser doesn't return any entries" in {
      val migrationId = 1L
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = migrationId)
      val history = mkUpdateHistory(dsoParty, migrationId = migrationId)
      for {
        _ <- store.testIngestionSink.initialize()
        _ <- acs(Seq.empty, Seq.empty, Seq.empty)(store)
        _ <- history.ingestionSink.initialize()

        // The two `createMulti` calls produce one entry each, the `exercise` calls produce no entries,
        // and we want backfilling to skip over those updates.
        _ <- sync1.createMulti(c(1), recordTime = t(1))(Seq(history))
        _ <- MonadUtil.sequentialTraverse(2 to 10) { i =>
          sync1.exercise(
            c(i),
            None,
            "someChoice",
            com.daml.ledger.javaapi.data.Unit.getInstance(),
            com.daml.ledger.javaapi.data.Unit.getInstance(),
            recordTime = t(i),
          )(history)
        }
        _ <- sync1.createMulti(c(11), recordTime = t(11))(Seq(history))
        _ <- sync1.createMulti(c(12), recordTime = t(12))(Seq(store, history))

        // TxLogBackfillingTrigger initializing backfilling
        _ <- store.initializeTxLogBackfilling()
        _ <- assertEntries(store, Seq(12))

        // TxLogBackfillingTrigger performing backfilling
        _ <- backfillAll(store, history)

        _ <- assertEntries(store, Seq(1, 11, 12))
      } yield succeed
    }
  }

  private def assertEntries(store: DbMultiDomainAcsStore[TestTxLogEntry], expected: Seq[Int]) = {
    for {
      actualEntries <- store.listTxLogEntries()
    } yield {
      val actualContractIds: Seq[String] = actualEntries.map(_.contractId)
      val expectedContractIds: Seq[String] = expected.map(i => c(i).contractId.contractId)
      actualContractIds should contain theSameElementsInOrderAs expectedContractIds
    }
  }

  private def backfillOnce(
      store: DbMultiDomainAcsStore[TestTxLogEntry],
      history: UpdateHistory,
  ): Future[HistoryBackfilling.Outcome] = TraceContext.withNewTraceContext("backfillOnce") {
    implicit traceContext =>
      val backfilling = new TxLogBackfilling(
        store = store,
        updateHistory = history,
        batchSize = 2,
        loggerFactory = loggerFactory,
      )
      backfilling.backfill()
  }

  private def backfillAll(
      store: DbMultiDomainAcsStore[TestTxLogEntry],
      history: UpdateHistory,
  ): Future[Unit] = TraceContext.withNewTraceContext("backfillAll") { implicit traceContext =>
    val backfilling = new TxLogBackfilling(
      store = store,
      updateHistory = history,
      batchSize = 2,
      loggerFactory = loggerFactory,
    )
    def go(i: Int): Future[Unit] = {
      logger.debug(s"backfill() iteration $i")
      i should be < 100
      backfilling.backfill().flatMap {
        case HistoryBackfilling.Outcome.MoreWorkAvailableNow(_) => go(i + 1)
        case HistoryBackfilling.Outcome.MoreWorkAvailableLater => go(i + 1)
        case HistoryBackfilling.Outcome.BackfillingIsComplete => Future.unit
      }
    }
    go(1)
  }

  // Helper code here is copied from MultiDomainAcsStoreTest and UpdateHistoryTest
  private type C = Contract[AppRewardCoupon.ContractId, AppRewardCoupon]
  private def c(i: Int): C =
    appRewardCoupon(i, dsoParty, contractId = validContractId(i))
  protected val importUpdateRecordTime: CantonTimestamp = CantonTimestamp.MinValue
  protected def t(i: Int): Instant = defaultEffectiveAt.plusMillis(i.toLong)

  private val sync1: SynchronizerId = SynchronizerId.tryFromString("synchronizer1::synchronizer")
  private val sync2: SynchronizerId = SynchronizerId.tryFromString("synchronizer2::synchronizer")

  private def storeDescriptor(id: Int, participantId: ParticipantId) =
    DbMultiDomainAcsStore.StoreDescriptor(
      version = 1,
      name = "DbMultiDomainAcsStoreTest",
      party = dsoParty,
      participant = participantId,
      key = Map(
        "id" -> id.toString
      ),
    )

  case class GenericAcsRowData(contract: Contract[_, _]) extends AcsRowData.AcsRowDataFromContract {
    override def contractExpiresAt: Option[Time.Timestamp] = None

    override def indexColumns: Seq[(String, IndexColumnValue[_])] = Seq.empty
  }
  object GenericAcsRowData {
    implicit val hasIndexColumns: HasIndexColumns[GenericAcsRowData] =
      new HasIndexColumns[GenericAcsRowData] {
        override def indexColumnNames: Seq[String] = Seq.empty
      }
  }

  protected val defaultContractFilter: MultiDomainAcsStore.ContractFilter[
    GenericAcsRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter

    MultiDomainAcsStore.SimpleContractFilter(
      dsoParty,
      templateFilters = Map(
        mkFilter(AppRewardCoupon.COMPANION)(_ => true) { contract =>
          GenericAcsRowData(contract)
        }
      ),
    )
  }

  val participantId = ParticipantId("TxLogBackfillingStoreTest")

  def mkStore(
      acsId: Int,
      txLogId: Option[Int],
      migrationId: Long,
  ): DbMultiDomainAcsStore[TestTxLogEntry] = {
    mkStoreWithAcsRowDataF(
      acsId,
      txLogId,
      migrationId,
      participantId,
      defaultContractFilter,
      "acs_store_template",
      txLogId.map(_ => "txlog_store_template"),
    )
  }
  def mkUpdateHistory(
      party: PartyId,
      migrationId: Long,
      backfillingRequired: BackfillingRequirement = BackfillingRequirement.BackfillingNotRequired,
  ): UpdateHistory = {
    new UpdateHistory(
      storage,
      DomainMigrationInfo(
        migrationId,
        None,
      ),
      "TxLogBackfillingStoreTest",
      participantId,
      party,
      backfillingRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = true,
    )
  }

  def mkStoreWithAcsRowDataF[R <: AcsRowData](
      acsId: Int,
      txLogId: Option[Int],
      migrationId: Long,
      participantId: ParticipantId,
      filter: MultiDomainAcsStore.ContractFilter[R, AcsInterfaceViewRowData.NoInterfacesIngested],
      acsTableName: String,
      txLogTableName: Option[String],
  ) = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(DarResources.amulet.all)
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      txLogTableName,
      None,
      storeDescriptor(acsId, participantId),
      txLogId.map(storeDescriptor(_, participantId)),
      loggerFactory,
      filter,
      testTxLogConfig,
      DomainMigrationInfo(
        migrationId,
        None,
      ),
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      IngestionConfig(),
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)

}
