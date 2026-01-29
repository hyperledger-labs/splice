package org.lfdecentralizedtrust.splice.store

import cats.syntax.parallel.*
import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.digitalasset.daml.lf.data.Time
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, AppRewardCoupon}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.AppPaymentRequest
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsRowData,
  IndexColumnValue,
}
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract, ContractWithState}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  allocationv1,
  holdingv1,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.test.dummyholding.DummyHolding
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.test.dummytwointerfaces.DummyTwoInterfaces
import org.lfdecentralizedtrust.splice.migration.MigrationTimeInfo
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

abstract class MultiDomainAcsStoreTest[
    S <: MultiDomainAcsStore
] extends StoreTest { this: HasActorSystem =>

  import MultiDomainAcsStore.*

  private var transferCounter = 0
  protected def nextReassignmentId: String = {
    val id = "%08d".format(transferCounter)
    transferCounter += 1
    id
  }

  case class GenericAcsRowData(contract: Contract[?, ?]) extends AcsRowData.AcsRowDataFromContract {
    override def contractExpiresAt: Option[Time.Timestamp] = None

    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
  }
  object GenericAcsRowData {
    implicit val hasIndexColumns: HasIndexColumns[GenericAcsRowData] =
      new HasIndexColumns[GenericAcsRowData] {
        override def indexColumnNames: Seq[String] = Seq.empty
      }
  }

  case class GenericInterfaceRowData(
      override val interfaceId: Identifier,
      override val interfaceView: DamlRecord[?],
  ) extends AcsInterfaceViewRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
  }
  object GenericInterfaceRowData {
    implicit val hasIndexColumns: HasIndexColumns[GenericInterfaceRowData] =
      new HasIndexColumns[GenericInterfaceRowData] {
        override def indexColumnNames: Seq[String] = Seq.empty
      }
  }

  protected val defaultContractFilter: MultiDomainAcsStore.ContractFilter[
    GenericAcsRowData,
    GenericInterfaceRowData,
  ] = {
    import MultiDomainAcsStore.mkFilter

    MultiDomainAcsStore.SimpleContractFilter(
      dsoParty,
      templateFilters = Map(
        mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured) { contract =>
          GenericAcsRowData(contract)
        }
      ),
      interfaceFilters = Map(
        // will include both Amulet & DummyHolding
        mkFilterInterface(holdingv1.Holding.INTERFACE) { contract =>
          contract.payload.instrumentId.admin == dsoParty.toProtoPrimitive
        }(contract =>
          GenericInterfaceRowData(
            contract.identifier,
            contract.payload,
          )
        ),
        mkFilterInterface(allocationrequestv1.AllocationRequest.INTERFACE) { contract =>
          contract.payload.settlement.executor == dsoParty.toProtoPrimitive
        }(contract =>
          GenericInterfaceRowData(
            contract.identifier,
            contract.payload,
          )
        ),
      ),
    )
  }

  protected def mkStore(
      acsId: Int = 0,
      txLogId: Option[Int] = Some(1),
      domainMigrationId: Long = 0,
      participantId: ParticipantId = ParticipantId("MultiDomainAcsStoreTest"),
      filter: MultiDomainAcsStore.ContractFilter[
        GenericAcsRowData,
        GenericInterfaceRowData,
      ] = defaultContractFilter,
      migrationTimeInfo: Option[MigrationTimeInfo] = None,
  ): Store

  protected type Store = S
  protected type C = Contract[AppRewardCoupon.ContractId, AppRewardCoupon]
  protected type CReady = AssignedContract[AppRewardCoupon.ContractId, AppRewardCoupon]

  protected def assertIncompleteReassignments(
      incompleteReassignmentsById: Map[ContractId[?], NonEmpty[Set[ReassignmentId]]] = Map.empty
  )(implicit store: Store) =
    for {
      actualIncompleteReassignmentsById <- store.listIncompleteReassignments()
    } yield {
      clue("incompleteTransfersById") {
        actualIncompleteReassignmentsById shouldBe incompleteReassignmentsById
      }
    }

  protected def assertList(
      expected: (C, Option[SynchronizerId])*
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
      _ <- expected_.parTraverse_ { c =>
        store
          .lookupContractById(AppRewardCoupon.COMPANION)(c.contract.contractId)
          .map(_ shouldBe Some(c))
      }
      _ <- expected_.parTraverse_ { c =>
        store
          .lookupContractStateById(c.contract.contractId)
          .map(_ shouldBe Some(c.state))
      }
    } yield succeed
  }

  protected def assertLookupNone(c: C)(implicit store: MultiDomainAcsStore) =
    store.lookupContractById(AppRewardCoupon.COMPANION)(c.contractId)

  protected def assertReadyForAssign(
      contractId: ContractId[?],
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
        upgradedAppRewardCouponPackageId,
        coupon.identifier.getModuleName,
        coupon.identifier.getEntityName,
      )
    )
  }
  private val charsetMatchingDbBytes = java.nio.charset.Charset forName "UTF-8"

  protected def dummyHolding(owner: PartyId, amount: BigDecimal, issuer: PartyId) = {
    val templateId = DummyHolding.TEMPLATE_ID
    val payload =
      new DummyHolding(owner.toProtoPrimitive, issuer.toProtoPrimitive, amount.bigDecimal)
    contract(
      identifier = templateId,
      contractId = new DummyHolding.ContractId(nextCid()),
      payload = payload,
    )
  }

  protected def twoInterfaces(
      owner: PartyId,
      amount: BigDecimal,
      issuer: PartyId,
      date: Instant,
  ) = {
    val templateId = DummyTwoInterfaces.TEMPLATE_ID
    val payload =
      new DummyTwoInterfaces(owner.toProtoPrimitive, issuer.toProtoPrimitive, numeric(amount), date)
    contract(
      identifier = templateId,
      contractId = new DummyTwoInterfaces.ContractId(nextCid()),
      payload = payload,
    )
  }

  protected val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
  protected def holdingView(owner: PartyId, amount: BigDecimal, admin: PartyId, id: String) = {
    new holdingv1.HoldingView(
      owner.toProtoPrimitive,
      new holdingv1.InstrumentId(admin.toProtoPrimitive, id),
      numeric(amount.bigDecimal),
      java.util.Optional.empty,
      emptyMetadata,
    )
  }

  protected def allocationRequestView(executor: PartyId, date: Instant) = {
    new allocationrequestv1.AllocationRequestView(
      new allocationv1.SettlementInfo(
        executor.toProtoPrimitive,
        new allocationv1.Reference("ref", java.util.Optional.empty),
        date,
        date,
        date,
        emptyMetadata,
      ),
      java.util.Map.of(),
      emptyMetadata,
    )
  }

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

  protected val d1: SynchronizerId = SynchronizerId.tryFromString("domain1::domain")
  protected val d2: SynchronizerId = SynchronizerId.tryFromString("domain2::domain")
  protected val d3: SynchronizerId = SynchronizerId.tryFromString("domain3::domain")

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
        _ <- initWithAcs(Seq(StoreTest.AcsImportEntry(c(1), d1, 0L)))
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
          activeContracts = Seq(StoreTest.AcsImportEntry(c(1), d1, 0L))
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
          Seq(
            StoreTest.AcsImportEntry(c(1), d1, 0L),
            StoreTest.AcsImportEntry(cFeatured(2), d1, 0L),
          )
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
            StoreTest.AcsImportIncompleteEntry(c(1), d1, d2, tf0, 1L)
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
            StoreTest.AcsImportIncompleteEntry(c(1), d1, d2, tf0, 1L)
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
          incompleteOut = Seq(StoreTest.AcsImportIncompleteEntry(cFeatured(1), d1, d2, tf0, 1L))
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
        _ <- initWithAcs(Seq(StoreTest.AcsImportEntry(c(1), d1, 0L)))
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
        _ <- initWithAcs(Seq(StoreTest.AcsImportEntry(c(1), d1, 0L)))
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
            StoreTest.AcsImportIncompleteEntry(c(1), d1, d2, tf0, 1L)
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
          activeContracts = Seq(StoreTest.AcsImportEntry(c(1), d1, 0L)),
          incompleteOut = Seq(StoreTest.AcsImportIncompleteEntry(c(2), d1, d2, tf2, 3L)),
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
        _ <- initWithAcs(
          Seq(
            StoreTest.AcsImportEntry(c(1), d1, 0L),
            StoreTest.AcsImportEntry(cUpgraded(2), d1, 0L),
          )
        )
        _ <- d1.create(c(3))
        _ <- d1.create(cUpgraded(4))
        results <- store.listContracts(AppRewardCoupon.COMPANION)
        _ = results should have size 4
        _ = forExactly(2, results) { c =>
          c.contract.identifier.getPackageId shouldBe AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID.getPackageId
        }
        _ = forExactly(2, results) { c =>
          c.contract.identifier.getPackageId shouldBe upgradedAppRewardCouponPackageId
        }
      } yield succeed
    }

    "have 900-1000 test contracts for the mismatch test" in {
      MultiDomainAcsStoreTest.generatedCoids.value.size should (be >= 900 and be <= 1000)
    }

    "ingest and return interface views for 1 interface 2 implementors" in {
      implicit val store = mkStore()
      val aDifferentIssuer = providerParty(42)
      val includedAmulet =
        (1 to 3).map(n =>
          amulet(providerParty(n), BigDecimal(n), n.toLong, BigDecimal(0.00001)) -> holdingView(
            providerParty(n),
            BigDecimal(n),
            dsoParty,
            "AMT",
          )
        )
      val excludedAmulet = (4 to 6).map(n =>
        amulet(
          providerParty(n),
          BigDecimal(n),
          n.toLong,
          BigDecimal(0.00001),
          dso = aDifferentIssuer, // this is excluded in the interface filter
        ) -> holdingView(providerParty(n), BigDecimal(n), aDifferentIssuer, "AMT")
      )
      val includedDummy = (1 to 3).map(n =>
        dummyHolding(providerParty(n), BigDecimal(n), dsoParty) -> holdingView(
          providerParty(n),
          BigDecimal(n),
          dsoParty,
          "DUM",
        )
      )
      val excludedDummy = (4 to 6).map(n =>
        dummyHolding(
          providerParty(n),
          BigDecimal(n),
          issuer = aDifferentIssuer, // this is excluded in the interface filter
        ) -> holdingView(providerParty(n), BigDecimal(n), aDifferentIssuer, "DUM")
      )
      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- MonadUtil.sequentialTraverse(
          includedAmulet ++ excludedAmulet
        ) { case (contract, interfaceView) =>
          d1.create(
            contract,
            implementedInterfaces =
              Map(holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> interfaceView.toValue),
          )
        }
        _ <- MonadUtil.sequentialTraverse(
          includedDummy ++ excludedDummy
        ) { case (contract, interfaceView) =>
          d1.create(
            contract,
            implementedInterfaces =
              Map(holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> interfaceView.toValue),
          )
        }
        result <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
      } yield {
        result should be((includedAmulet ++ includedDummy).map { case (contract, view) =>
          Contract(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
            new holdingv1.Holding.ContractId(contract.contractId.contractId),
            view,
            contract.createdEventBlob,
            contract.createdAt,
          )
        })
      }
    }

    "only include interfaces for the current store id and migration id" in {
      val storeI1M1 = mkStore(1, domainMigrationId = 1L)
      val storeI2M1 = mkStore(2, domainMigrationId = 1L)
      val storeI1M2 = mkStore(1, domainMigrationId = 2L)
      val contractI1M1 = amulet(providerParty(1), BigDecimal(1), 1.toLong, BigDecimal(0.00001))
      val viewI1M1 = holdingView(
        providerParty(1),
        BigDecimal(1),
        dsoParty,
        "Amulet",
      )
      val contractI2M1 = amulet(providerParty(2), BigDecimal(2), 2.toLong, BigDecimal(0.00002))
      val viewI2M1 = holdingView(
        providerParty(2),
        BigDecimal(2),
        dsoParty,
        "Amulet",
      )
      val contractI1M2 = amulet(providerParty(3), BigDecimal(3), 3.toLong, BigDecimal(0.00003))
      val viewI1M2 = holdingView(
        providerParty(3),
        BigDecimal(3),
        dsoParty,
        "Amulet",
      )
      for {
        _ <- initWithAcs()(storeI1M1)
        _ <- initWithAcs()(storeI2M1)
        _ <- initWithAcs()(storeI1M2)
        _ <- d1.create(
          contractI1M1,
          implementedInterfaces = Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> viewI1M1.toValue
          ),
        )(storeI1M1)
        _ <- d1.create(
          contractI2M1,
          implementedInterfaces = Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> viewI2M1.toValue
          ),
        )(storeI2M1)
        _ <- d1.create(
          contractI1M2,
          implementedInterfaces = Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> viewI1M2.toValue
          ),
        )(storeI1M2)
        resultStoreI1M1 <- storeI1M1.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
        resultStoreI2M1 <- storeI2M1.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
        resultStoreI1M2 <- storeI1M2.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
      } yield {
        resultStoreI1M1 should be(
          Seq(
            Contract(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
              new holdingv1.Holding.ContractId(contractI1M1.contractId.contractId),
              viewI1M1,
              contractI1M1.createdEventBlob,
              contractI1M1.createdAt,
            )
          )
        )
        resultStoreI2M1 should be(
          Seq(
            Contract(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
              new holdingv1.Holding.ContractId(contractI2M1.contractId.contractId),
              viewI2M1,
              contractI2M1.createdEventBlob,
              contractI2M1.createdAt,
            )
          )
        )
        resultStoreI1M2 should be(
          Seq(
            Contract(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
              new holdingv1.Holding.ContractId(contractI1M2.contractId.contractId),
              viewI1M2,
              contractI1M2.createdEventBlob,
              contractI1M2.createdAt,
            )
          )
        )
      }
    }

    "filter interfaces by package-id" in {
      implicit val store = mkStore()
      val contract = amulet(providerParty(1), BigDecimal(1), 1.toLong, BigDecimal(0.00001))
      val view = holdingView(
        providerParty(1),
        BigDecimal(1),
        dsoParty,
        "AMT",
      )
      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(
          contract,
          implementedInterfaces = Map(
            new Identifier(
              "a package id that is totally different from the original",
              holdingv1.Holding.INTERFACE_ID.getModuleName,
              holdingv1.Holding.INTERFACE_ID.getEntityName,
            ) -> view.toValue
          ),
        )
        result <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
      } yield {
        result should be(Seq.empty)
      }
    }

    "ingest and return interface views for 2 interfaces 1 implementor" in {
      implicit val store = mkStore()
      val aDifferentIssuer = providerParty(42)
      val included = (1 to 3).map(n => {
        val date = Instant.EPOCH.plusSeconds(n.toLong)
        val owner = providerParty(n)
        (
          twoInterfaces(
            owner,
            BigDecimal(n),
            dsoParty,
            date,
          ),
          holdingView(owner, BigDecimal(n), dsoParty, "AMT"),
          allocationRequestView(dsoParty, date),
        )
      })
      val excluded = (4 to 6).map(n => {
        val date = Instant.EPOCH.plusSeconds(n.toLong)
        val owner = providerParty(n)
        (
          twoInterfaces(
            owner,
            BigDecimal(n),
            aDifferentIssuer, // this is excluded in the interface filter
            date,
          ),
          holdingView(owner, BigDecimal(n), aDifferentIssuer, "AMT"),
          allocationRequestView(aDifferentIssuer, date),
        )
      })
      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- MonadUtil.sequentialTraverse(
          included ++ excluded
        ) { case (contract, holdingView, allocationRequestView) =>
          d1.create(
            contract,
            implementedInterfaces = Map(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView.toValue,
              allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID -> allocationRequestView.toValue,
            ),
          )
        }
        resultHolding <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
        resultAllocationRequest <- store.listInterfaceViews(
          allocationrequestv1.AllocationRequest.INTERFACE
        )
      } yield {
        resultHolding should be(included.map { case (contract, holdingView, _) =>
          Contract(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
            new holdingv1.Holding.ContractId(contract.contractId.contractId),
            holdingView,
            contract.createdEventBlob,
            contract.createdAt,
          )
        })
        resultAllocationRequest should be(included.map {
          case (contract, _, allocationRequestView) =>
            Contract(
              allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID,
              new allocationrequestv1.AllocationRequest.ContractId(contract.contractId.contractId),
              allocationRequestView,
              contract.createdEventBlob,
              contract.createdAt,
            )
        })
      }
    }

    "archive ingested interface views" in {
      implicit val store = mkStore()
      val dummies = (1 to 3).map { n =>
        val owner = providerParty(n)
        (
          dummyHolding(owner, BigDecimal(n), dsoParty),
          holdingView(owner, BigDecimal(n), dsoParty, "DUM"),
        )
      }
      val amulets = (4 to 6).map { n =>
        val owner = providerParty(n)
        (
          amulet(owner, BigDecimal(n), n.toLong, BigDecimal(0.0001)),
          holdingView(owner, BigDecimal(n), dsoParty, "AMT"),
        )
      }
      for {
        _ <- initWithAcs()
        _ <- MonadUtil.sequentialTraverse(dummies) { case (contract, holdingView) =>
          d1.create(
            contract,
            implementedInterfaces = Map(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView.toValue
            ),
          )
        }
        _ <- MonadUtil.sequentialTraverse(amulets) { case (contract, holdingView) =>
          d1.create(
            contract,
            implementedInterfaces = Map(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView.toValue
            ),
          )
        }
        resultBeforeArchive <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
        _ <- d1.archive(
          amulets(1)._1,
          implementedInterfaces = Seq(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID
          ),
        )
        _ <- d1.archive(
          dummies(1)._1,
          implementedInterfaces = Seq(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID
          ),
        )
        resultAfterArchive <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
      } yield {
        resultBeforeArchive should be((dummies ++ amulets).map { case (contract, holdingView) =>
          Contract(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
            new holdingv1.Holding.ContractId(contract.contractId.contractId),
            holdingView,
            contract.createdEventBlob,
            contract.createdAt,
          )
        })
        resultAfterArchive should be(Seq(dummies(0), dummies(2), amulets(0), amulets(2)).map {
          case (contract, holdingView) =>
            Contract(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
              new holdingv1.Holding.ContractId(contract.contractId.contractId),
              holdingView,
              contract.createdEventBlob,
              contract.createdAt,
            )
        })
      }
    }

    "ingest and return interface views for a template that's included as template and interface" in {
      implicit val store = mkStore(filter = {
        import MultiDomainAcsStore.mkFilter

        MultiDomainAcsStore.SimpleContractFilter(
          dsoParty,
          templateFilters = Map(
            mkFilter(Amulet.COMPANION)(_.payload.dso == dsoParty.toProtoPrimitive) { contract =>
              GenericAcsRowData(contract)
            }
          ),
          interfaceFilters = Map(
            mkFilterInterface(holdingv1.Holding.INTERFACE) { contract =>
              contract.payload.instrumentId.admin == dsoParty.toProtoPrimitive
            }(contract =>
              GenericInterfaceRowData(
                contract.identifier,
                contract.payload,
              )
            )
          ),
        )
      })
      val items = (1 to 3).map { n =>
        val owner = providerParty(n)
        (
          amulet(owner, BigDecimal(n), n.toLong, BigDecimal(0.001), dso = dsoParty),
          holdingView(owner, BigDecimal(n), dsoParty, "DUM"),
        )
      }
      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- MonadUtil.sequentialTraverse(items) { case (contract, holdingView) =>
          d1.create(
            contract,
            implementedInterfaces = Map(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView.toValue
            ),
          )
        }
        resultHolding <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
        resultByTemplate <- store.listAssignedContracts(
          Amulet.COMPANION
        )
      } yield {
        resultHolding should be(items.map { case (contract, holdingView) =>
          Contract(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
            new holdingv1.Holding.ContractId(contract.contractId.contractId),
            holdingView,
            contract.createdEventBlob,
            contract.createdAt,
          )
        })
        resultByTemplate.map(_.contract) should be(items.map(_._1))
      }
    }

    "ingest interface views as part of an ACS import" in {
      implicit val store = mkStore()
      val owner = providerParty(1)
      val aHolding = (
        amulet(owner, BigDecimal(10), 1L, BigDecimal(0.00001), dso = dsoParty),
        holdingView(owner, BigDecimal(10), dsoParty, "AMT"),
      )
      val aTwoInterfaces = (
        twoInterfaces(
          owner,
          BigDecimal(10),
          dsoParty,
          Instant.EPOCH.plusSeconds(1L),
        ),
        holdingView(owner, BigDecimal(10), dsoParty, "AMT"),
        allocationRequestView(dsoParty, Instant.EPOCH.plusSeconds(1L)),
      )
      val anExcludedHolding = (
        amulet(
          owner,
          BigDecimal(10),
          1L,
          BigDecimal(0.00001),
          dso = providerParty(42), // this is excluded in the interface filter
        ),
        holdingView(owner, BigDecimal(10), providerParty(42), "AMT"),
      )
      val incompleteOutHoldings = (2 to 5).map(n =>
        amulet(owner, BigDecimal(n), n.toLong, BigDecimal(0.00001), dso = dsoParty) ->
          holdingView(owner, BigDecimal(n.toLong), dsoParty, "AMT")
      )
      val incompleteInHolding = (6 to 8).map(n =>
        amulet(owner, BigDecimal(n), n.toLong, BigDecimal(0.00001), dso = dsoParty) ->
          holdingView(owner, BigDecimal(n), dsoParty, "AMT")
      )
      val acs = Seq(
        StoreTest.AcsImportEntry(
          aHolding._1,
          d1,
          0L,
          Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> aHolding._2.toValue
          ),
        ),
        StoreTest.AcsImportEntry(
          aTwoInterfaces._1,
          d1,
          0L,
          Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> aTwoInterfaces._2.toValue,
            allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID -> aTwoInterfaces._3.toValue,
          ),
        ),
        StoreTest.AcsImportEntry(
          anExcludedHolding._1,
          d1,
          0L,
          Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> anExcludedHolding._2.toValue
          ),
        ),
      )
      for {
        _ <- initWithAcs(
          acs,
          incompleteOut = incompleteOutHoldings.map { case (contract, holdingView) =>
            StoreTest.AcsImportIncompleteEntry(
              contract,
              d1,
              d2,
              nextReassignmentId,
              1L,
              Map(
                holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView.toValue
              ),
            )
          },
          incompleteIn = incompleteInHolding.map { case (contract, holdingView) =>
            StoreTest.AcsImportIncompleteEntry(
              contract,
              d2,
              d1,
              nextReassignmentId,
              1L,
              Map(
                holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> holdingView.toValue
              ),
            )
          },
        )
        resultHolding <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
        resultAllocationRequest <- store.listInterfaceViews(
          allocationrequestv1.AllocationRequest.INTERFACE
        )
      } yield {
        val allHoldings = Seq(
          aHolding,
          (aTwoInterfaces._1, aTwoInterfaces._2),
        ) ++ incompleteOutHoldings ++ incompleteInHolding
        resultHolding should be(allHoldings.map { case (contract, holdingView) =>
          Contract(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
            new holdingv1.Holding.ContractId(contract.contractId.contractId),
            holdingView,
            contract.createdEventBlob,
            contract.createdAt,
          )
        })
        resultAllocationRequest should be(
          Seq(
            Contract(
              allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID,
              new allocationrequestv1.AllocationRequest.ContractId(
                aTwoInterfaces._1.contractId.contractId
              ),
              aTwoInterfaces._3,
              aTwoInterfaces._1.createdEventBlob,
              aTwoInterfaces._1.createdAt,
            )
          )
        )
      }
    }

    "ingest interface views in assignment updates" in {
      implicit val store = mkStore()
      val owner = providerParty(1)
      val assignedHolding = (
        amulet(owner, BigDecimal(10), 1L, BigDecimal(0.00001), dso = dsoParty),
        holdingView(owner, BigDecimal(10), dsoParty, "AMT"),
      )
      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.assign(
          assignedHolding._1 -> d1,
          nextReassignmentId,
          1,
          implementedInterfaces = Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> assignedHolding._2.toValue
          ),
        )
        result <- store.listInterfaceViews(
          holdingv1.Holding.INTERFACE
        )
      } yield {
        result should be(
          Seq(
            Contract(
              holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
              new holdingv1.Holding.ContractId(assignedHolding._1.contractId.contractId),
              assignedHolding._2,
              assignedHolding._1.createdEventBlob,
              assignedHolding._1.createdAt,
            )
          )
        )
      }
    }

    "ingest and return interface views with their contract's state" in {
      implicit val store = mkStore()
      val owner = providerParty(1)
      val contract1 = amulet(owner, BigDecimal(10), 1L, BigDecimal(0.00001), dso = dsoParty)
      val view1 = holdingView(owner, BigDecimal(10), dsoParty, "AMT")
      val contract2 = amulet(
        owner,
        BigDecimal(20),
        2L,
        BigDecimal(0.00002),
        dso = dsoParty,
      )
      val view2 = holdingView(owner, BigDecimal(20), dsoParty, "AMT")
      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(
          contract1,
          implementedInterfaces = Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> view1.toValue
          ),
        )
        _ <- d2.create(
          contract2,
          implementedInterfaces = Map(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> view2.toValue
          ),
        )
        result1 <- store.findInterfaceViewByContractId(
          holdingv1.Holding.INTERFACE
        )(contract1.contractId.toInterface(holdingv1.Holding.INTERFACE))
        result2 <- store.findInterfaceViewByContractId(
          holdingv1.Holding.INTERFACE
        )(contract2.contractId.toInterface(holdingv1.Holding.INTERFACE))
      } yield {
        result1.value.contract should be(
          Contract(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
            new holdingv1.Holding.ContractId(contract1.contractId.contractId),
            view1,
            contract1.createdEventBlob,
            contract1.createdAt,
          )
        )
        result1.value.state.fold(_ should be(d1), fail("should be assigned"))

        result2.value.contract should be(
          Contract(
            holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID,
            new holdingv1.Holding.ContractId(contract2.contractId.contractId),
            view2,
            contract2.createdEventBlob,
            contract2.createdAt,
          )
        )
        result2.value.state.fold(_ should be(d2), fail("should be assigned"))
      }
    }

    "prevent against ingestion of same (moduleName, entityName) with different package name - templates" in {
      val filter =
        MultiDomainAcsStore.SimpleContractFilter[GenericAcsRowData, GenericInterfaceRowData](
          dsoParty,
          templateFilters = Map(
            mkFilter(Amulet.COMPANION)(_.payload.dso == dsoParty.toProtoPrimitive) { contract =>
              GenericAcsRowData(contract)
            }
          ),
          interfaceFilters = Map.empty,
        )
      implicit val store = mkStore(filter = filter)
      val owner = providerParty(1)
      val goodContract = amulet(owner, BigDecimal(10), 1L, BigDecimal(0.00001), dso = dsoParty)
      val badContract =
        amulet(owner, BigDecimal(20), 2L, BigDecimal(0.00002), dso = dsoParty).copy(identifier =
          new Identifier(
            maliciousPackageId,
            goodContract.identifier.getModuleName,
            goodContract.identifier.getEntityName,
          )
        )

      filter.contains(toCreatedEvent(goodContract)) should be(true)
      filter.contains(toCreatedEvent(badContract)) should be(false)

      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(
          goodContract
        )
        _ <- d1.create(
          badContract
        )
        resultAmulet <- store.listContracts(Amulet.COMPANION)
      } yield {
        resultAmulet.map(_.contractId.contractId) should contain theSameElementsAs Seq(
          goodContract.contractId.contractId
        )
      }
    }

    "prevent against ingestion of same (moduleName, entityName) with different package name - interfaces" in {
      val filter =
        MultiDomainAcsStore.SimpleContractFilter[GenericAcsRowData, GenericInterfaceRowData](
          dsoParty,
          templateFilters = Map.empty,
          interfaceFilters = Map(
            mkFilterInterface(holdingv1.Holding.INTERFACE)(_ => true) { contract =>
              GenericInterfaceRowData(contract.identifier, contract.payload)
            }
          ),
        )
      implicit val store = mkStore(
        filter = filter
      )
      val owner = providerParty(1)
      val goodContract = amulet(owner, BigDecimal(10), 1L, BigDecimal(0.00001), dso = dsoParty)
      val goodView = holdingView(owner, BigDecimal(10), dsoParty, "AMT")
      val goodImplementedInterfaces = Map(
        holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> goodView.toValue
      )
      val badContract = dummyHolding(owner, BigDecimal(20), dsoParty)
      val badView = holdingView(owner, BigDecimal(20), dsoParty, "AMT")
      val badImplementedInterfaces = Map(
        new Identifier(
          maliciousPackageId,
          holdingv1.Holding.INTERFACE_ID.getModuleName,
          holdingv1.Holding.INTERFACE_ID.getEntityName,
        ) -> badView.toValue
      )

      filter.contains(
        toCreatedEvent(
          goodContract,
          implementedInterfaces = goodImplementedInterfaces,
        )
      ) should be(true)

      filter.contains(
        toCreatedEvent(
          badContract,
          implementedInterfaces = badImplementedInterfaces,
        )
      ) should be(false)

      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(
          goodContract,
          implementedInterfaces = goodImplementedInterfaces,
        )
        _ <- d1.create(
          badContract,
          implementedInterfaces = badImplementedInterfaces,
        )
        resultHolding <- store.listInterfaceViews(holdingv1.Holding.INTERFACE)
        resultAmulet <- store.listContracts(Amulet.COMPANION)
      } yield {
        resultHolding.map(_.contractId.contractId) should contain theSameElementsAs Seq(
          goodContract.contractId.contractId
        )
        resultAmulet.map(_.contractId.contractId) should contain theSameElementsAs Seq(
          goodContract.contractId.contractId
        )
      }
    }

    "prevent against ingestion of same (moduleName, entityName) with different package name - via interface fallback" in {
      val filter =
        MultiDomainAcsStore.SimpleContractFilter[GenericAcsRowData, GenericInterfaceRowData](
          dsoParty,
          templateFilters = Map.empty,
          interfaceFilters = Map(
            mkFilterInterface(holdingv1.Holding.INTERFACE)(_ => true) { contract =>
              GenericInterfaceRowData(contract.identifier, contract.payload)
            }
          ),
        )
      implicit val store = mkStore(
        filter = filter
      )
      val owner = providerParty(1)
      val goodContract = amulet(owner, BigDecimal(10), 1L, BigDecimal(0.00001), dso = dsoParty)
      val goodView = holdingView(owner, BigDecimal(10), dsoParty, "AMT")
      val goodImplementedInterfaces = Map(
        holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> goodView.toValue
      )
      // like Splice's Amulet, but with different packageid
      // that still implements the Holding interface
      val fakeAmuletContract =
        amulet(owner, BigDecimal(20), 2L, BigDecimal(0.00002), dso = dsoParty).copy(identifier =
          new Identifier(
            maliciousPackageId,
            goodContract.identifier.getModuleName,
            goodContract.identifier.getEntityName,
          )
        )
      val fakeAmuletView = holdingView(owner, BigDecimal(20), dsoParty, "AMT")
      val fakeAmuletImplementedInterfaces = Map(
        holdingv1.Holding.INTERFACE_ID_WITH_PACKAGE_ID -> fakeAmuletView.toValue
      )

      filter.contains(
        toCreatedEvent(
          goodContract,
          implementedInterfaces = goodImplementedInterfaces,
        )
      ) should be(true)

      filter.contains(
        toCreatedEvent(
          fakeAmuletContract,
          implementedInterfaces = fakeAmuletImplementedInterfaces,
        )
      ) should be(true) // as a Holding, it is included. But it should not be included as an Amulet.

      for {
        _ <- initWithAcs()
        _ <- assertList()
        _ <- d1.create(
          goodContract,
          implementedInterfaces = goodImplementedInterfaces,
        )
        _ <- d1.create(
          fakeAmuletContract,
          implementedInterfaces = fakeAmuletImplementedInterfaces,
        )
        resultHolding <- store.listInterfaceViews(holdingv1.Holding.INTERFACE)
        resultAmulet <- store.listContracts(Amulet.COMPANION)
      } yield {
        // fakeAmuletContract is a Holding
        resultHolding.map(_.contractId.contractId) should contain theSameElementsAs Seq(
          goodContract.contractId.contractId,
          fakeAmuletContract.contractId.contractId,
        )
        // but it is not an Amulet
        resultAmulet.map(_.contractId.contractId) should contain theSameElementsAs Seq(
          goodContract.contractId.contractId
        )
      }
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
