package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{MultiDomainAcsStore, MultiDomainAcsStoreTest}
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.network.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import org.scalatest.DoNotDiscover

import scala.concurrent.Future

/** Note: this test is currently not passing, as DbMultiDomainAcsStore is not fully implemented.
  * Once DbMultiDomainAcsStore is fully implemented, remove the [[DoNotDiscover]] annotation,
  * and merge this test with [[DbMultiDomainAcsStoreTest]].
  */
@DoNotDiscover
class DbMultiDomainAcsStoreTest2
    extends MultiDomainAcsStoreTest[
      DbMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry]
    ]
    with CNPostgresTest {
  import MultiDomainAcsStore.*

  override def mkStore(): Store = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResource("dar/canton-coin-0.1.0.dar")
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val domainId = DomainId.tryFromString("domain1::domain")
    new DbMultiDomainAcsStore(
      storage,
      "acs_store_template",
      resolveDomainId = Future.successful(domainId),
      loggerFactory,
      TestTxLogStoreParser,
      FutureSupervisor.Noop,
      RetryProvider(loggerFactory, timeouts),
    )
  }

  override def assertTestState(
      contractStateEventsById: Map[ContractId[_], Long] = Map.empty,
      archivedTombstones: Set[ContractId[_]] = Set.empty,
      pendingTransfersById: Map[ContractId[_], NonEmpty[Set[TransferId]]] = Map.empty,
  )(implicit store: Store) = Future.successful(succeed)

  override protected def cleanDb(storage: DbStorage): Future[?] = {
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
  }
}
