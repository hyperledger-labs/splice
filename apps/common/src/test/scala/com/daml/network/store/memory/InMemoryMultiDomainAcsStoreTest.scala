package com.daml.network.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  HardLimit,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
}
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.network.util.Contract
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.concurrent.FutureSupervisor

class InMemoryMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      InMemoryMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry]
    ] {
  import MultiDomainAcsStore.*

  override def mkStore(): InMemoryMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry] =
    new InMemoryMultiDomainAcsStore(
      loggerFactory,
      txFilter,
      TestTxLogStoreParser,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
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

  "filter before limit" in {
    implicit val store = mkStore()
    for {
      _ <- acs()
      _ <- d1.create(c(1))
      _ <- d1.create(c(2))
      round1 <- store.filterContracts(
        AppRewardCoupon.COMPANION,
        limit = HardLimit(1),
        filter =
          (c: Contract[AppRewardCoupon.ContractId, AppRewardCoupon]) => c.payload.round.number == 1,
      )
      round2 <- store.filterContracts(
        AppRewardCoupon.COMPANION,
        limit = HardLimit(1),
        filter =
          (c: Contract[AppRewardCoupon.ContractId, AppRewardCoupon]) => c.payload.round.number == 2,
      )
    } yield {
      round1.map(_.contract) shouldBe Seq(c(1))
      round2.map(_.contract) shouldBe Seq(c(2))
    }
  }
}
