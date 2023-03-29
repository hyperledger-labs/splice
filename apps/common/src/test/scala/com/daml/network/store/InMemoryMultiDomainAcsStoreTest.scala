package com.daml.network.store

import akka.actor.ActorSystem
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.{ContractMetadata, CreatedEvent, TransactionTree, TreeEvent}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.codegen.java.cn.scripts.testwallet.TestDeliveryOffer
import com.daml.network.codegen.java.cn.splitwell.*
import com.daml.network.codegen.java.cn.wallet.payment.{DeliveryOffer, DeliveryOfferView}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{
  TransferEvent,
  TransferUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.Contract
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.google.protobuf
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

import scala.jdk.CollectionConverters.*

class InMemoryMultiDomainAcsStoreTest extends StoreTest {

  implicit val actorSystem: ActorSystem = ActorSystem("InMemoryMultiDomainAcsStoreTest")

  import MultiDomainAcsStore.*
  import AcsStore.InterfaceImplementation

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
      templateFilters = Map(
        mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured)
      ),
      interfaceFilters = Map(
        mkFilter(DeliveryOffer.INTERFACE)(
          _ => true,
          Seq(
            InterfaceImplementation(
              TransferInProgress.COMPANION
            )(offer =>
              new DeliveryOfferView(
                offer.group.svc,
                offer.sender,
                s"TransferInProgress",
              )
            ),
            InterfaceImplementation(
              TestDeliveryOffer.COMPANION
            )(offer =>
              new DeliveryOfferView(
                offer.svc,
                offer.sender,
                "TestDeliveryOffer",
              )
            ),
          ),
        )
      ),
    )
  }

  private def mkStore(): Store =
    new InMemoryMultiDomainAcsStore(
      loggerFactory,
      txFilter,
      TestTxLogStoreParser,
      FutureSupervisor.Noop,
      RetryProvider(loggerFactory, timeouts),
    )(actorSystem.dispatcher)

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

    def switchToUpdates(offset: String = nextOffset)(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.switchToIngestingUpdates(domain, offset)

    def create[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        offset: String = nextOffset,
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.ingestUpdate(
        domain,
        TransactionTreeUpdate(
          mkCreateTx(
            offset,
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

    def removeTransferOutIfBootstrap(
        contractId: ContractId[_],
        transferId: String,
    )(implicit store: MultiDomainAcsStore) =
      store.ingestionSink.removeTransferOutIfBootstrap(
        contractId,
        TransferId(
          domain,
          transferId,
        ),
      )
  }

  private type Store = InMemoryMultiDomainAcsStore[TestTxLogIndexRecord, TestTxLogEntry]
  private type C = Contract[AppRewardCoupon.ContractId, AppRewardCoupon]
  private type CReady = ReadyContract[AppRewardCoupon.ContractId, AppRewardCoupon]

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

  private def assertReadyForTransferIn(transferId: TransferId, expected: Boolean)(implicit
      store: MultiDomainAcsStore
  ) =
    store.isReadyForTransferIn(transferId).map {
      _ shouldBe expected
    }

  private def assertTestState(
      contractStateEventsById: Map[ContractId[_], Long] = Map.empty,
      archivedTombstones: Set[ContractId[_]] = Set.empty,
      pendingTransfersById: Map[ContractId[_], NonEmpty[Set[TransferId]]] = Map.empty,
      bootstrapTransferOutIds: Set[TransferId] = Set.empty,
  )(implicit store: Store) =
    for {
      actualContractStateEventsById <- store.contractStateEventsById
      actualArchivedTombstones <- store.archivedTombstones
      actualPendingTransfersById <- store.pendingTransfersById
      actualBootstrapTransferOutIds <- store.bootstrapTransferOutIds
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
      clue("bootstrapTransferOutIds") {
        actualBootstrapTransferOutIds shouldBe bootstrapTransferOutIds
      }
    }

  private def c(i: Int): C = appRewardCoupon(i, svcParty)
  private def cFeatured(i: Int): C = appRewardCoupon(i, svcParty, true)

  def transferInProgress(
      i: Int
  ) =
    Contract(
      identifier = TransferInProgress.TEMPLATE_ID,
      contractId = new TransferInProgress.ContractId(s"#$i"),
      payload = new TransferInProgress(
        new Group(
          svcParty.toProtoPrimitive,
          svcParty.toProtoPrimitive,
          Seq.empty.asJava,
          new GroupId("mygroup"),
          svcParty.toProtoPrimitive,
          new RelTime(5),
        ),
        svcParty.toProtoPrimitive,
        Seq.empty.asJava,
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  def testDeliveryOffer(
      i: Int
  ) =
    Contract(
      identifier = TestDeliveryOffer.TEMPLATE_ID,
      contractId = new TestDeliveryOffer.ContractId(s"#$i"),
      payload = new TestDeliveryOffer(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        "",
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  def deliveryOffer(
      i: Int,
      description: String,
  ) =
    Contract(
      identifier = DeliveryOffer.TEMPLATE_ID,
      contractId = new DeliveryOffer.ContractId(s"#$i"),
      payload = new DeliveryOfferView(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        description,
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

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
        _ <- d1.removeTransferOutIfBootstrap(c(1).contractId, tf0)
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
        _ <- d1.acsAndTransferOuts(
          transferOuts = Seq(
            (c(1) -> d2, tf0)
          )
        )
        _ <- d1.switchToUpdates()
        _ = eventually()(transfers.get should have length 1)
        _ <- d2.switchToUpdates()
        _ <- d3.switchToUpdates()
        _ <- assertReadyForTransferIn(tf0Id, true)
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ <- assertReadyForTransferIn(tf0Id, false)
        // transfer out before transfer in
        _ <- d2.transferOut(c(1) -> d1, tf1)
        _ <- assertReadyForTransferIn(tf1Id, true)
        _ = eventually()(transfers.get should have length 2)
        _ <- d1.transferIn(c(1) -> d2, tf1)
        _ <- assertReadyForTransferIn(tf1Id, false)
        // transfer in before transfer out
        _ <- d3.transferIn(c(1) -> d1, tf2)
        _ <- assertReadyForTransferIn(tf2Id, false)
        _ <- d1.transferOut(c(1) -> d3, tf2)
        _ <- assertReadyForTransferIn(tf2Id, false)
        _ <- streamF
      } yield {
        transfers.get().map(TransferId.fromTransferOut(_)) shouldBe Seq(tf0Id, tf1Id)
      }
    }
    "remove completed transfer out events" in {
      implicit val store = mkStore()
      val tf0 = nextTransferId
      val tf0Id = TransferId(d1, tf0)
      for {
        _ <- d1.acsAndTransferOuts(
          transferOuts = Seq(
            (c(1) -> d2, tf0)
          )
        )
        _ <- assertReadyForTransferIn(tf0Id, true)
        _ <- d1.removeTransferOutIfBootstrap(c(1).contractId, tf0)
        _ <- assertReadyForTransferIn(tf0Id, false)
      } yield succeed
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
        _ <- d1.acsAndTransferOuts(
          acs = Seq(c(1)),
          transferOuts = Seq((c(2) -> d2, tf2)),
        )
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ = eventually()(readyContracts.get() should have size 1)
        // transfer out before transfer in
        _ <- d1.transferOut(c(1) -> d2, tf0)
        _ <- d2.transferIn(c(1) -> d1, tf0)
        _ = eventually()(readyContracts.get() should have size 2)
        // transfer in before transfer out
        _ <- d1.transferIn(c(1) -> d2, tf1)
        _ <- d2.transferOut(c(1) -> d1, tf1)
        _ = eventually()(readyContracts.get() should have size 3)
        // Complete transfer in of in-flight transfer out.
        _ <- d2.transferIn(c(2) -> d1, tf2)
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
    "interfaces" in {
      implicit val store = mkStore()
      for {
        _ <- d1.switchToUpdates()
        _ <- d1.create(transferInProgress(1))
        _ <- d1.create(testDeliveryOffer(2))
        cs <- store.listContracts(DeliveryOffer.INTERFACE)
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

    "return tx log entries in correct order" in {
      implicit val store = mkStore()
      val tx1Offset = "011"
      val tx2Offset = "012"
      val tx3Offset = "013"
      val tx4Offset = "014"

      val (validatorRewardCouponsForAcs, validatorRewardCouponsForTxs) =
        Seq(0, 1, 2, 3).map(i => mkValidatorRewardCoupon(i)).splitAt(2)
      val filteredAppRewardCoupons =
        Seq(0, 1, 3).map(i => appRewardCoupon(i + 100, mkPartyId(s"provider-$i")))
      val appRewardCouponsToArchive = Seq(2, 3).map(i => appRewardCoupon(i, providerParty(i)))

      val tx1: TransactionTree = mkTx(
        tx1Offset,
        Seq(
          filteredAppRewardCoupons.map(toCreatedEvent),
          appRewardCouponsToArchive.map(toArchivedEvent),
          validatorRewardCouponsForAcs.map(toCreatedEvent),
        ).flatten,
      )
      val tx2: TransactionTree = mkTx(
        tx2Offset,
        Seq(mkValidatorRewardCoupon(100))
          .map(toCreatedEvent)
          .appendedAll(filteredAppRewardCoupons.map(toArchivedEvent)),
      )
      val tx3: TransactionTree = mkCreateTx(tx3Offset, validatorRewardCouponsForTxs)
      val tx4: TransactionTree = mkCreateTx(tx4Offset, Seq(mkValidatorRewardCoupon(5)))

      val reader = new TxLogStore.Reader[TestTxLogIndexRecord, TestTxLogEntry](
        txLogStore = store,
        transactionTreeSource = TransactionTreeSource.StaticForTesting(Seq(tx1, tx2, tx3, tx4)),
      )
      val initialEventIdD1 = tx1.getRootEventIds.asScala.headOption.value
      val initialEventIdD2 = tx3.getRootEventIds.asScala.headOption.value
      for {
        _ <- d1.switchToUpdates()
        _ <- d2.switchToUpdates()
        _ <- store.ingestionSink.ingestUpdate(d1, TransactionTreeUpdate(tx1))
        _ <- store.ingestionSink.ingestUpdate(d1, TransactionTreeUpdate(tx2))
        _ <- store.ingestionSink.ingestUpdate(d2, TransactionTreeUpdate(tx3))
        indices <- store.getTxLogIndicesByOffset(0, 1000)
        entries <- reader.getTxLogByOffset(0, 1000)
        indices2 <- store.getTxLogIndicesByOffset(2, 3)
        entries2 <- reader.getTxLogByOffset(2, 3)

        d1Indices <- store.getTxLogIndicesAfterEventId(d1, initialEventIdD1, 10)
        d2Indices <- store.getTxLogIndicesAfterEventId(d2, initialEventIdD2, 10)
      } yield {
        // The test tx log creates one entry for each create event.
        // The test transactions only contain root nodes, the tx log should preserve their order.
        def createEvents(tx: TransactionTree): Seq[CreatedEvent] =
          tx.getRootEventIds.asScala.toList
            .collect(i =>
              tx.getEventsById.get(i) match {
                case c: CreatedEvent => c
              }
            )
        val expectedEventsInTxLog = Seq(tx1, tx2, tx3).flatMap(createEvents(_))
        val expectedEventIds = expectedEventsInTxLog.map(_.getEventId)

        indices.map(_.eventId) should contain theSameElementsInOrderAs expectedEventIds
        entries.map(_.payload) should contain theSameElementsInOrderAs expectedEventIds

        indices2.map(_.eventId) should contain theSameElementsInOrderAs expectedEventIds.slice(2, 5)
        entries2.map(_.payload) should contain theSameElementsInOrderAs expectedEventIds.slice(2, 5)

        d1Indices.map(_.eventId) should contain theSameElementsInOrderAs Seq(tx1, tx2)
          .flatMap(createEvents(_))
          .map(_.getEventId)
          .tail
        d2Indices.map(_.eventId) should contain theSameElementsInOrderAs Seq(tx3)
          .flatMap(createEvents(_))
          .map(_.getEventId)
          .tail
      }
    }

    "signals offsets as ingestion progresses" in {
      implicit val store = mkStore()

      val d1AcsOffset = "010"
      val d2AcsOffset = "010"
      val d1Tx1Offset = "012"
      val d2Tx1Offset = "012"

      val signalD1_ACS = store.signalWhenAcsCompletedOrShutdown(d1)

      val signalD1_010 = store.signalWhenIngestedOrShutdown(d1, "010")
      val signalD1_011 = store.signalWhenIngestedOrShutdown(d1, "011")
      val signalD1_012 = store.signalWhenIngestedOrShutdown(d1, "012")

      val signalD2_010 = store.signalWhenIngestedOrShutdown(d2, "010")
      val signalD2_011 = store.signalWhenIngestedOrShutdown(d2, "011")

      def notCompleted(futures: Future[Unit]*) =
        forAll(futures)(_.isCompleted shouldBe false)

      notCompleted(
        signalD1_ACS,
        signalD1_010,
        signalD1_011,
        signalD1_012,
        signalD2_010,
        signalD2_011,
      )

      for {
        _ <- d1.switchToUpdates(d1AcsOffset)
        _ <- signalD1_ACS
        _ <- signalD1_010
        _ = notCompleted(signalD1_011, signalD1_012, signalD2_010, signalD2_011)
        _ <- d1.create(c(1), d1Tx1Offset)
        _ <- signalD1_011
        _ <- signalD1_012
        _ = notCompleted(signalD2_010, signalD2_011)
        _ <- d2.switchToUpdates(d2AcsOffset)
        _ <- signalD2_010
        _ = notCompleted(signalD2_011)
        _ <- d2.create(c(2), d2Tx1Offset)
        _ <- signalD2_011
        // We can still register signals for already ingested offsets
        _ <- store.signalWhenAcsCompletedOrShutdown(d1)
        _ <- store.signalWhenIngestedOrShutdown(d1, "010")
      } yield succeed
    }
  }
}
