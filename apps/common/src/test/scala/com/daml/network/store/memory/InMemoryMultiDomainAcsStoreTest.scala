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
import com.daml.network.util.Contract
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLogging

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

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

    // TODO(#6634): Move to MultiDomainAcsStoreTest once implemented for DbMultiDomainAcsStore
    "signals offsets as ingestion progresses" in {
      implicit val store = mkStore()

      val acsOffset = "010"
      val tx1Offset = "012"

      val signal_010 = store.signalWhenIngestedOrShutdown("010")
      val signal_011 = store.signalWhenIngestedOrShutdown("011")
      val signal_012 = store.signalWhenIngestedOrShutdown("012")

      def notCompleted(futures: Future[Unit]*) =
        forAll(futures)(_.isCompleted shouldBe false)

      notCompleted(
        signal_010,
        signal_011,
        signal_012,
      )

      for {
        _ <- acs(acsOffset = acsOffset)
        _ <- signal_010
        _ = notCompleted(signal_011, signal_012)
        _ <- d1.create(c(1), tx1Offset)
        _ <- signal_011
        _ <- signal_012
        // We can still register signals for already ingested offsets
        _ <- store.signalWhenIngestedOrShutdown("010")
      } yield succeed
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
        // Note: We cannot currently reliably order transfer events
        // so we don't know if the transfer in is actually a newer state.
        // (We could have missed the transfer out if it happened before the ACS offset).
        // So we give up on a bit of liveness and mark the contract as in-flight
        // until we see the transfer out.
        _ <- assertList(c(1) -> None)
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
        _ <- assertList(c(1) -> None)
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
        // We cannot reliably order transfer in events
        // We could however order this one here since a transfer in
        // naturally has to happen after the first create.
        // However, our implementation currently treats the initial create
        // and transfer ins uniformly and also marks this as in-flight.
        _ <- assertList(c(1) -> None)
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
        _ <- assertList(c(1) -> None)
        _ <- d2.transferOut(c(1) -> d1, tf1, 2)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> None)
        _ <- d1.transferOut(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.transferIn(c(1) -> d2, tf1, 2)
        _ <- assertList(c(1) -> None)
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
    "in-flight transfer out + transfer in" in {
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
    "no in-flight transfer out, transfer in" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- assertTestState(
          // Currently, we leak these two states for contracts where we see the transfer in but not transfer out
          // which can happen during bootstrapping. See the docs on InMemoryMultiDomainAcsStore.State for details.
          archivedTombstones = Set(c(1).contractId),
          pendingTransfersById = Map(c(1).contractId -> NonEmpty(Set, TransferId(d1, tf0))),
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
          archivedTombstones = Set(c(1).contractId),
          pendingTransfersById = Map(c(1).contractId -> NonEmpty(Set, TransferId(d2, tf0))),
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
    "filtering of in-flight transfer outs" in {
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
      for {
        // in-flight transfer out
        _ <- acs(
          incompleteOut = Seq(
            (c(1), d1, d2, tf0, 1L)
          )
        )
        _ = eventually()(transfers.get should have length 1)
        _ <- assertReadyForTransferIn(tf0Id, true)
        _ <- d2.transferIn(c(1) -> d1, tf0, 1)
        _ <- assertReadyForTransferIn(tf0Id, false)
        // transfer out before transfer in
        _ <- d2.transferOut(c(1) -> d1, tf1, 2)
        _ <- assertReadyForTransferIn(tf1Id, true)
        _ = eventually()(transfers.get should have length 2)
        _ <- d1.transferIn(c(1) -> d2, tf1, 2)
        _ <- assertReadyForTransferIn(tf1Id, false)
        // transfer in before transfer out
        _ <- d3.transferIn(c(1) -> d1, tf2, 3)
        _ <- assertReadyForTransferIn(tf2Id, false)
        _ <- d1.transferOut(c(1) -> d3, tf2, 3)
        _ <- assertReadyForTransferIn(tf2Id, false)
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
        // Complete transfer in of in-flight transfer out.
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
