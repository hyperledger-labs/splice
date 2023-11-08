package com.daml.network.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.lf.data.Time.Timestamp
import com.daml.network.environment.{DarResources, RetryProvider}
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.network.store.{MultiDomainAcsStore, MultiDomainAcsStoreTest, StoreTest}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import slick.jdbc.JdbcProfile

import scala.concurrent.Future

class DbMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      DbMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry]
    ]
    with CNPostgresTest
    with HasActorSystem
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile
  import storage.api.jdbcProfile.api.*

  "DbMultiDomainAcsStore" should {

    "allow creating & deleting same contract id in different stores" in {
      val store1 = mkStore(id = 1)
      val store2 = mkStore(id = 2)
      val coupon = c(1)
      for {
        _ <- acs()(store1)
        _ <- acs()(store2)
        _ <- d1.create(coupon)(store1)
        _ <- d1.create(coupon)(store2)
        _ <- assertList(coupon -> Some(d1))(store1)
        _ <- assertList(coupon -> Some(d1))(store2)
        _ <- d1.archive(coupon)(store1)
        _ <- assertList()(store1) // deleted from store1
        _ <- assertList(coupon -> Some(d1))(store2) // but not from store2
      } yield succeed
    }

    "not be SQL-injectable" in {
      val store = mkStoreWithAcsRowDataF(
        1,
        defaultContractFilter,
        acsTableName = "directory_acs_store", // to have extra columns
        (ce, bs, _) =>
          Right {
            val base = acsRowData(defaultContractFilter, ce, bs)
            new AcsRowData {
              override val contract: Contract[?, ?] = base.contract

              override def contractExpiresAt: Option[Timestamp] = base.contractExpiresAt

              override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
                "directory_entry_name" -> lengthLimited("'); DROP TABLE bobby_tables; --")
              )
            }
          },
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

  override def mkStore(id: Int, filter: MultiDomainAcsStore.ContractFilter) = {
    mkStoreWithAcsRowDataF(
      id,
      filter,
      "acs_store_template",
      (evt, blob, _) => Right(acsRowData(filter, evt, blob)),
    )
  }

  def mkStoreWithAcsRowDataF(
      id: Int,
      filter: MultiDomainAcsStore.ContractFilter,
      acsTableName: String,
      getAcsRowData: (
          CreatedEvent,
          ByteString,
          TraceContext,
      ) => Either[String, AcsRowData],
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
      TestTxLogStoreParser,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      getAcsRowData,
      ingestTxLogInsert = (_: StoreTest.TestTxLogIndexRecord, _: TraceContext) =>
        Right(DBIO.successful(())),
    )
  }

  private def acsRowData(
      filter: MultiDomainAcsStore.ContractFilter,
      evt: CreatedEvent,
      createdEventBlob: ByteString,
  ) = {
    new AcsRowData {
      override val contract: Contract[?, ?] = filter
        .decodeMatchingContract(evt, createdEventBlob)
        .valueOrFail("Failed to decode contract.")

      override def contractExpiresAt: Option[Timestamp] = Some(
        Timestamp.Epoch.addMicros(1000000000L)
      )

      override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
    }
  }

  override protected def cleanDb(storage: DbStorage): Future[?] = {
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
  }
}
