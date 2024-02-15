package com.daml.network.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.environment.{DarResources, RetryProvider}
import com.daml.network.store.StoreTest.{TestTxLogEntry, testTxLogConfig}
import com.daml.network.store.{MultiDomainAcsStore, MultiDomainAcsStoreTest}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import slick.jdbc.JdbcProfile

import scala.concurrent.Future

class DbMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      DbMultiDomainAcsStore[TestTxLogEntry]
    ]
    with CNPostgresTest
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
      for {
        _ <- acs()(store1)
        _ <- acs()(store1MigrationId1) // store1 with different migration id
        _ = store1.storeId shouldBe store1MigrationId1.storeId
        _ <- acs()(store2)
        _ <- d1.create(coupon1)(store1)
        _ <- d1.create(coupon2)(store1MigrationId1)
        _ <- d1.create(coupon1)(store2)
        _ <- assertList(coupon1 -> Some(d1))(
          store1
        ) // only fetched 1 coupon with matching migration id
        _ <- assertList(coupon2 -> Some(d1))(
          store1MigrationId1
        ) // only fetched 1 coupon with matching migration id
        _ <- assertList(coupon1 -> Some(d1))(store2)
        _ <- d1.archive(coupon1)(store1)
        _ <- assertList()(store1) // deleted from store1
        _ <- assertList(coupon1 -> Some(d1))(store2) // but not from store2
        _ <- assertList(coupon2 -> Some(d1))(
          store1MigrationId1
        ) // and not from store1 with migrationId=1
      } yield succeed
    }

    "not be SQL-injectable" in {
      import MultiDomainAcsStore.mkFilter
      val store = mkStoreWithAcsRowDataF(
        1,
        0,
        MultiDomainAcsStore.SimpleContractFilter(
          svcParty,
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
  }

  private def storeDescriptor(id: Int) =
    io.circe.parser
      .parse(raw"""{"test": "DbMultiDomainAcsStoreTest", "id": $id}""")
      .getOrElse(sys.error("Why is it so hard to define a JSON literal"))

  override def mkStore(
      id: Int,
      migrationId: Long,
      filter: MultiDomainAcsStore.ContractFilter[GenericAcsRowData],
  ) = {
    mkStoreWithAcsRowDataF(
      id,
      migrationId,
      filter,
      "acs_store_template",
    )
  }

  def mkStoreWithAcsRowDataF[R <: AcsRowData](
      id: Int,
      migrationId: Long,
      filter: MultiDomainAcsStore.ContractFilter[R],
      acsTableName: String,
  ) = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(DarResources.cantonCoin.all)
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      "txlog_store_template",
      storeDescriptor(id),
      loggerFactory,
      filter,
      testTxLogConfig,
      migrationId,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
  }

  override protected def cleanDb(storage: DbStorage): Future[?] = {
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
  }

  case class BobbyTablesRowData(contract: Contract[?, ?]) extends AcsRowData {
    override def contractExpiresAt: Option[Timestamp] = None

    override def indexColumns: Seq[(String, IndexColumnValue[_])] = Seq(
      "cns_entry_name" -> lengthLimited("'); DROP TABLE bobby_tables; --")
    )
  }
}
