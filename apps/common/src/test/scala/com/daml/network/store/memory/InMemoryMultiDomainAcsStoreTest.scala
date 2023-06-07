package com.daml.network.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
}
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.concurrent.FutureSupervisor

class InMemoryMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      InMemoryMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry]
    ] {
  import MultiDomainAcsStore.*

  override def mkStore(): Store =
    new InMemoryMultiDomainAcsStore(
      loggerFactory,
      txFilter,
      TestTxLogStoreParser,
      FutureSupervisor.Noop,
      RetryProvider(loggerFactory, timeouts),
    )(actorSystem.dispatcher)

  override def assertTestState(
      contractStateEventsById: Map[ContractId[_], Long] = Map.empty,
      archivedTombstones: Set[ContractId[_]] = Set.empty,
      pendingTransfersById: Map[ContractId[_], NonEmpty[Set[TransferId]]] = Map.empty,
  )(implicit store: Store) =
    for {
      actualContractStateEventsById <- store.contractStateEventsById
      actualArchivedTombstones <- store.archivedTombstones
      actualPendingTransfersById <- store.pendingTransfersById
    } yield {
      clue("contractStateEventsById") {
        actualContractStateEventsById shouldBe contractStateEventsById
      }
      clue("archivedTombstones") {
        actualArchivedTombstones shouldBe archivedTombstones
      }
      clue("pendingTransfersById") {
        actualPendingTransfersById shouldBe pendingTransfersById
      }
    }
}
