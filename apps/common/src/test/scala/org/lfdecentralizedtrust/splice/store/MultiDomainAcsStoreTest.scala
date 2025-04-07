package org.lfdecentralizedtrust.splice.store

import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.daml.lf.data.Time
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.AppRewardCoupon
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.AppPaymentRequest
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent
import org.lfdecentralizedtrust.splice.store.db.{AcsRowData, IndexColumnValue}
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract, ContractWithState}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.util.MonadUtil

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

abstract class MultiDomainAcsStoreTest[
    S <: MultiDomainAcsStore
] extends StoreTest { this: HasActorSystem =>

  import MultiDomainAcsStore.*

  private val upgradedPackageId = "upgradedpackageid"

  private var transferCounter = 0
  protected def nextReassignmentId: String = {
    val id = "%08d".format(transferCounter)
    transferCounter += 1
    id
  }

  case class GenericAcsRowData(contract: Contract[_, _]) extends AcsRowData {
    override def contractExpiresAt: Option[Time.Timestamp] = None

    override def indexColumns: Seq[(String, IndexColumnValue[_])] = Seq.empty
  }

  protected val defaultContractFilter: MultiDomainAcsStore.ContractFilter[GenericAcsRowData] = {
    import MultiDomainAcsStore.mkFilter

    MultiDomainAcsStore.SimpleContractFilter(
      dsoParty,
      templateFilters = Map(
        mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured) { contract =>
          GenericAcsRowData(contract)
        }
      ),
    )
  }

  protected def mkStore(
      acsId: Int = 0,
      txLogId: Option[Int] = Some(1),
      domainMigrationId: Long = 0,
      participantId: ParticipantId = ParticipantId("MultiDomainAcsStoreTest"),
      filter: MultiDomainAcsStore.ContractFilter[GenericAcsRowData] = defaultContractFilter,
  ): Store

  protected type Store = S
  protected type C = Contract[AppRewardCoupon.ContractId, AppRewardCoupon]
  protected type CReady = AssignedContract[AppRewardCoupon.ContractId, AppRewardCoupon]

  protected def assertIncompleteReassignments(
      incompleteReassignmentsById: Map[ContractId[_], NonEmpty[Set[ReassignmentId]]] = Map.empty
  )(implicit store: Store) =
    for {
      actualIncompleteReassignmentsById <- store.listIncompleteReassignments()
    } yield {
      clue("incompleteTransfersById") {
        actualIncompleteReassignmentsById shouldBe incompleteReassignmentsById
      }
    }

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
        limit = HardLimit.tryCreate(expected.size.max(1)),
      )
      _ = actualList shouldBe expected_
      _ <- expected_.traverse_ { c =>
        store
          .lookupContractById(AppRewardCoupon.COMPANION)(c.contract.contractId)
          .map(_ shouldBe Some(c))
      }
      _ <- expected_.traverse_ { c =>
        store
          .lookupContractStateById(c.contract.contractId)
          .map(_ shouldBe Some(c.state))
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

  protected def c(i: Int): C = appRewardCoupon(i, dsoParty, contractId = validContractId(i))
  protected def cUpgraded(i: Int): C = {
    val coupon = appRewardCoupon(i, dsoParty, contractId = validContractId(i))
    coupon.copy(
      identifier = new Identifier(
        upgradedPackageId,
        coupon.identifier.getModuleName,
        coupon.identifier.getEntityName,
      )
    )
  }
  private val charsetMatchingDbBytes = java.nio.charset.Charset forName "UTF-8"

  protected def reassignmentContractOrder[AC](
      unordered: Seq[AC],
      participantId: ParticipantId,
  )(getContractId: AC => String = (_: Contract.Has[?, ?]).contractId.contractId): Seq[AC] =
    if (unordered.sizeIs <= 1) unordered
    else {
      val pidBytes = participantId.toProtoPrimitive getBytes charsetMatchingDbBytes
      val md = java.security.MessageDigest getInstance "MD5"
      unordered
        .map { ac =>
          md.update(getContractId(ac) getBytes charsetMatchingDbBytes)
          // in postgres bytea comparison, \x3132 < \xc3a9 (i.e. bytewise
          // comparison is unsigned); Arrays.compare on byte[] incompatibly
          // compares signed bytes, e.g. yielding \x3132 > \xc3a9.  Pretend we
          // have unsigned bytes to match the DB memory store's sorting behavior
          val digest = md.digest(pidBytes).map(b => if (b < 0) 256 + b else b.toInt)
          md.reset()
          (digest, ac)
        }
        .sortBy(_._1)(java.util.Arrays.compare(_, _))
        .map(_._2)
    }

  protected def cFeatured(i: Int): C =
    appRewardCoupon(i, dsoParty, true, contractId = validContractId(i))

  def transferInProgress(
      i: Int,
      paymentRequest: AppPaymentRequest.ContractId,
  ) =
    contract(
      identifier = TransferInProgress.TEMPLATE_ID_WITH_PACKAGE_ID,
      contractId = new TransferInProgress.ContractId(s"#$i"),
      payload = new TransferInProgress(
        new Group(
          dsoParty.toProtoPrimitive,
          dsoParty.toProtoPrimitive,
          Seq.empty.asJava,
          new GroupId("mygroup"),
          dsoParty.toProtoPrimitive,
          new RelTime(5),
        ),
        dsoParty.toProtoPrimitive,
        Seq.empty.asJava,
        paymentRequest,
      ),
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
        _ <- initWithAcs()
        _ <- assertList()
      } yield succeed
    }
    "single domain creates and archives" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs()
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
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "paginate asc and desc" in {
      val contracts = (1 to 10).map(n => c(n))
      implicit val store: S = mkStore()
      def paginate(after: Option[Long], acc: Seq[C], sortOrder: SortOrder): Future[Seq[C]] = {
        store
          .listContractsPaginated(
            AppRewardCoupon.COMPANION,
            after,
            PageLimit.tryCreate(1),
            sortOrder,
          )
          .flatMap { resultsPage =>
            resultsPage.nextPageToken match {
              case Some(nextPageToken) =>
                paginate(
                  Some(nextPageToken),
                  acc ++ resultsPage.resultsInPage.map(_.contract),
                  sortOrder,
                )
              case None =>
                Future.successful(acc)
            }
          }
      }
      for {
        _ <- initWithAcs()
        _ <- store.ingestionSink.initialize()
        _ <- MonadUtil.sequentialTraverse(contracts)(d1.create(_))
        paginatedResultsAsc <- paginate(None, Seq.empty, SortOrder.Ascending)
        paginatedResultsDesc <- paginate(None, Seq.empty, SortOrder.Descending)
      } yield {
        paginatedResultsAsc.map(_.contractId) should be(contracts.map(_.contractId))
        paginatedResultsDesc.map(_.contractId) should be(contracts.map(_.contractId).reverse)
      }
    }
    "throws if the store primary party is not a stakeholder of the created event" in {
      implicit val store = mkStore()
      recoverToSucceededIf[IllegalStateException] {
        for {
          _ <- initWithAcs()
          _ <- assertList()
          _ <- d1.create(c(1), createdEventSignatories = Seq(userParty(1)))
        } yield succeed
      }
    }
    "ingestion can be restarted at any time" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs(Seq((c(1), d1, 0L)))
        _ <- store.ingestionSink.initialize()
        _ <- d1.create(c(2))
        _ <- store.ingestionSink.initialize()
        _ <- assertList(c(1) -> Some(d1), c(2) -> Some(d1))
      } yield succeed
    }
    "respect the limit and log a warning for HardLimit" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs()
        _ <- d1.create(c(1))
        _ <- d1.create(c(2))
        resultHard <- loggerFactory.assertLogs(
          store.listContracts(AppRewardCoupon.COMPANION, limit = HardLimit.tryCreate(1)),
          _.warningMessage should include(
            "Size of the result exceeded the limit"
          ).and(include("Result size: 2. Limit: 1")),
        )
        resultPage <- store.listContracts(AppRewardCoupon.COMPANION, limit = PageLimit.tryCreate(1))
      } yield {
        resultHard should have size 1
        resultPage should have size 1
      }
    }
    "single domain acs" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs(
          activeContracts = Seq((c(1), d1, 0L))
        )
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertLookupNone(c(1))
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }

    "filtering of acs" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs(
          Seq((c(1), d1, 0L), (cFeatured(2), d1, 0L))
        )
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "filtering of create" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs()
        _ <- d1.create(c(1))
        _ <- d1.create(cFeatured(2))
        _ <- assertList(c(1) -> Some(d1))
        _ <- d1.archive(c(1))
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "signals offsets as ingestion progresses" in {
      implicit val store = mkStore()

      val acsOffset = 10L
      val tx1Offset = 12L

      val signal_010 = store.signalWhenIngestedOrShutdown(10L)
      val signal_011 = store.signalWhenIngestedOrShutdown(11L)
      val signal_012 = store.signalWhenIngestedOrShutdown(12L)

      def notCompleted(futures: Future[Unit]*) =
        forAll(futures)(_.isCompleted shouldBe false)

      notCompleted(
        signal_010,
        signal_011,
        signal_012,
      )

      for {
        _ <- initWithAcs(acsOffset = acsOffset)
        _ <- signal_010
        _ = notCompleted(signal_011, signal_012)
        _ <- d1.create(c(1), tx1Offset)
        _ <- signal_011
        _ <- signal_012
        // We can still register signals for already ingested offsets
        _ <- store.signalWhenIngestedOrShutdown(10L)
      } yield succeed
    }

    "unassign before assign" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextReassignmentId
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList(c(1) -> None)
        _ <- assertIncompleteReassignments(
          Map(c(1).contractId -> NonEmpty(Set, ReassignmentId(d1, tf0)))
        )
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
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextReassignmentId
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- assertIncompleteReassignments(
          Map(c(1).contractId -> NonEmpty(Set, ReassignmentId(d1, tf0)))
        )
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
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(c(1))
        _ <- assertList(c(1) -> Some(d1))
        tf0 = nextReassignmentId
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ <- assertList(c(1) -> Some(d2))
        _ <- assertIncompleteReassignments(
          Map(c(1).contractId -> NonEmpty(Set, ReassignmentId(d1, tf0)))
        )
        _ <- d2.archive(c(1))
        _ <- assertList()
        _ <- assertIncompleteReassignments(
          Map(c(1).contractId -> NonEmpty(Set, ReassignmentId(d1, tf0)))
        )
        _ <- assertLookupNone(c(1))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertList()
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "assign before create" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs()
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
        _ <- initWithAcs()
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
        _ <- initWithAcs()
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
        _ <- initWithAcs()
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
        _ <- initWithAcs(
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
        _ <- initWithAcs(
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
        _ <- initWithAcs()
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
        _ <- initWithAcs()
        _ <- d1.create(cFeatured(1))
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- assertIncompleteReassignments()
      } yield succeed
    }
    "filtering of incomplete unassigns" in {
      implicit val store = mkStore()
      val tf0 = nextReassignmentId
      for {
        _ <- initWithAcs(
          incompleteOut = Seq((cFeatured(1), d1, d2, tf0, 1L))
        )
        _ <- assertIncompleteReassignments()
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
        _ <- initWithAcs(Seq((c(1), d1, 0L)))
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
        _ <- initWithAcs(Seq((c(1), d1, 0L)))
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
        _ <- initWithAcs(
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
    "stream assigned contracts" in {
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
      def assertSize(label: String, expected: Long) = {
        clue(label) {
          eventually()(assignedContracts.get() should have size expected)
        }
      }
      for {
        _ <- initWithAcs(
          activeContracts = Seq((c(1), d1, 0L)),
          incompleteOut = Seq((c(2), d1, d2, tf2, 3L)),
        )
        _ = assertSize("Initial", 1)
        // unassign before assign
        _ <- d1.unassign(c(1) -> d2, tf0, 1)
        _ <- d2.assign(c(1) -> d1, tf0, 1)
        _ = assertSize("unassign before assign", 2)
        // assign before unassign
        _ <- d1.assign(c(1) -> d2, tf1, 2)
        _ <- d2.unassign(c(1) -> d1, tf1, 2)
        _ = assertSize("assign before unassign", 3)
        // complete assign of incomplete unassign.
        _ <- d2.assign(c(2) -> d1, tf2, 3)
        _ = assertSize("complete assign of incomplete unassign", 4)
        // create
        _ <- d2.create(c(3))
        _ = assertSize("create", 5)
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

    "reads return contracts from all package versions" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs(Seq((c(1), d1, 0L), (cUpgraded(2), d1, 0L)))
        _ <- d1.create(c(3))
        _ <- d1.create(cUpgraded(4))
        results <- store.listContracts(AppRewardCoupon.COMPANION)
        _ = results should have size 4
        _ = forExactly(2, results) { c =>
          c.contract.identifier.getPackageId shouldBe AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID.getPackageId
        }
        _ = forExactly(2, results) { c =>
          c.contract.identifier.getPackageId shouldBe upgradedPackageId
        }
      } yield succeed
    }

    "have 900-1000 test contracts for the mismatch test" in {
      MultiDomainAcsStoreTest.generatedCoids.value.size should (be >= 900 and be <= 1000)
    }

    "read assignment-mismatched contracts in a stable order" in {
      import com.digitalasset.daml.lf.value.Value
      import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
        FeaturedAppRight,
        AppRewardCoupon,
      }
      import MultiDomainAcsStore.ConstrainedTemplate

      // the specific templates don't matter, we just need 2 of them
      val sampleParticipantId = ParticipantId("foo")
      val evenCompanion = FeaturedAppRight.COMPANION
      val oddCompanion = AppRewardCoupon.COMPANION
      def smallestContract(coid: Value.ContractId, ix: Int) =
        if (ix % 2 == 0) featuredAppRight(dsoParty, coid.coid)
        else appRewardCoupon(1, dsoParty, contractId = coid.coid)
      val coids = MultiDomainAcsStoreTest.generatedCoids.value
      val expectedOrder = reassignmentContractOrder(
        coids,
        sampleParticipantId,
      )(_.coid)

      val contractFilter = {
        import MultiDomainAcsStore.mkFilter

        MultiDomainAcsStore.SimpleContractFilter(
          dsoParty,
          templateFilters = Map(
            mkFilter(evenCompanion)(_ => true)(GenericAcsRowData(_)),
            mkFilter(oddCompanion)(_ => true)(GenericAcsRowData(_)),
          ),
        )
      }
      implicit val store: Store = mkStore(0, Some(0), 0L, sampleParticipantId, contractFilter)
      for {
        _ <- initWithAcs(coids.zipWithIndex.map { case (coid, ix) =>
          (smallestContract(coid, ix), dummyDomain, 0L)
        })
        contracts <- store.listAssignedContractsNotOnDomainN(
          dummy2Domain,
          Seq[ConstrainedTemplate](evenCompanion, oddCompanion),
        )
      } yield contracts.map(_.contractId.contractId) shouldBe expectedOrder.map(_.coid)
    }
  }
}

private[store] object MultiDomainAcsStoreTest {
  import org.scalacheck.Gen
  import com.digitalasset.daml.lf.value.Value
  import com.digitalasset.daml.lf.value.test.ValueGenerators.comparableCoidsGen

  private[this] val coidsGen = comparableCoidsGen match {
    case a +: b +: cs => Gen.oneOf(a, b, cs*)
    case Seq(a) => a
    // should never be reached
    case _ => throw new IllegalStateException("no contract ID generator present")
  }

  // we want the same sequence of contract IDs without writing it out
  // between test runs
  private[this] val chosenByFairDiceRoll = org.scalacheck.rng.Seed(-4003657415693691769L)
  private val generatedCoids = Gen
    .containerOfN[Set, Value.ContractId](1000, coidsGen)
    .map(_.toSeq)
    .apply(Gen.Parameters.default, chosenByFairDiceRoll)
}
