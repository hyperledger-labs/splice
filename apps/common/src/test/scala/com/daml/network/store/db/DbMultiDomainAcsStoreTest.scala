package com.daml.network.store.db

import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.environment.RetryProvider
import com.daml.network.store.StoreTest.TestTxLogStoreParser
import com.daml.network.store.db.AcsTables.*
import com.daml.network.store.{MultiDomainAcsStore, PageLimit, StoreTest}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, PartyId}
import slick.jdbc.JdbcProfile
import slick.lifted.ProvenShape

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

class DbMultiDomainAcsStoreTest
    extends StoreTest
    with CNPostgresTest
    with HasActorSystem
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile
  import storage.api.jdbcProfile.api.*

  "DbMultiDomainAcsStore" should {

    "stream rows" in {
      val store = mkStore()
      val coupons = (1 to 3).map(n => appRewardCoupon(n, svcParty))
      val seenCoupons =
        new AtomicReference(Seq.empty[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]])
      val done = store
        .streamReadyContracts(AppRewardCoupon.COMPANION, pageSize = PageLimit(1))
        .take(3)
        .runForeach(coupon => seenCoupons.updateAndGet(x => x.appended(coupon.contract)))
      for {
        _ <- store.ingestionSink.initialize()
        _ <- store.ingestionSink.ingestAcs("0", Seq.empty, Seq.empty)
        _ <- create(store.storeId, coupons.head)
        _ = eventually()(seenCoupons.get() should be(Seq(coupons.head)))
        _ <- create(store.storeId, coupons(1))
        _ = eventually()(seenCoupons.get() should be(coupons.take(2)))
        _ <- create(store.storeId, coupons(2))
        _ <- done
      } yield {
        seenCoupons.get() should be(coupons)
      }
    }

  }

  private val storeDescriptor =
    io.circe.parser
      .parse(raw"""{"test": "DbMultiDomainAcsStoreTest"}""")
      .getOrElse(sys.error("Why is it so hard to define a JSON literal"))

  private def mkStore() = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResource("dar/canton-coin-0.1.0.dar")
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val unusedTxFilter = MultiDomainAcsStore
      .SimpleContractFilter(PartyId.tryFromProtoPrimitive("aaaa::bbbb"), Map.empty, Map.empty)

    new DbMultiDomainAcsStore(
      storage,
      "acs_store_template",
      storeDescriptor,
      _ => Future.successful(DomainId.tryFromString("domain1::domain")),
      loggerFactory,
      unusedTxFilter,
      TestTxLogStoreParser,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )
  }

  private var eventNumber = 0L
  private def create(
      storeId: Int,
      contract: Contract[AppRewardCoupon.ContractId, AppRewardCoupon],
  ) = {
    val row = AcsStoreRowTemplate(
      storeId = storeId,
      eventNumber = eventNumber,
      contractId = contract.contractId.asInstanceOf[ContractId[Any]],
      templateId = TemplateId.fromIdentifier(
        Identifier.of(
          contract.identifier.getPackageId,
          contract.identifier.getModuleName,
          contract.identifier.getEntityName,
        )
      ),
      createArguments = contract.toJson.payload,
      contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt),
      contractMetadataContractKeyHash = Some(contract.metadata.contractKeyHash.toStringUtf8),
      contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray,
    )
    storage
      .queryAndUpdate(
        insertRowIfNotExists(AcsStoreTemplateTable)(
          _.contractId === contract.contractId.asInstanceOf[ContractId[Any]],
          row,
        ),
        "insert",
      )
      .map { result =>
        eventNumber += 1
        result
      }
  }

  // we can just use the template table for these
  lazy val AcsStoreTemplateTable = new TableQuery(tag =>
    new AcsStoreTemplate[AcsStoreRowTemplate](tag, "acs_store_template") {
      override def * : ProvenShape[AcsStoreRowTemplate] =
        templateColumns.tupled.<>((AcsStoreRowTemplate.apply _).tupled, AcsStoreRowTemplate.unapply)
    }
  )

  override protected def cleanDb(storage: DbStorage): Future[?] = {
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
  }
}
