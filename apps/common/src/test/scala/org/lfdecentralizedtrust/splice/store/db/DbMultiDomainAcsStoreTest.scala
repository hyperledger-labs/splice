package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.AppRewardCoupon
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.IMPORT_ACS_WORKFLOW_ID_PREFIX
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.StoreTest.testTxLogConfig
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
  TestTxLogEntry,
}
import org.lfdecentralizedtrust.splice.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import slick.jdbc.JdbcProfile

class DbMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      DbMultiDomainAcsStore[TestTxLogEntry]
    ]
    with SplicePostgresTest
    with HasActorSystem
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  "DbMultiDomainAcsStore" should {

    "allow creating & deleting same contract id in different stores" in {
      val store1 = mkStore(id = 1)
      val store1MigrationId1 = mkStore(id = 1, migrationId = 1L)
      val store2 = mkStore(id = 2)
      val coupon1 = c(1)
      val coupon2 = c(2)
      val coupon3 = c(3)
      for {
        _ <- acs()(store1)
        _ <- acs()(store1MigrationId1) // store1 with different migration id
        _ = store1.storeId shouldBe store1MigrationId1.storeId
        _ <- acs()(store2)
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
        id = 1,
        migrationId = 0,
        participantId = mkParticipantId("DbMultiDomainAcsStoreTest"),
        filter = MultiDomainAcsStore.SimpleContractFilter(
          dsoParty,
          templateFilters = Map(
            mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured)(BobbyTablesRowData(_))
          ),
        ),
        acsTableName = "scan_acs_store", // to have extra columns
      )
      val coupon = c(1)
      for {
        _ <- acs()(store)
        _ <- d1.create(coupon)(store)
        _ <- assertList(coupon -> Some(d1))(store)
      } yield succeed
    }

    "preserves daml numeric values in the ACS" in {
      implicit val store = mkStore()
      val values = specialNumericValues()
      val expected = values.map(appRewardCoupon(1, dsoParty, false, _))
      for {
        _ <- acs()
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
        _ <- acs()
        _ <- MonadUtil.sequentialTraverse(expected)(d1.create(_))
        actual <- store.listTxLogEntries()
      } yield {
        actual.map(_.numericValue.bigDecimal) should contain theSameElementsInOrderAs values
      }
    }

    "re-initialize at offset 0" in {
      val store = mkStore(id = 0)
      for {
        r <- store.testIngestionSink.initialize()
        _ = r shouldBe None
        _ <- store.testIngestionSink.ingestAcs(
          0L,
          Seq.empty,
          Seq.empty,
          Seq.empty,
        )
        r <- store.testIngestionSink.initialize()
        _ = r shouldBe Some(0L)
      } yield succeed
    }
  }

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

  override def mkStore(
      id: Int,
      migrationId: Long,
      participantId: ParticipantId,
      filter: MultiDomainAcsStore.ContractFilter[GenericAcsRowData],
  ) = {
    mkStoreWithAcsRowDataF(
      id,
      migrationId,
      participantId,
      filter,
      "acs_store_template",
    )
  }

  def mkStoreWithAcsRowDataF[R <: AcsRowData](
      id: Int,
      migrationId: Long,
      participantId: ParticipantId,
      filter: MultiDomainAcsStore.ContractFilter[R],
      acsTableName: String,
  ) = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(DarResources.amulet.all)
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      Some("txlog_store_template"),
      storeDescriptor(id, participantId),
      loggerFactory,
      filter,
      testTxLogConfig,
      DomainMigrationInfo(
        migrationId,
        None,
      ),
      participantId,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
  }

  case class BobbyTablesRowData(contract: Contract[?, ?]) extends AcsRowData {
    override def contractExpiresAt: Option[Timestamp] = None

    override def indexColumns: Seq[(String, IndexColumnValue[_])] = Seq(
      "ans_entry_name" -> lengthLimited("'); DROP TABLE bobby_tables; --")
    )
  }
}
