package com.daml.network.store

import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.codegen.java.cn.scripts.testwallet.TestDeliveryOffer
import com.daml.network.codegen.java.cn.splitwell.*
import com.daml.network.codegen.java.cn.wallet.payment.{DeliveryOffer, DeliveryOfferView}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.store.StoreTest.{TestTxLogEntry, TestTxLogIndexRecord}
import com.daml.network.util.{Contract, ContractWithState, AssignedContract}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.topology.DomainId
import com.google.protobuf
import org.scalatest.Assertion

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

abstract class MultiDomainAcsStoreTest[
    S <: MultiDomainAcsStore with TxLogStore[
      TestTxLogIndexRecord,
      TestTxLogEntry,
    ]
] extends StoreTest { this: HasActorSystem =>

  import MultiDomainAcsStore.*

  private var transferCounter = 0
  protected def nextReassignmentId: String = {
    val id = "%08d".format(transferCounter)
    transferCounter += 1
    id
  }

  protected val contractFilter: MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter

    MultiDomainAcsStore.SimpleContractFilter(
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

  protected def mkStore(id: Int = 0): Store

  protected type Store = S
  protected type C = Contract[AppRewardCoupon.ContractId, AppRewardCoupon]
  protected type CReady = AssignedContract[AppRewardCoupon.ContractId, AppRewardCoupon]

  protected def assertTestState(
      contractStateEventsById: Map[ContractId[_], ContractStateEvent] = Map.empty,
      incompleteTransfersById: Map[ContractId[_], NonEmpty[Set[ReassignmentId]]] = Map.empty,
  )(implicit store: Store): Future[Assertion]

  protected def assertList(
      expected: (C, Option[DomainId])*
  )(implicit store: MultiDomainAcsStore) = {
    val expected_ = expected.map {
      case (c, None) => ContractWithState(c, ContractState.InFlight)
      case (c, Some(id)) => ContractWithState(c, ContractState.Assigned(id))
    }
    for {
      actualList <- store.listContracts(
        AppRewardCoupon.COMPANION,
        limit = HardLimit(expected.size.toLong),
      )
      _ = actualList shouldBe expected_
      _ <- expected_.traverse_ { c =>
        store
          .lookupContractById(AppRewardCoupon.COMPANION)(c.contract.contractId)
          .map(_ shouldBe Some(c))
      }
    } yield succeed
  }

  protected def assertLookupNone(c: C)(implicit store: MultiDomainAcsStore) =
    store.lookupContractById(AppRewardCoupon.COMPANION)(c.contractId)

  protected def assertReadyForAssign(
      contractId: ContractId[_],
      reassignmentId: ReassignmentId,
      expected: Boolean,
  )(implicit
      store: MultiDomainAcsStore
  ) =
    store.isReadyForAssign(contractId, reassignmentId).map {
      _ shouldBe expected
    }

  protected def c(i: Int): C = appRewardCoupon(i, svcParty)
  protected def cFeatured(i: Int): C = appRewardCoupon(i, svcParty, true)

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

  protected val d1: DomainId = DomainId.tryFromString("domain1::domain")
  protected val d2: DomainId = DomainId.tryFromString("domain2::domain")
  protected val d3: DomainId = DomainId.tryFromString("domain3::domain")

  "MultiDomainAcsStore" should {
    "ingestion initialization is idempotent" in {
      implicit val store = mkStore()
      for {
        _ <- store.ingestionSink.initialize()
        _ <- store.ingestionSink.initialize()
      } yield succeed
    }
    "initial store is empty" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- assertList()
      } yield succeed
    }
    "single domain creates and archives" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
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
    "ingestion can be restarted at any time" in {
      implicit val store = mkStore()
      for {
        _ <- acs(Seq((c(1), d1, 0L)))
        _ <- store.ingestionSink.initialize()
        _ <- d1.create(c(2))
        _ <- store.ingestionSink.initialize()
        _ <- assertList(c(1) -> Some(d1), c(2) -> Some(d1))
      } yield succeed
    }
    "respect the limit and log a warning for HardLimit" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- d1.create(c(1))
        _ <- d1.create(c(2))
        resultHard <- loggerFactory.assertLogs(
          store.listContracts(AppRewardCoupon.COMPANION, limit = HardLimit(1)),
          _.warningMessage should include(
            "Size of the result exceeded the limit. Result size: 2. Limit: 1"
          ),
        )
        resultPage <- store.listContracts(AppRewardCoupon.COMPANION, limit = PageLimit(1))
      } yield {
        resultHard should have size 1
        resultPage should have size 1
      }
    }
    "single domain acs" in {
      implicit val store = mkStore()
      for {
        _ <- acs(
          acs = Seq((c(1), d1, 0L))
        )
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertLookupNone(c(1))
        _ <- assertList()
        _ <- assertTestState()
      } yield succeed
    }

    "filtering of acs" in {
      implicit val store = mkStore()
      for {
        _ <- acs(
          Seq((c(1), d1, 0L), (cFeatured(2), d1, 0L))
        )
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertTestState()
      } yield succeed
    }
    "filtering of create" in {
      implicit val store = mkStore()
      for {
        _ <- acs()
        _ <- d1.create(c(1))
        _ <- d1.create(cFeatured(2))
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertTestState()
      } yield succeed
    }
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
    "stream ready contracts on single domain" in {
      implicit val store = mkStore()
      val assignedContracts = new AtomicReference(Seq.empty[CReady])
      val streamF = store
        .streamAssignedContracts(AppRewardCoupon.COMPANION)
        .take(3)
        .runForeach { assignedContract =>
          assignedContracts.updateAndGet(_.appended(assignedContract))
        }
      def r(round: Int) = AssignedContract(c(round), d1)
      for {
        _ <- acs(Seq((c(1), d1, 0L)))
        _ = eventually()(assignedContracts.get() shouldBe Seq(r(1)))
        _ <- d1.create(c(2))
        _ = eventually()(assignedContracts.get() shouldBe Seq(r(1), r(2)))
        _ <- d1.create(c(3))
        _ = eventually()(assignedContracts.get() shouldBe Seq(r(1), r(2), r(3)))
        _ <- streamF
      } yield succeed
    }
    "stream ready contracts after ingestion restart" in {
      implicit val store = mkStore()
      val assignedContracts = new AtomicReference(Seq.empty[CReady])
      val streamF = store
        .streamAssignedContracts(AppRewardCoupon.COMPANION)
        .take(3)
        .runForeach { assignedContract =>
          assignedContracts.updateAndGet(_.appended(assignedContract))
        }
      def r(round: Int) = AssignedContract(c(round), d1)
      for {
        _ <- acs(Seq((c(1), d1, 0L)))
        _ = eventually()(assignedContracts.get() shouldBe Seq(r(1)))
        _ <- d1.create(c(2))
        _ <- store.ingestionSink.initialize()
        _ = eventually()(assignedContracts.get() shouldBe Seq(r(1), r(2)))
        _ <- store.ingestionSink.initialize()
        _ <- d1.create(c(3))
        _ = eventually()(assignedContracts.get() shouldBe Seq(r(1), r(2), r(3)))
        _ <- streamF
      } yield succeed
    }
  }
}
