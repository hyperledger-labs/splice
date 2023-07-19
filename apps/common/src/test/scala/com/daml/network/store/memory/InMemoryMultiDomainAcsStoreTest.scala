package com.daml.network.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.codegen.java.cn.wallet.payment.DeliveryOffer
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.TransferEvent
import com.daml.network.store.{
  HardLimit,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
}
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.network.util.{Contract, ContractWithState, ReadyContract}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLogging

import java.util.concurrent.atomic.AtomicReference

class InMemoryMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      InMemoryMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry]
    ]
    with HasExecutionContext
    with NamedLogging
    with HasActorSystem {
  import MultiDomainAcsStore.*

  override def mkStore(id: Int): InMemoryMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry] =
    new InMemoryMultiDomainAcsStore(
      loggerFactory,
      contractFilter,
      TestTxLogStoreParser,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )(actorSystem.dispatcher)

  override def assertTestState(
      contractStateEventsById: Map[ContractId[_], ContractStateEvent] = Map.empty,
      incompleteTransfersById: Map[ContractId[_], NonEmpty[Set[TransferId]]] = Map.empty,
  )(implicit store: Store) =
    for {
      actualContractStateEventsById <- store.contractStateEventsById
      actualIncompleteTransfersById <- store.incompleteTransfersById
    } yield {
      clue("contractStateEventsById") {
        actualContractStateEventsById shouldBe contractStateEventsById
      }
      clue("incompleteTransfersById") {
        actualIncompleteTransfersById shouldBe incompleteTransfersById
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

  "InMemoryMultiDomainAcsStore" should {
    // TODO(#5012): Move to MultiDomainAcsStoreTest once implemented for DbMultiDomainAcsStore
    "interfaces" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- d1.create(transferInProgress(1))
        _ <- d1.create(testDeliveryOffer(2))
        cs <- store.listContracts(DeliveryOffer.INTERFACE, limit = HardLimit(2L))
      } yield {
        cs shouldBe Seq(
          ContractWithState(
            deliveryOffer(1, "TransferInProgress"),
            ContractState.Assigned(d1),
          ),
          ContractWithState(
            deliveryOffer(2, "TestDeliveryOffer"),
            ContractState.Assigned(d1),
          ),
        )
      }
    }

    // TODO(#5314): Move to MultiDomainAcsStoreTest once implemented for DbMultiDomainAcsStore
    "transfer out before transfer in" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextTransferId
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> None)
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertLookupNone(c(1))
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "transfer in before transfer out" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- assertTestState()
      } yield succeed
    }
    "transfer in and archive before transfer out" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "transfer in before create" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- assertTestState()
      } yield succeed
    }
    "multiple early transfer ins" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextTransferId
        tf1 = nextTransferId
        tf2 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d3.transferIn(c(1) -> d1, tf2, 3)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d2.transferOut(c(1) -> d1, tf1, 2)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.transferIn(c(1) -> d2, tf1, 2)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.transferOut(c(1) -> d3, tf2, 3)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d3.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- assertTestState()
      } yield succeed
    }
    "archive before create" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- d1.create(c(1))
        _ <- assertList()
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "archive before transfer in" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextTransferId
        tf1 = nextTransferId
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> None)
        _ <- d3.transferIn(c(1) -> d2, tf1, 2)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d3.archive(c(1))
        _ <- assertList()
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList()
        _ <- d2.transferOut(c(1) -> d3, tf1, 2)
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "incomplete transfer out + transfer in" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      for {
        _ <- acs(
          incompleteOut = Seq(
            (c(1), d1, d2, tf0, 1L)
          )
        )
        _ <- assertList(c(1) -> None)
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "incomplete transfer in + transfer out" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      for {
        _ <- acs(
          incompleteIn = Seq(
            (c(1), d1, d2, tf0, 1L)
          )
        )
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertTestState(
        )
      } yield succeed
    }

    "filtering of transfer in" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      val tf1 = nextTransferId
      for {
        _ <- acs()
        _ <- d1.transferIn(c(1) -> d2, tf0, 1)
        _ <- d1.transferIn(cFeatured(2) -> d2, tf1, 2)
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertTestState(
          // We've not yet seen the transfer out
          contractStateEventsById = Map(
            c(1).contractId -> ContractStateEvent(c(1).contractId, 1, StoreContractState.Archived)
          ),
          incompleteTransfersById = Map(c(1).contractId -> NonEmpty(Set, TransferId(d2, tf0))),
        )
      } yield succeed
    }
    "filtering of transfer out" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      for {
        _ <- acs()
        _ <- d1.create(cFeatured(1))
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertTestState()
      } yield succeed
    }
    "filtering of incomplete transfer outs" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      for {
        _ <- acs(
          incompleteOut = Seq((cFeatured(1), d1, d2, tf0, 1L))
        )
        _ <- assertTestState(
        )
      } yield succeed
    }
    "stream transfers and report stale transfers" in {
      implicit val store = mkStore()
      val transfers = new AtomicReference(Seq.empty[TransferEvent.Out])
      val streamF = store
        .streamReadyForTransferIn()
        .take(2)
        .runForeach { transfer => transfers.updateAndGet(_.appended(transfer)) }
      val tf0 = nextTransferId
      val tf0Id = TransferId(d1, tf0)
      val tf1 = nextTransferId
      val tf1Id = TransferId(d2, tf1)
      val tf2 = nextTransferId
      val tf2Id = TransferId(d1, tf2)
      val cid = c(1).contractId
      for {
        // incomplete transfer out
        _ <- acs(
          incompleteOut = Seq(
            (c(1), d1, d2, tf0, 1L)
          )
        )
        _ = eventually()(transfers.get should have length 1)
        _ <- assertReadyForTransferIn(cid, tf0Id, true)
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertReadyForTransferIn(cid, tf0Id, false)
        // transfer out before transfer in
        _ <- d2.transferOut(c(1) -> d1, tf1, 2)
        _ <- assertReadyForTransferIn(cid, tf1Id, true)
        _ = eventually()(transfers.get should have length 2)
        _ <- d1.transferIn(c(1) -> d2, tf1, 2)
        _ <- assertReadyForTransferIn(cid, tf1Id, false)
        // transfer in before transfer out
        _ <- d3.transferIn(c(1) -> d1, tf2, 3)
        _ <- assertReadyForTransferIn(cid, tf2Id, false)
        _ <- d1.transferOut(c(1) -> d3, tf2, 3)
        _ <- assertReadyForTransferIn(cid, tf2Id, false)
        _ <- streamF
      } yield {
        transfers.get().map(TransferId.fromTransferOut(_)) shouldBe Seq(tf0Id, tf1Id)
      }
    }
    "stream ready contracts" in {
      implicit val store = mkStore()
      val readyContracts = new AtomicReference(Seq.empty[CReady])
      val streamF = store
        .streamReadyContracts(AppRewardCoupon.COMPANION)
        .take(5)
        .runForeach { readyContract => readyContracts.updateAndGet(_.appended(readyContract)) }
      val tf0 = nextTransferId
      val tf1 = nextTransferId
      val tf2 = nextTransferId
      for {
        _ <- acs(
          acs = Seq((c(1), d1, 0L)),
          incompleteOut = Seq((c(2), d1, d2, tf2, 3L)),
        )
        _ = eventually()(readyContracts.get() should have size 1)
        // transfer out before transfer in
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ = eventually()(readyContracts.get() should have size 2)
        // transfer in before transfer out
        _ <- d1.transferIn(c(1) -> d2, tf1, 2)
        _ <- d2.transferOut(c(1) -> d1, tf1, 2)
        _ = eventually()(readyContracts.get() should have size 3)
        // Complete transfer in of incomplete transfer out.
        _ <- d2.transferIn(c(2) -> d1, tf2, 3)
        _ = eventually()(readyContracts.get() should have size 4)
        _ <- d2.create(c(3))
        _ = eventually()(readyContracts.get() should have size 5)
        _ <- streamF
      } yield {
        readyContracts.get() shouldBe Seq(
          ReadyContract(c(1), d1),
          ReadyContract(c(1), d2),
          ReadyContract(c(1), d1),
          ReadyContract(c(2), d2),
          ReadyContract(c(3), d2),
        )
      }
    }
  }
}
