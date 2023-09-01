package com.daml.network.store.memory

import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.codegen.java.cn.wallet.payment.DeliveryOffer
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.ReassignmentEvent
import com.daml.network.store.{
  HardLimit,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
}
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord, TestTxLogStoreParser}
import com.daml.network.util.{AssignedContract, Contract, ContractWithState}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory

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
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )(actorSystem.dispatcher)

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
    "unassign before assign" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextReassignmentId
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> None)
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertLookupNone(c(1))
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "assign before unassign" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextReassignmentId
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "assign and archive before unassign" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextReassignmentId
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "assign before create" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextReassignmentId
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "multiple early transfer ins" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextReassignmentId
        tf1 = nextReassignmentId
        tf2 = nextReassignmentId
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d3.assign(c(1) -> d1, tf2, 3)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d2.unassign(c(1) -> d1, tf1, 2)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.assign(c(1) -> d2, tf1, 2)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.unassign(c(1) -> d3, tf2, 3)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d3.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "archive before create" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextReassignmentId
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- d1.create(c(1))
        _ <- assertList()
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "archive before assign" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
        tf0 = nextReassignmentId
        tf1 = nextReassignmentId
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> None)
        _ <- d3.assign(c(1) -> d2, tf1, 2)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d3.archive(c(1))
        _ <- assertList()
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList()
        _ <- d2.unassign(c(1) -> d3, tf1, 2)
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "incomplete unassign + assign" in {
      implicit val store = mkStore()
      val tf0 = nextReassignmentId
      for {
        _ <- acs(
          incompleteOut = Seq(
            (c(1), d1, d2, tf0, 1L)
          )
        )
        _ <- assertList(c(1) -> None)
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "incomplete assign + unassign" in {
      implicit val store = mkStore()
      val tf0 = nextReassignmentId
      for {
        _ <- acs(
          incompleteIn = Seq(
            (c(1), d1, d2, tf0, 1L)
          )
        )
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }

    "filtering of assign" in {
      implicit val store = mkStore()
      val tf0 = nextReassignmentId
      val tf1 = nextReassignmentId
      for {
        _ <- acs()
        _ <- d1.assign(c(1) -> d2, tf0, 1)
        _ <- d1.assign(cFeatured(2) -> d2, tf1, 2)
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertIncompleteReassignments(
          // We've not yet seen the unassign
          Map(c(1).contractId -> NonEmpty(Set, ReassignmentId(d2, tf0)))
        )
      } yield succeed
    }
    "filtering of unassign" in {
      implicit val store = mkStore()
      val tf0 = nextReassignmentId
      for {
        _ <- acs()
        _ <- d1.create(cFeatured(1))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "filtering of incomplete unassigns" in {
      implicit val store = mkStore()
      val tf0 = nextReassignmentId
      for {
        _ <- acs(
          incompleteOut = Seq((cFeatured(1), d1, d2, tf0, 1L))
        )
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "stream transfers and report stale transfers" in {
      implicit val store = mkStore()
      val transfers = new AtomicReference(Seq.empty[ReassignmentEvent.Unassign])
      val streamF = store
        .streamReadyForAssign()
        .take(2)
        .runForeach { transfer => transfers.updateAndGet(_.appended(transfer)) }
      val tf0 = nextReassignmentId
      val tf0Id = ReassignmentId(d1, tf0)
      val tf1 = nextReassignmentId
      val tf1Id = ReassignmentId(d2, tf1)
      val tf2 = nextReassignmentId
      val tf2Id = ReassignmentId(d1, tf2)
      val cid = c(1).contractId
      for {
        // incomplete unassign
        _ <- acs(
          incompleteOut = Seq(
            (c(1), d1, d2, tf0, 1L)
          )
        )
        _ = eventually()(transfers.get should have length 1)
        _ <- assertReadyForAssign(cid, tf0Id, true)
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertReadyForAssign(cid, tf0Id, false)
        // unassign before assign
        _ <- d2.unassign(c(1) -> d1, tf1, 2)
        _ <- assertReadyForAssign(cid, tf1Id, true)
        _ = eventually()(transfers.get should have length 2)
        _ <- d1.assign(c(1) -> d2, tf1, 2)
        _ <- assertReadyForAssign(cid, tf1Id, false)
        // assign before unassign
        _ <- d3.assign(c(1) -> d1, tf2, 3)
        _ <- assertReadyForAssign(cid, tf2Id, false)
        _ <- d1.unassign(c(1) -> d3, tf2, 3)
        _ <- assertReadyForAssign(cid, tf2Id, false)
        _ <- streamF
      } yield {
        transfers.get().map(ReassignmentId.fromUnassign(_)) shouldBe Seq(tf0Id, tf1Id)
      }
    }
    "stream ready contracts" in {
      implicit val store = mkStore()
      val assignedContracts = new AtomicReference(Seq.empty[CReady])
      val streamF = store
        .streamAssignedContracts(AppRewardCoupon.COMPANION)
        .take(5)
        .runForeach { assignedContract =>
          assignedContracts.updateAndGet(_.appended(assignedContract))
        }
      val tf0 = nextReassignmentId
      val tf1 = nextReassignmentId
      val tf2 = nextReassignmentId
      for {
        _ <- acs(
          acs = Seq((c(1), d1, 0L)),
          incompleteOut = Seq((c(2), d1, d2, tf2, 3L)),
        )
        _ = eventually()(assignedContracts.get() should have size 1)
        // unassign before assign
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ = eventually()(assignedContracts.get() should have size 2)
        // assign before unassign
        _ <- d1.assign(c(1) -> d2, tf1, 2)
        _ <- d2.unassign(c(1) -> d1, tf1, 2)
        _ = eventually()(assignedContracts.get() should have size 3)
        // Complete assign of incomplete unassign.
        _ <- d2.assign(c(2) -> d1, tf2, 3)
        _ = eventually()(assignedContracts.get() should have size 4)
        _ <- d2.create(c(3))
        _ = eventually()(assignedContracts.get() should have size 5)
        _ <- streamF
      } yield {
        assignedContracts.get() shouldBe Seq(
          AssignedContract(c(1), d1),
          AssignedContract(c(1), d2),
          AssignedContract(c(1), d1),
          AssignedContract(c(2), d2),
          AssignedContract(c(3), d2),
        )
      }
    }
  }
}
