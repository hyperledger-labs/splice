package com.daml.network.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.network.store.db.AcsTables.ContractStateRowData
import com.daml.network.store.{MultiDomainAcsStoreTest, StoreTest}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
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
        loggerFactory,
        contractFilter,
        TestTxLogStoreParser,
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
        (evt, csr, _) => Right(create(store.storeId, evt, csr)),
        (_, _) => Right(DBIO.successful(())),
      )
    store
  }

  private def create(
      storeId: Int,
      evt: CreatedEvent,
      contractState: ContractStateRowData,
  ) = {
    import storage.DbStorageConverters.setParameterByteArray

    val contract = Contract
      .fromCreatedEvent(coinCodegen.AppRewardCoupon.COMPANION)(evt)
      .valueOrFail("Failed to parse contract.")
    val contractId = new ContractId[Any](evt.getContractId)
    val templateId = contract.identifier
    val createArguments = payloadJsonFromContract(contract.payload)
    val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
    val contractMetadataContractKeyHash =
      lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
    val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
    val contractExpiresAt = Some(contractMetadataCreatedAt.addMicros(1000000000L))
    sqlu"""
      insert into acs_store_template(store_id, contract_id, template_id, create_arguments, contract_metadata_created_at,
                                contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
                                assigned_domain, reassignment_counter, reassignment_target_domain,
                                reassignment_source_domain, reassignment_submitter, reassignment_unassign_id)
      values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
              $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
              ${contractState.assignedDomain}, ${contractState.reassignmentCounter}, ${contractState.reassignmentTargetDomain},
              ${contractState.reassignmentSourceDomain}, ${contractState.reassignmentSubmitter}, ${contractState.reassignmentUnassignId})
      on conflict do nothing
    """
  }

  override protected def cleanDb(storage: DbStorage): Future[?] = {
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
  }
}
