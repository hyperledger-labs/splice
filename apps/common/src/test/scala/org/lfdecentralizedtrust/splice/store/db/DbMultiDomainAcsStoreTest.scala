package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.{DamlRecord, Identifier, OffsetCheckpoint}
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.AppRewardCoupon
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.IMPORT_ACS_WORKFLOW_ID_PREFIX
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  DarResources,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  TransactionTreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.migration.{DomainMigrationInfo, MigrationTimeInfo}
import org.lfdecentralizedtrust.splice.store.StoreTestBase.testTxLogConfig
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
  StoreTestBase,
  TestTxLogEntry,
}
import org.lfdecentralizedtrust.splice.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  holdingv1,
}

import java.util.Collections
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns
import org.slf4j.event.Level
import slick.jdbc.JdbcProfile

import java.time.Instant
import scala.concurrent.{Future, Promise}
import StoreTestBase.*
import cats.data.NonEmptyList
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

class DbMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      DbMultiDomainAcsStore[TestTxLogEntry]
    ]
    with SplicePostgresTest
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  "DbMultiDomainAcsStore" should {
    "allow creating & deleting same contract id in different stores" in {
      val store1 = mkStore(acsId = 1, txLogId = Some(1))
      val store1MigrationId1 = mkStore(acsId = 1, txLogId = Some(1), migrationId = 1L)
      val store2 = mkStore(acsId = 2, txLogId = Some(2))
      val coupon1 = c(1)
      val coupon2 = c(2)
      val coupon3 = c(3)
      for {
        _ <- initWithAcs()(store1)
        _ <- initWithAcs()(store1MigrationId1) // store1 with different migration id
        _ = store1.acsStoreId shouldBe store1MigrationId1.acsStoreId
        _ <- initWithAcs()(store2)
        _ <- d1.create(coupon1)(store1)
        _ <- d1.create(coupon2)(store1MigrationId1)
        txLogs <- store1MigrationId1.listTxLogEntries()
        _ = txLogs should have size 2 // tx log entries of coupon 1 and coupon 2
        _ <- d1.create(coupon3, workflowId = IMPORT_ACS_WORKFLOW_ID_PREFIX)(store1MigrationId1)
        txLogsAfterCreatingCoupon3 <- store1MigrationId1.listTxLogEntries()
        _ =
          // number of tx event remains unchanged as the acs import of the create event is skipped
          txLogsAfterCreatingCoupon3 should have size 2
        _ <- d1.create(coupon1)(store2)
        _ <- assertList(coupon1 -> Some(d1))(
          store1
        ) // only fetched 1 coupon with matching migration id
        _ <- assertList(coupon2 -> Some(d1), coupon3 -> Some(d1))(
          store1MigrationId1
        ) // fetched 2 coupons with matching migration id
        _ <- assertList(coupon1 -> Some(d1))(store2)
        _ <- d1.archive(coupon1)(store1)
        _ <- assertList()(store1) // deleted from store1
        _ <- assertList(coupon1 -> Some(d1))(store2) // but not from store2
        _ <- assertList(coupon2 -> Some(d1), coupon3 -> Some(d1))(
          store1MigrationId1
        ) // and not from store1 with migrationId=1
      } yield succeed
    }

    "not be SQL-injectable" in {
      import MultiDomainAcsStore.mkFilter
      val store = mkStoreWithAcsRowDataF(
        acsId = 1,
        txLogId = Some(1),
        migrationId = 0,
        participantId = mkParticipantId("DbMultiDomainAcsStoreTest"),
        filter =
          MultiDomainAcsStore.SimpleContractFilter[BobbyTablesRowData, GenericInterfaceRowData](
            dsoParty,
            templateFilters = Map(
              mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured)(BobbyTablesRowData(_))
            ),
            Map.empty,
          ),
        acsTableName = "scan_acs_store", // to have extra columns
        txLogTableName = Some("txlog_store_template"),
        interfaceViewsTableNameOpt = None,
      )
      val coupon = c(1)
      for {
        _ <- initWithAcs()(store)
        _ <- d1.create(coupon)(store)
        _ <- assertList(coupon -> Some(d1))(store)
      } yield succeed
    }

    "preserves daml numeric values in the ACS" in {
      implicit val store = mkStore()
      val values = specialNumericValues()
      val expected = values.map(appRewardCoupon(1, dsoParty, false, _))
      for {
        _ <- initWithAcs()
        _ <- MonadUtil.sequentialTraverse(expected)(d1.create(_))
        actual <- store.listContracts(
          AppRewardCoupon.COMPANION,
          limit = HardLimit.tryCreate(expected.length),
        )
      } yield {
        actual.map(_.contract.payload.amount) should contain theSameElementsInOrderAs values
      }
    }

    "preserves daml numeric values in the TxLog" in {
      implicit val store = mkStore()
      val values = specialNumericValues()
      val expected = values.map(appRewardCoupon(1, dsoParty, false, _))
      for {
        _ <- initWithAcs()
        _ <- MonadUtil.sequentialTraverse(expected)(d1.create(_))
        actual <- store.listTxLogEntries()
      } yield {
        actual.map(_.numericValue.bigDecimal) should contain theSameElementsInOrderAs values
      }
    }

    "store initialization" should {
      "refuse to ingest the ACS twice" in {
        val store = mkStore(acsId = 0, txLogId = Some(0))
        for {
          _ <- store.ingestionSink.initialize()
          _ <- acs(Seq(StoreTestBase.AcsImportEntry(c(1), d1, 0L)))(store)
          error <- acs(Seq(StoreTestBase.AcsImportEntry(c(1), d1, 0L)))(store).failed
          _ = error.getMessage should include("already ingested")
        } yield succeed
      }
      "does not report the ACS as ingested until all batches are ingested" in {
        val store = mkStore(txLogId = Some(0))
        val ingestedOneItem = Promise[Unit]()
        val continueIngesting = Promise[Unit]()
        for {
          _ <- store.ingestionSink.initialize()
          _ = store.hasFinishedAcsIngestion should be(false)
          ingestionCompletedFuture = store.ingestionSink.ingestAcsStreamInBatches(
            Source
              .single(StoreTestBase.AcsImportEntry(c(1), d1, 0L))
              .map { e =>
                ingestedOneItem.trySuccess(())
                e
              }
              .concat(Source.future(continueIngesting.future.map { _ =>
                StoreTestBase.AcsImportEntry(c(2), d1, 0L)
              }))
              .map(acsImportEntryToActiveContract)
              .map(c => Seq(BaseLedgerConnection.ActiveContractsItem.ActiveContract(c))),
            123L,
          )
          _ <- ingestedOneItem.future
          _ = store.hasFinishedAcsIngestion should be(false)
          _ = continueIngesting.success(())
          _ <- ingestionCompletedFuture // which will complete once element 2 is finally ingested
          _ <- assertList(c(1) -> Some(d1), c(2) -> Some(d1))(store)
        } yield succeed
      }
      "initialize empty stores with equal descriptors" in {
        val store = mkStore(acsId = 0, txLogId = Some(0))
        for {
          r <- store.ingestionSink.initialize()
          _ = r shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ = store.acsStoreId shouldBe store.txLogStoreId
          _ = store.hasFinishedAcsIngestion shouldBe false
        } yield succeed
      }
      "initialize empty stores with different descriptors" in {
        val store = mkStore(acsId = 0, txLogId = Some(1))
        for {
          r <- store.ingestionSink.initialize()
          _ = r shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ = store.acsStoreId should not be store.txLogStoreId
          _ = store.hasFinishedAcsIngestion shouldBe false
        } yield succeed
      }
      "initialize empty store without a txlog" in {
        val store = mkStore(acsId = 0, txLogId = None)
        for {
          r <- store.ingestionSink.initialize()
          _ = r shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ = assertThrows[RuntimeException](store.txLogStoreId)
          _ = store.hasFinishedAcsIngestion shouldBe false
        } yield succeed
      }
      "re-initialize from acs at ledger end if nothing was ingested" in {
        val store0 = mkStore(acsId = 0, txLogId = Some(0))
        val store1 = mkStore(acsId = 0, txLogId = Some(0))
        for {
          r0 <- store0.ingestionSink.initialize()
          _ = r0 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ = store0.hasFinishedAcsIngestion shouldBe false

          r1 <- store1.ingestionSink.initialize()
          _ = r1 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ = store1.hasFinishedAcsIngestion shouldBe false

          // Both descriptors should be preserved
          _ = store0.acsStoreId shouldBe store1.acsStoreId
          _ = store0.txLogStoreId shouldBe store1.txLogStoreId
        } yield succeed
      }
      "resume at initial acs offset 0" in {
        val store0 = mkStore(acsId = 0, txLogId = Some(0))
        val store1 = mkStore(acsId = 0, txLogId = Some(0))
        for {
          r0 <- store0.ingestionSink.initialize()
          _ = r0 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ <- acs(acsOffset = 0L)(store0)
          _ <- store0.hasFinishedAcsIngestion shouldBe true

          r1 <- store1.ingestionSink.initialize()
          _ = r1 shouldBe IngestionStart.ResumeAtOffset(0L)
          _ <- store1.hasFinishedAcsIngestion shouldBe true

          // Both descriptors should be preserved
          _ = store0.acsStoreId shouldBe store1.acsStoreId
          _ = store0.txLogStoreId shouldBe store1.txLogStoreId
        } yield succeed
      }
      "resume at latest offset" in {
        val store0 = mkStore(acsId = 0, txLogId = Some(0))
        val store1 = mkStore(acsId = 0, txLogId = Some(0))
        for {
          _ <- store0.ingestionSink.initialize()
          _ <- acs(Seq(StoreTestBase.AcsImportEntry(c(1), d1, 0L)))(store0)
          tx <- d1.create(c(2))(store0)

          r1 <- store1.ingestionSink.initialize()
          _ = r1 shouldBe IngestionStart.ResumeAtOffset(tx.getOffset)
          _ = store1.hasFinishedAcsIngestion shouldBe true

          // History should be preserved
          ts <- store1.listTxLogEntries()
          _ = ts should have size 1

          // Both descriptors should be preserved
          _ = store0.acsStoreId shouldBe store1.acsStoreId
          _ = store0.txLogStoreId shouldBe store1.txLogStoreId
        } yield succeed
      }
      "re-initialize from acs at ledger begin after a migration id change" in {
        val store0 = mkStore(acsId = 0, txLogId = Some(0), migrationId = 0)
        val store1 = mkStore(acsId = 0, txLogId = Some(0), migrationId = 1)
        for {
          r0 <- store0.ingestionSink.initialize()
          _ = r0 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ <- acs(Seq(StoreTestBase.AcsImportEntry(c(1), d1, 0L)))(store0)
          _ <- d1.create(c(2))(store0)

          r1 <- store1.ingestionSink.initialize()
          _ = r1 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ = store1.hasFinishedAcsIngestion shouldBe false
          _ <- acs()(store1)

          // History should be preserved
          ts <- store1.listTxLogEntries()
          _ = ts should have size 1

          // Both descriptors should be preserved
          _ = store0.acsStoreId shouldBe store1.acsStoreId
          _ = store0.txLogStoreId shouldBe store1.txLogStoreId
        } yield succeed
      }
      "re-initialize from acs at last ingested offset after the acs descriptor changes" in {
        val store0 = mkStore(acsId = 0, txLogId = Some(0))
        val store1 = mkStore(acsId = 1, txLogId = Some(0))
        for {
          r0 <- store0.ingestionSink.initialize()
          _ = r0 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ <- acs(Seq(StoreTestBase.AcsImportEntry(c(1), d1, 0L)))(store0)
          tx <- d1.create(c(2))(store0)

          r1 <- store1.ingestionSink.initialize()
          _ = r1 shouldBe IngestionStart.InitializeAcsAtOffset(tx.getOffset)
          _ = store1.hasFinishedAcsIngestion shouldBe false
          _ <- acs()(store1)

          // History should be preserved
          ts <- store1.listTxLogEntries()
          _ = ts should have size 1

          // ACS store descriptor should change
          _ = store0.acsStoreId should not be store1.acsStoreId
          _ = store0.txLogStoreId shouldBe store1.txLogStoreId
        } yield succeed
      }
      "resume from last ingested offset after the txlog descriptor changes" in {
        val store0 = mkStore(acsId = 0, txLogId = Some(0))
        val store1 = mkStore(acsId = 0, txLogId = Some(1))
        for {
          r0 <- store0.ingestionSink.initialize()
          _ = r0 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ <- acs(Seq(StoreTestBase.AcsImportEntry(c(1), d1, 0L)))(store0)
          tx <- d1.create(c(2))(store0)

          r1 <- store1.ingestionSink.initialize()
          _ = r1 shouldBe IngestionStart.ResumeAtOffset(tx.getOffset)
          _ = store1.hasFinishedAcsIngestion shouldBe true

          // History should be reset
          ts <- store1.listTxLogEntries()
          _ = ts shouldBe empty

          // TxLog store descriptor should change
          _ = store0.acsStoreId shouldBe store1.acsStoreId
          _ = store0.txLogStoreId should not be store1.txLogStoreId
        } yield succeed
      }
      "re-initialize from acs at ledger end after both descriptors changes" in {
        val store0 = mkStore(acsId = 0, txLogId = Some(0))
        val store1 = mkStore(acsId = 1, txLogId = Some(1))
        for {
          r0 <- store0.ingestionSink.initialize()
          _ = r0 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ <- acs(Seq(StoreTestBase.AcsImportEntry(c(1), d1, 0L)))(store0)
          _ <- d1.create(c(2))(store0)

          r1 <- store1.ingestionSink.initialize()
          _ = r1 shouldBe IngestionStart.InitializeAcsAtLatestOffset
          _ = store1.hasFinishedAcsIngestion shouldBe false
          _ <- acs()(store1)

          // New store should not see any data from the old store
          cs <- store1.listContracts(AppRewardCoupon.COMPANION, HardLimit.tryCreate(10))
          ts <- store1.listTxLogEntries()
          _ = cs shouldBe empty
          _ = ts shouldBe empty

          _ = store0.acsStoreId should not be store1.acsStoreId
          _ = store0.txLogStoreId should not be store1.txLogStoreId
        } yield succeed
      }

      "offset checkpoints can be ingested" in {
        val store = mkStore()
        for {
          _ <- initWithAcs(acsOffset = 0)(store)
          o1 <- store.lookupLastIngestedOffset()
          _ = o1 shouldBe Some(0)
          _ <- store.testIngestionSink.ingestUpdateBatch(
            NonEmptyList.of(
              TreeUpdateOrOffsetCheckpoint.Checkpoint(
                new OffsetCheckpoint(
                  5,
                  Collections.emptyList(),
                )
              )
            )
          )
          o2 <- store.lookupLastIngestedOffset()
          _ = o2 shouldBe Some(5)
        } yield succeed
      }
    }

    "log view failures" in {
      implicit val store = mkStore()
      val contractsToFailedViews = (1 to 3).map { n =>
        val contract = dummyHolding(providerParty(n), BigDecimal(n), dsoParty)
        val failedView = com.google.rpc.Status
          .newBuilder()
          .setCode(n * 100)
          .setMessage(s"test entry num $n")
          .build()
        contract -> failedView
      }

      for {
        _ <- initWithAcs()
        _ <- MonadUtil.sequentialTraverse(contractsToFailedViews) { case (contract, failedView) =>
          ingestExpectingFailedInterfacesLog(
            store,
            Seq(
              (
                contract,
                Map.empty[Identifier, DamlRecord],
                Map(
                  holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> failedView
                ),
              )
            ),
          )
        }
        // nothing should be returned since they can't be parsed
        expectedEmpty <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
      } yield expectedEmpty shouldBe empty
    }

    "ingest successful interface views and ignore failed ones" in {
      implicit val store = mkStore()
      val owner = providerParty(1)
      val contract = twoInterfaces(owner, BigDecimal(10.0), dsoParty, Instant.now())
      val successfulView = holdingView(owner, BigDecimal(10.0), dsoParty, "id")
      val failedView = com.google.rpc.Status
        .newBuilder()
        .setCode(500)
        .setMessage("Failed view")
        .build()

      for {
        _ <- initWithAcs()
        _ <- ingestExpectingFailedInterfacesLog(
          store,
          Seq(
            (
              contract,
              Map(
                holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> successfulView.toValue
              ),
              Map(
                allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID -> failedView
              ),
            )
          ),
        )
        result1 <- store.listInterfaceViews(holdingv1.Holding.INTERFACE)
        result2 <- store.listInterfaceViews(allocationrequestv1.AllocationRequest.INTERFACE)
      } yield {
        result1 should be(
          Seq(
            Contract(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
              new holdingv1.Holding.ContractId(contract.contractId.contractId),
              successfulView,
              contract.createdEventBlob,
              contract.createdAt,
            )
          )
        )
        result2 shouldBe empty
      }
    }

    "ingest several contracts in a single transaction with mixed successful / failed interface views" in {
      implicit val store = mkStore()
      val contract1 = twoInterfaces(providerParty(1), BigDecimal(10.0), dsoParty, Instant.now())
      val contract2 = twoInterfaces(providerParty(2), BigDecimal(20.0), dsoParty, Instant.now())
      val contract3 = twoInterfaces(providerParty(3), BigDecimal(30.0), dsoParty, Instant.now())
      val contract4 = twoInterfaces(providerParty(4), BigDecimal(40.0), dsoParty, Instant.now())

      for {
        _ <- initWithAcs()
        // 1 -> all good; 2 -> holding failed; 3 -> allocation request failed; 4 -> both failed
        _ <- ingestExpectingFailedInterfacesLog(
          store,
          Seq[
            (Contract[?, ?], Map[Identifier, DamlRecord], Map[Identifier, com.google.rpc.Status])
          ](
            (
              contract1,
              Map(
                holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView(
                  providerParty(1),
                  BigDecimal(10.0),
                  dsoParty,
                  "1",
                ).toValue,
                allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID -> allocationRequestView(
                  dsoParty,
                  Instant.now(),
                ).toValue,
              ),
              Map(
              ),
            ),
            (
              contract2,
              Map(
                allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID -> allocationRequestView(
                  dsoParty,
                  Instant.now(),
                ).toValue
              ),
              Map(
                holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> failedViewStatus(
                  "Failed holding view"
                )
              ),
            ),
            (
              contract3,
              Map(
                holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView(
                  providerParty(3),
                  BigDecimal(30.0),
                  dsoParty,
                  "3",
                ).toValue
              ),
              Map(
                allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID -> failedViewStatus(
                  "Failed allocation request view"
                )
              ),
            ),
            (
              contract4,
              Map(
              ),
              Map(
                holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> failedViewStatus(
                  "Failed holding view"
                ),
                allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID -> failedViewStatus(
                  "Failed allocation request view"
                ),
              ),
            ),
          ),
        )
        resultHolding <- store.listInterfaceViews(holdingv1.Holding.INTERFACE)
        resultAllocationRequest <- store.listInterfaceViews(
          allocationrequestv1.AllocationRequest.INTERFACE
        )
      } yield {
        resultHolding.map(_.contractId.contractId) should be(
          Seq(contract1.contractId.contractId, contract3.contractId.contractId)
        )
        resultAllocationRequest.map(_.contractId.contractId) should be(
          Seq(contract1.contractId.contractId, contract2.contractId.contractId)
        )
      }
    }

    "containsArchived should return true for archived contracts" in {
      val store = mkStore(acsId = 1, txLogId = Some(1), migrationId = 1L)
      val coupon1 = c(1)
      val coupon2 = c(2)
      val coupon3 = c(3)
      for {
        _ <- initWithAcs()(store)
        _ <- d1.create(coupon1)(store)
        _ <- d1.create(coupon2)(store)
        _ <- d1.create(coupon3)(store)
        _ <- d1.archive(coupon1)(store)
        _ <- d1.archive(coupon2)(store)
        _ = store.containsArchived(Seq(coupon1.contractId)).futureValue shouldBe true
        _ = store
          .containsArchived(Seq(coupon1.contractId, coupon2.contractId))
          .futureValue shouldBe true
        _ = store
          .containsArchived(Seq(coupon2.contractId, coupon3.contractId))
          .futureValue shouldBe true
        _ = store.containsArchived(Seq(coupon3.contractId)).futureValue shouldBe false
        _ = store.containsArchived(Seq()).futureValue shouldBe false
      } yield succeed
    }

    "tx rollbacks after migrations are handled correctly" in {
      import com.digitalasset.canton.data.CantonTimestamp
      val store1 = mkStore(acsId = 1, txLogId = Some(1), migrationId = 1L)
      val coupon1 = c(1)
      val coupon2 = c(2)
      val t0 = CantonTimestamp.Epoch
      val t1 = CantonTimestamp.Epoch.plusSeconds(60)
      val store2TimeTooEarly = mkStore(
        acsId = 1,
        txLogId = Some(1),
        migrationId = 2L,
        migrationTimeInfo = Some(MigrationTimeInfo(t0, synchronizerWasPaused = true)),
      )
      val store2CorrectTime = mkStore(
        acsId = 1,
        txLogId = Some(1),
        migrationId = 2L,
        migrationTimeInfo = Some(MigrationTimeInfo(t1, synchronizerWasPaused = true)),
      )
      for {
        _ <- initWithAcs()(store1)
        _ <- d1.create(coupon1, recordTime = t0.toInstant)(store1)
        _ <- d1.create(coupon2, recordTime = t1.toInstant)(store1)
        txLogs <- store1.listTxLogEntries()
        _ = txLogs should have size 2
        ex <- recoverToExceptionIf[IllegalStateException](initWithAcs()(store2TimeTooEarly))
        _ = ex.getMessage should include("Found 1 rows")
        _ <- initWithAcs()(store2CorrectTime)
        txLogs <- store2CorrectTime.listTxLogEntries()
        _ = txLogs should have size 2
      } yield succeed
    }

    "tx rollbacks after DR are handled correctly" in {
      import com.digitalasset.canton.data.CantonTimestamp
      val store1 = mkStore(acsId = 1, txLogId = Some(1), migrationId = 1L)
      val coupon1 = c(1)
      val coupon2 = c(2)
      val t0 = CantonTimestamp.Epoch
      val t1 = CantonTimestamp.Epoch.plusSeconds(60)
      val store2TimeTooEarlyDR = mkStore(
        acsId = 1,
        txLogId = Some(1),
        migrationId = 2L,
        migrationTimeInfo = Some(MigrationTimeInfo(t0, synchronizerWasPaused = false)),
      )
      for {
        _ <- initWithAcs()(store1)
        _ <- d1.create(coupon1, recordTime = t0.toInstant)(store1)
        _ <- d1.create(coupon2, recordTime = t1.toInstant)(store1)
        txLogs <- store1.listTxLogEntries()
        _ = txLogs should have size 2
        _ <- initWithAcs()(store2TimeTooEarlyDR)
        txLogs <- store2TimeTooEarlyDR.listTxLogEntries()
        _ = txLogs should have size 1
      } yield succeed
    }

    "can ingest large batches" in {
      implicit val store = mkStore()
      // 100 txs of 1000 CreatedEvents each
      val batchSize = 100
      val createdEventsPerBatch = 1000
      def bigBatch() = (1 to batchSize).map { i =>
        val offset = nextOffset() // mutable state
        TreeUpdateOrOffsetCheckpoint.Update(
          TransactionTreeUpdate(
            mkTx(
              offset = offset,
              events = (1 to createdEventsPerBatch).map(j =>
                toCreatedEvent(
                  amulet(providerParty(j), j, j.toLong, BigDecimal(0.001)),
                  Seq(providerParty(j), dsoParty),
                  Seq(providerParty(j), dsoParty),
                  Map(
                    holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView(
                      providerParty(j),
                      j,
                      dsoParty,
                      "AMT",
                    ).toValue
                  ),
                  Map.empty,
                )
              ),
              synchronizerId = d1,
              recordTime = Instant.EPOCH.plusSeconds(i.toLong),
            )
          ),
          d1,
        )
      }

      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- store.testIngestionSink.ingestUpdateBatch(
          NonEmptyList.fromListUnsafe(bigBatch().toList)
        )
        count <- storage
          .querySingle(
            sql"select count(*) from acs_store_template".as[Int].headOption,
            "bigBatchCount",
          )
          .value
          .failOnShutdown("test doesn't shutdown")
      } yield count should be(Some(batchSize * createdEventsPerBatch))
    }
  }

  private def failedViewStatus(msg: String) = {
    com.google.rpc.Status
      .newBuilder()
      .setCode(500)
      .setMessage(msg)
      .build()
  }

  private def ingestExpectingFailedInterfacesLog(
      store: DbMultiDomainAcsStore[?],
      contracts: Seq[
        (Contract[?, ?], Map[Identifier, DamlRecord], Map[Identifier, com.google.rpc.Status])
      ],
  ): Future[Unit] = {
    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      // using `d1.create` has an inner log assertion that breaks the one above
      store.testIngestionSink.underlying.ingestUpdate(
        d1,
        TransactionTreeUpdate(
          mkCreateTxWithInterfaces(
            nextOffset(),
            contracts,
            defaultEffectiveAt,
            Seq(dsoParty),
            d1,
            "",
          )
        ),
      ),
      entries => {
        forAll(entries) { entry =>
          entry.message should include(
            "Found failed interface views that match an interface id in a filter"
          ).or(include(RepeatedIngestionWarningMessage))
        }
      },
    )
  }

  private def storeDescriptor(id: Int, participantId: ParticipantId) =
    StoreDescriptor(
      version = 1,
      name = "DbMultiDomainAcsStoreTest",
      party = dsoParty,
      participant = participantId,
      key = Map(
        "id" -> id.toString
      ),
    )

  override def mkStore(
      acsId: Int,
      txLogId: Option[Int],
      migrationId: Long,
      participantId: ParticipantId,
      filter: MultiDomainAcsStore.ContractFilter[
        GenericAcsRowData,
        GenericInterfaceRowData,
      ],
      migrationTimeInfo: Option[MigrationTimeInfo],
  ) = {
    mkStoreWithAcsRowDataF(
      acsId,
      txLogId,
      migrationId,
      participantId,
      filter,
      "acs_store_template",
      txLogId.map(_ => "txlog_store_template"),
      Some("interface_views_template"),
      migrationTimeInfo,
    )
  }

  def mkStoreWithAcsRowDataF[R <: AcsRowData](
      acsId: Int,
      txLogId: Option[Int],
      migrationId: Long,
      participantId: ParticipantId,
      filter: MultiDomainAcsStore.ContractFilter[R, GenericInterfaceRowData],
      acsTableName: String,
      txLogTableName: Option[String],
      interfaceViewsTableNameOpt: Option[String],
      migrationTimeInfo: Option[MigrationTimeInfo] = None,
  ) = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++ DarResources.TokenStandard.allPackageResources.flatMap(_.all)
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      txLogTableName,
      interfaceViewsTableNameOpt,
      storeDescriptor(acsId, participantId),
      txLogId.map(storeDescriptor(_, participantId)),
      loggerFactory,
      filter,
      testTxLogConfig,
      DomainMigrationInfo(
        migrationId,
        migrationTimeInfo,
      ),
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      IngestionConfig(),
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
  }

  case class BobbyTablesRowData(contract: Contract[?, ?])
      extends AcsRowData.AcsRowDataFromContract {
    override def contractExpiresAt: Option[Timestamp] = None

    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "ans_entry_name" -> lengthLimited("'); DROP TABLE bobby_tables; --")
    )
  }
  object BobbyTablesRowData {
    implicit val hasIndexColumns: HasIndexColumns[BobbyTablesRowData] =
      new HasIndexColumns[BobbyTablesRowData] {
        override def indexColumnNames: Seq[String] = Seq("ans_entry_name")
      }
  }
}
