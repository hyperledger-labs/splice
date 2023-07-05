package com.daml.network.store.db

import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin as coinCodegen
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
      implicit val store = mkStore()
      val coupons = (1 to 3).map(n => appRewardCoupon(n, svcParty))
      val seenCoupons =
        new AtomicReference(
          Seq.empty[Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]]
        )
      val done = store
        .streamReadyContracts(coinCodegen.AppRewardCoupon.COMPANION, pageSize = PageLimit(1))
        .take(3)
        .runForeach(coupon => seenCoupons.updateAndGet(x => x.appended(coupon.contract)))
      for {
        _ <- store.ingestionSink.initialize()
        _ <- store.ingestionSink.ingestAcs("0", Seq.empty, Seq.empty)
        _ <- dummyDomain.create(coupons.head)
        _ = eventually()(seenCoupons.get() should be(Seq(coupons.head)))
        _ <- dummyDomain.create(coupons(1))
        _ = eventually()(seenCoupons.get() should be(coupons.take(2)))
        _ <- dummyDomain.create(coupons(2))
        _ <- done
      } yield {
        seenCoupons.get() should be(coupons)
      }
    }

    "allow creating same contract id in different stores" in {
      val store1 = mkStore(id = 1)
      val store2 = mkStore(id = 2)
      val coupon = appRewardCoupon(1, svcParty)
      for {
        _ <- store1.ingestionSink.initialize()
        _ <- store1.ingestionSink.ingestAcs("0", Seq.empty, Seq.empty)
        _ <- store2.ingestionSink.initialize()
        _ <- store2.ingestionSink.ingestAcs("0", Seq.empty, Seq.empty)
        _ <- dummyDomain.create(coupon)(store1)
        _ <- dummyDomain.create(coupon)(store2)
      } yield {
        eventually() {
          store1
            .listContracts(coinCodegen.AppRewardCoupon.COMPANION)
            .futureValue
            .map(_.contract) should be(Seq(coupon))
          store2
            .listContracts(coinCodegen.AppRewardCoupon.COMPANION)
            .futureValue
            .map(_.contract) should be(Seq(coupon))
        }
      }
    }

    "ingest the initial acs" in {
      implicit val store = mkStore()
      val couponsAcs =
        (1 to 3)
          .map(n => appRewardCoupon(n, svcParty))
          .map(c => toActiveContract(dummyDomain, c))
      for {
        _ <- store.ingestionSink.initialize()
        _ <- store.ingestionSink.ingestAcs("0", couponsAcs, Seq.empty)
        _ <- store.waitUntilAcsIngested()
      } yield {
        store
          .listContracts(coinCodegen.AppRewardCoupon.COMPANION)
          .futureValue
          // Note: the ACS is not ordered
          .map(_.contract.payload.round.number) should contain theSameElementsAs (1L to 3L)
      }
    }

  }

  private def storeDescriptor(id: Int) =
    io.circe.parser
      .parse(raw"""{"test": "DbMultiDomainAcsStoreTest", "id": $id}""")
      .getOrElse(sys.error("Why is it so hard to define a JSON literal"))

  private def mkStore(id: Int = 1) = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResource("dar/canton-coin-0.1.0.dar")
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val contractFilter = MultiDomainAcsStore
      .SimpleContractFilter(
        PartyId.tryFromProtoPrimitive("aaaa::bbbb"),
        Map(MultiDomainAcsStore.mkFilter(coinCodegen.AppRewardCoupon.COMPANION)(_ => true)),
        Map.empty,
      )

    lazy val store
        : DbMultiDomainAcsStore[StoreTest.TestTxLogIndexRecord, StoreTest.TestTxLogEntry] =
      new DbMultiDomainAcsStore(
        storage,
        "acs_store_template",
        "txlog_store_template",
        storeDescriptor(id),
        _ => Future.successful(DomainId.tryFromString("domain1::domain")),
        loggerFactory,
        contractFilter,
        TestTxLogStoreParser,
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
        (evt, _) => Right(create(store.storeId, evt)),
        (_, _) => Right(DBIO.successful(())),
      )
    store
  }

  private var eventNumber = 0L
  private def create(
      storeId: Int,
      evt: CreatedEvent,
  ): DBIO[Unit] = {
    val contract = Contract
      .fromCreatedEvent(coinCodegen.AppRewardCoupon.COMPANION)(evt)
      .valueOrFail("Failed to parse contract.")
    val contractId = new ContractId[Any](evt.getContractId)
    val row = AcsStoreRowTemplate(
      storeId = storeId,
      eventNumber = eventNumber,
      contractId = contractId,
      templateId = TemplateId.fromIdentifier(
        Identifier.of(
          contract.identifier.getPackageId,
          contract.identifier.getModuleName,
          contract.identifier.getEntityName,
        )
      ),
      createArguments = io.circe.parser
        .parse(payloadJsonFromContract(contract.payload).compactPrint)
        .valueOrFail("circe couldn't parse spray json"),
      contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt),
      contractMetadataContractKeyHash = Some(contract.metadata.contractKeyHash.toStringUtf8),
      contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray,
    )
    insertRowIfNotExists(AcsStoreTemplateTable)(
      row => row.contractId === contractId && row.storeId === storeId,
      row,
    ).map { result =>
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
