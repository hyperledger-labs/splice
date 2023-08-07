package com.daml.network.store.db

import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.{ContractStateEvent, ReassignmentId}
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.network.store.db.AcsTables.*
import com.daml.network.store.{MultiDomainAcsStoreTest, StoreTest}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.resource.DbStorage
import org.scalatest.Assertion
import slick.jdbc.JdbcProfile
import slick.lifted.ProvenShape

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
  }

  private def storeDescriptor(id: Int) =
    io.circe.parser
      .parse(raw"""{"test": "DbMultiDomainAcsStoreTest", "id": $id}""")
      .getOrElse(sys.error("Why is it so hard to define a JSON literal"))

  override def mkStore(id: Int) = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResource("dar/canton-coin-0.1.0.dar")
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    lazy val store
        : DbMultiDomainAcsStore[StoreTest.TestTxLogIndexRecord, StoreTest.TestTxLogEntry] =
      new DbMultiDomainAcsStore(
        storage,
        "acs_store_template",
        "txlog_store_template",
        storeDescriptor(id),
        _ => Future.successful(d1),
        loggerFactory,
        contractFilter,
        TestTxLogStoreParser,
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
        (evt, _) => Right(create(store.storeId, evt)),
        (_, _) => Right(DBIO.successful(())),
      )
    store
  }
  override def assertTestState(
      contractStateEventsById: Map[ContractId[_], ContractStateEvent] = Map.empty,
      incompleteTransfersById: Map[ContractId[_], NonEmpty[Set[ReassignmentId]]] = Map.empty,
  )(implicit store: Store): Future[Assertion] = Future.successful(succeed)

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
