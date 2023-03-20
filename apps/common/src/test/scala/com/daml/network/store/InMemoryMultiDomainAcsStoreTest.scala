package com.daml.network.store

import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.TreeEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{
  TransferUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.util.Contract
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.topology.DomainId

class InMemoryMultiDomainAcsStoreTest extends StoreTest {

  import MultiDomainAcsStore.{ContractState, ContractWithState}
  import InMemoryMultiDomainAcsStore.TransferId

  private var offsetCounter = 0

  private def nextOffset: String = {
    val offset = "%08d".format(offsetCounter)
    offsetCounter += 1
    offset
  }

  private var transferCounter = 0
  private def nextTransferId: String = {
    val id = "%08d".format(transferCounter)
    transferCounter += 1
    id
  }

  private val txFilter: AcsStore.ContractFilter = {
    import AcsStore.mkFilter

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured)
      ),
    )
  }

  private def mkStore(): InMemoryMultiDomainAcsStore =
    new InMemoryMultiDomainAcsStore(
      loggerFactory,
      txFilter,
    )

  private def mkCreateTx[TCid <: ContractId[T], T](
      offset: String,
      createRequests: Seq[Contract[TCid, T]],
  ) = mkTx(offset, createRequests.map[TreeEvent](toCreatedEvent))

  // Convenient syntax to make the tests easy to read.
  private implicit class DomainSyntax(private val domain: DomainId) {
    def acsAndTransferOuts[TCid <: ContractId[T], T](
        acs: Seq[Contract[TCid, T]] = Seq.empty,
        transferOuts: Seq[((Contract[TCid, T], DomainId), String)] = Seq.empty,
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.ingestAcsAndTransferOuts(
        domain,
        acs.map(toCreatedEvent(_)),
        transferOuts.map { case ((c, targetDomain), tfid) =>
          toTransferOutEvent(
            c.contractId,
            tfid,
            domain,
            targetDomain,
          )
        },
      )

    def switchToUpdates()(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.switchToIngestingUpdates(domain, nextOffset)

    def create[TCid <: ContractId[T], T](
        c: Contract[TCid, T]
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.ingestUpdate(
        domain,
        TransactionTreeUpdate(
          mkCreateTx(
            nextOffset,
            Seq(c),
          )
        ),
      )
    def archive[TCid <: ContractId[T], T](
        c: Contract[TCid, T]
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.ingestUpdate(
        domain,
        TransactionTreeUpdate(
          mkTx(nextOffset, Seq(toArchivedEvent(c)))
        ),
      )

    def transferOut[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], DomainId),
        transferId: String,
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.ingestUpdate(
        domain,
        TransferUpdate(
          mkTransfer(
            nextOffset,
            toTransferOutEvent(
              contractAndDomain._1.contractId,
              transferId,
              domain,
              contractAndDomain._2,
            ),
          )
        ),
      )

    def transferIn[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], DomainId),
        transferId: String,
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.ingestUpdate(
        domain,
        TransferUpdate(
          mkTransfer(
            nextOffset,
            toTransferInEvent(
              contractAndDomain._1,
              transferId,
              contractAndDomain._2,
              domain,
            ),
          )
        ),
      )

    def removeTransferOutIfBootstrap[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], DomainId),
        transferId: String,
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.removeTransferOutIfBootstrap(
        toTransferOutEvent(
          contractAndDomain._1.contractId,
          transferId,
          domain,
          contractAndDomain._2,
        )
      )
  }

  private type C = Contract[AppRewardCoupon.ContractId, AppRewardCoupon]

  private def assertList(expected: (C, Option[DomainId])*)(implicit store: MultiDomainAcsStore) = {
    val expected_ = expected.map {
      case (c, None) => ContractWithState(c, ContractState.InFlight)
      case (c, Some(id)) => ContractWithState(c, ContractState.Assigned(id))
    }
    for {
      actualList <- store.listContracts(AppRewardCoupon.COMPANION)
      _ = actualList shouldBe expected_
      _ <- expected_.traverse_ { c =>
        store
          .lookupContractById(AppRewardCoupon.COMPANION)(c.contract.contractId)
          .map(_ shouldBe Some(c))
      }
    } yield succeed
  }

  private def assertLookupNone(c: C)(implicit store: MultiDomainAcsStore) =
    store.lookupContractById(AppRewardCoupon.COMPANION)(c.contractId)

  private def assertTestState(
      contractLocationsById: Map[ContractId[_], NonEmpty[Map[DomainId, Long]]] = Map.empty,
      archivedTombstones: Set[ContractId[_]] = Set.empty,
      pendingTransfersById: Map[ContractId[_], NonEmpty[Set[TransferId]]] = Map.empty,
      bootstrapTransferOutIds: Set[TransferId] = Set.empty,
  )(implicit store: InMemoryMultiDomainAcsStore) =
    for {
      actualContractLocationsById <- store.contractLocationsById
      actualArchivedTombstones <- store.archivedTombstones
      actualPendingTransfersById <- store.pendingTransfersById
      actualBootstrapTransferOutIds <- store.bootstrapTransferOutIds
    } yield {
      clue("contractLocationsById") {
        actualContractLocationsById shouldBe contractLocationsById
      }
      clue("archivedTombstones") {
        actualArchivedTombstones shouldBe archivedTombstones
      }
      clue("pendingTransfersById") {
        actualPendingTransfersById shouldBe pendingTransfersById
      }
      clue("bootstrapTransferOutIds") {
        actualBootstrapTransferOutIds shouldBe bootstrapTransferOutIds
      }
    }

  private def c(i: Int): C = appRewardCoupon(i, svcParty)
  private def cFeatured(i: Int): C = appRewardCoupon(i, svcParty, true)

  private val d1: DomainId = DomainId.tryFromString("domain1::domain")
  private val d2: DomainId = DomainId.tryFromString("domain2::domain")
  private val d3: DomainId = DomainId.tryFromString("domain3::domain")

  "InMemoryMultiDomainAcsStore" should {
    "single domain creates and archives" in {
      implicit val store = mkStore()
      for {
        _ <- d1.switchToUpdates()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.create(c(2))
        _ <- assertList(c(1) -> Some(d1), c(2) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertLookupNone(c(1))
        _ <- assertList(c(2) -> Some(d1))
        _ <- d1.archive(c(2))
        _ <- assertLookupNone(c(2))
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "single domain acs" in {
      implicit val store = mkStore()
      for {
        _ <- d1.acsAndTransferOuts(
          acs = Seq(c(1))
        )
        _ <- d1.switchToUpdates()
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertLookupNone(c(1))
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "transfer out before transfer in" in {
      implicit val store = mkStore()
      for {
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextTransferId
        _ <- d1.transferOut(c(1) -> d2, tf0)
        _ <- assertList(c(1) -> None)
        _ <- d2.transferIn(c(1) -> d1, tf0)
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
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0)
        // Note: We cannot currently reliably order transfer events
        // so we don't know if the transfer in is actually a newer state.
        // (We could have missed the transfer out if it happened before the ACS offset).
        // So we give up on a bit of liveness and mark the contract as in-flight
        // until we see the transfer out.
        _ <- assertList(c(1) -> None)
        _ <- d1.transferOut(c(1) -> d2, tf0)
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
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ <- assertList(c(1) -> None)
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- d1.transferOut(c(1) -> d2, tf0)
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "transfer in before create" in {
      implicit val store = mkStore()
      for {
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- assertList()
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d1.create(c(1))
        // We cannot reliably order transfer in events
        // We could however order this one here since a transfer in
        // naturally has to happen after the first create.
        // However, our implementation currently treats the initial create
        // and transfer ins uniformly and also marks this as in-flight.
        _ <- assertList(c(1) -> None)
        _ <- d1.transferOut(c(1) -> d2, tf0)
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
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- d3.switchToUpdates()
        _ <- assertList()
        tf0 = nextTransferId
        tf1 = nextTransferId
        tf2 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d3.transferIn(c(1) -> d1, tf2)
        _ <- assertList(c(1) -> None)
        _ <- d2.transferOut(c(1) -> d1, tf1)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> None)
        _ <- d1.transferOut(c(1) -> d2, tf0)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d1.transferIn(c(1) -> d2, tf1)
        _ <- assertList(c(1) -> None)
        _ <- d1.transferOut(c(1) -> d3, tf2)
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
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- assertList()
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertLookupNone(c(1))
        _ <- d1.create(c(1))
        _ <- assertList()
        _ <- d1.transferOut(c(1) -> d2, tf0)
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "archive before transfer in" in {
      implicit val store = mkStore()
      for {
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- d3.switchToUpdates()
        _ <- assertList()
        tf0 = nextTransferId
        tf1 = nextTransferId
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.transferOut(c(1) -> d2, tf0)
        _ <- assertList(c(1) -> None)
        _ <- d3.transferIn(c(1) -> d2, tf1)
        _ <- assertList(c(1) -> Some(d3))
        _ <- d3.archive(c(1))
        _ <- assertList()
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ <- assertList()
        _ <- d2.transferOut(c(1) -> d3, tf1)
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "in-flight transfer out + transfer in" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      for {
        _ <- d1.acsAndTransferOuts(
          transferOuts = Seq(
            (c(1) -> d2, tf0)
          )
        )
        _ <- d1.switchToUpdates()
        _ <- assertList()
        _ <- d2.switchToUpdates()
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }
    "in-flight transfer out, no transfer in" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      for {
        _ <- d1.acsAndTransferOuts(
          transferOuts = Seq((c(1) -> d2, tf0))
        )
        _ <- d2.acsAndTransferOuts(
          acs = Seq(c(1))
        )
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- assertList(c(1) -> Some(d2))
        // Automation calls this once transfer in fails.
        _ <- d1.removeTransferOutIfBootstrap(c(1) -> d2, tf0)
        _ <- assertList(c(1) -> Some(d2))
        _ <- d2.archive(c(1))
        _ <- assertTestState()
      } yield succeed
    }
    "no in-flight transfer out, transfer in" in {
      implicit val store = mkStore()
      for {
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        tf0 = nextTransferId
        _ <- d2.transferIn(c(1) -> d1, tf0)
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
    "filtering of acs" in {
      implicit val store = mkStore()
      for {
        _ <- d1.acsAndTransferOuts(
          Seq(c(1), cFeatured(2))
        )
        _ <- d1.switchToUpdates()
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertTestState()
      } yield succeed
    }

    "filtering of create" in {
      implicit val store = mkStore()
      for {
        _ <- d1.switchToUpdates()
        _ <- d1.create(c(1))
        _ <- d1.create(cFeatured(2))
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertTestState()
      } yield succeed
    }
    "filtering of transfer in" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      val tf1 = nextTransferId
      for {
        _ <- d1.switchToUpdates()
        _ <- d1.transferIn(c(1) -> d2, tf0)
        _ <- d1.transferIn(cFeatured(2) -> d2, tf1)
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
        _ <- d1.switchToUpdates()
        _ <- d1.create(cFeatured(1))
        _ <- d1.transferOut(c(1) -> d2, tf0)
        _ <- assertTestState()
      } yield succeed
    }
    "filtering of in-flight transfer outs" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      for {
        _ <- d1.acsAndTransferOuts(
          transferOuts = Seq((cFeatured(1) -> d2, tf0))
        )
        _ <- d1.switchToUpdates()
        _ <- assertTestState(
          // Currently no filtering of in-flight transfer outs
          pendingTransfersById = Map(cFeatured(1).contractId -> NonEmpty(Set, TransferId(d1, tf0))),
          bootstrapTransferOutIds = Set(TransferId(d1, tf0)),
        )
      } yield succeed
    }
  }
}
