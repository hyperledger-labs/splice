package com.daml.network.store

import cats.instances.list.*
import cats.syntax.foldable.*
import akka.actor.ActorSystem
import akka.stream.scaladsl.*
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree, TreeEvent}
import com.daml.network.codegen.java.cc.{coin as directoryCodegen}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.{FutureSupervisor, Threading}
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*

class InMemoryAcsWithTxLogStoreTest extends StoreTest {

  implicit val actorSystem: ActorSystem = ActorSystem("InMemoryAcsStoreTest")

  // test values
  val (validatorRewardCouponsForAcs, validatorRewardCouponsForTxs) =
    Seq(0, 1, 2, 3).map(i => mkValidatorRewardCoupon(i)).splitAt(2)

  private val appRewardCoupons = Seq(0, 1).map(i => appRewardCoupon(i, providerParty(i)))
  private val appRewardCouponsToArchive = Seq(2, 3).map(i => appRewardCoupon(i, providerParty(i)))
  // these entries have the provider party as a user, and should be disregarded in lookups
  private val filteredAppRewardCoupons =
    Seq(0, 1, 3).map(i => appRewardCoupon(i + 100, mkPartyId(s"provider-$i")))
  private val acsEvents =
    appRewardCouponsToArchive.map(toCreatedEvent) ++ appRewardCoupons.map(toCreatedEvent)

  val acsOffset = "010"
  val tx1Offset = "011"
  val tx2Offset = "012"
  val tx3Offset = "013"
  val tx4Offset = "014"
  val tx5Offset = "015"

  val tx1: TransactionTree = mkTx(
    tx1Offset,
    Seq(
      filteredAppRewardCoupons.map(toCreatedEvent),
      appRewardCouponsToArchive.map(toArchivedEvent),
      validatorRewardCouponsForAcs.map(toCreatedEvent),
    ).flatten,
  )

  private def mkCreateTx[TCid <: ContractId[T], T](
      offset: String,
      createRequests: Seq[Contract[TCid, T]],
  ) = mkTx(offset, createRequests.map[TreeEvent](toCreatedEvent))

  val tx2: TransactionTree = mkTx(
    tx2Offset,
    Seq(mkValidatorRewardCoupon(100))
      .map(toCreatedEvent)
      .appendedAll(filteredAppRewardCoupons.map(toArchivedEvent)),
  )
  val tx3: TransactionTree = mkCreateTx(tx3Offset, validatorRewardCouponsForTxs)
  val tx4: TransactionTree = mkCreateTx(tx4Offset, Seq(mkValidatorRewardCoupon(5)))
  val tx5: TransactionTree = mkCreateTx(tx5Offset, Seq(mkValidatorRewardCoupon(6)))

  val txFilter: AcsStore.ContractFilter = {
    import AcsStore.mkFilter

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(directoryCodegen.AppRewardCoupon.COMPANION)(co => co.payload.round.number < 100),
        mkFilter(directoryCodegen.ValidatorRewardCoupon.COMPANION)(co =>
          co.payload.round.number < 100
        ),
      ),
    )
  }

  case class TestTxLogIndexRecord(
      offset: String,
      eventId: String,
  ) extends TxLogStore.IndexRecord

  case class TestTxLogEntry(
      indexRecord: TestTxLogIndexRecord,
      payload: String,
  ) extends TxLogStore.Entry[TestTxLogIndexRecord]

  object TestTxLogStoreParser extends TxLogStore.Parser[TestTxLogIndexRecord, TestTxLogEntry] {
    override def parseCreate(tx: TransactionTree, event: CreatedEvent)(implicit
        tc: TraceContext
    ): Option[TestTxLogEntry] =
      Some(
        TestTxLogEntry(
          indexRecord = TestTxLogIndexRecord(offset = tx.getOffset, eventId = event.getEventId),
          payload = event.getEventId,
        )
      )

    override def parseExercise(tx: TransactionTree, event: ExercisedEvent)(implicit
        tc: TraceContext
    ): Option[TestTxLogEntry] = None
  }

  def mkStore(): Future[InMemoryAcsWithTxLogStore[TestTxLogIndexRecord, TestTxLogEntry]] = {
    val store = new InMemoryAcsWithTxLogStore[TestTxLogIndexRecord, TestTxLogEntry](
      loggerFactory,
      txFilter,
      TestTxLogStoreParser,
      FutureSupervisor.Noop,
    )
    for {
      // ingest test events
      case () <- store.ingestionSink.ingestActiveContracts(acsEvents)
      case () <- store.ingestionSink.switchToIngestingTransactions(acsOffset)
      // ingest test txs
      case () <- store.ingestionSink.ingestTransaction(tx1)
      case () <- store.ingestionSink.ingestTransaction(tx2)
    } yield store
  }

  "InMemoryAcsStore" should {

    "lookup ingested validator rewards by id" in {
      for {
        store <- mkStore()
        retrieveResults = () =>
          Future.sequence(validatorRewardCouponsForAcs.map(req => {
            store.lookupContractById(directoryCodegen.ValidatorRewardCoupon.COMPANION)(
              req.contractId
            )
          }))
        results1 <- retrieveResults()
        // archive all results
        case () <- store.ingestionSink.ingestTransaction(
          mkTx(tx3Offset, validatorRewardCouponsForAcs.map(toArchivedEvent))
        )
        results2 <- retrieveResults()
      } yield {
        results1 shouldBe validatorRewardCouponsForAcs.map(reward => Some(reward))
        results2 shouldBe validatorRewardCouponsForAcs.map(_ => None)
      }
    }

    "lookup a non-ingested app reward by id" in {
      for {
        store <- mkStore()
        result <- store.lookupContractById(directoryCodegen.AppRewardCoupon.COMPANION)(
          new ContractId(s"non-existent#1")
        )
      } yield result shouldBe None
    }

    "lookup an non-ingested app reward by party" in {
      for {
        store <- mkStore()
        result <- store.findContractWithOffset(directoryCodegen.AppRewardCoupon.COMPANION)(co =>
          co.payload.provider == userParty(100).toProtoPrimitive
        )
      } yield result shouldBe QueryResult(tx2Offset, None)
    }

    "lookup an ingested app reward by provider party" in {
      for {
        store <- mkStore()
        result <- store.findContractWithOffset(directoryCodegen.AppRewardCoupon.COMPANION)(co =>
          co.payload.provider == providerParty(1).toProtoPrimitive
        )
      } yield result shouldBe QueryResult(tx2Offset, Some(appRewardCoupons(1)))
    }

    "list all ingested and active app rewards" in {
      for {
        store <- mkStore()
        result <- store.listContracts(directoryCodegen.AppRewardCoupon.COMPANION)
      } yield result shouldBe appRewardCoupons
    }

    "stream all ingested app rewards" in {
      for {
        store <- mkStore()
        streamedReqs <- store
          .streamContracts(directoryCodegen.AppRewardCoupon.COMPANION)
          .take(appRewardCoupons.length.toLong)
          .runWith(Sink.seq)
      } yield streamedReqs shouldBe appRewardCoupons
    }

    "stream ingested entry requests and wait for new ones to come in" in {
      val acc: AtomicReference[List[Contract[
        directoryCodegen.ValidatorRewardCoupon.ContractId,
        directoryCodegen.ValidatorRewardCoupon,
      ]]] =
        new AtomicReference(List.empty)
      for {
        store <- mkStore()
        rewardsPromise: Promise[Unit] = Promise[Unit]()
        extraReqsPromise: Promise[Unit] = Promise[Unit]()
        _sourceWillNeverCompleteF = store
          .streamContracts(directoryCodegen.ValidatorRewardCoupon.COMPANION)
          .runForeach(co => {
            val cos = acc.get().appended(co)
            acc.set(cos)
            if (cos.length == validatorRewardCouponsForAcs.length) rewardsPromise.success(())
            if (
              cos.length == validatorRewardCouponsForAcs.length + validatorRewardCouponsForTxs.length
            )
              extraReqsPromise.success(())
          })
        case () <- rewardsPromise.future
        // sleep for 10 millis so the source had a chance to produce extra elements if it was buggy
        _ = Threading.sleep(10)
        rewardsBeforeIngestion = acc.get()
        case () <- store.ingestionSink.ingestTransaction(tx3)
        case () <- extraReqsPromise.future
        rewardsAfterIngestion = acc.get()
      } yield {
        rewardsBeforeIngestion shouldBe validatorRewardCouponsForAcs
        rewardsAfterIngestion shouldBe (validatorRewardCouponsForAcs ++ validatorRewardCouponsForTxs)
      }
    }

    "signal when offsets have been ingested" in {
      for {
        store <- mkStore()
        acsOffsetIngestedF = store.signalWhenIngested(acsOffset)
        tx2OffsetIngestedF = store.signalWhenIngested(tx2Offset)
        tx3OffsetIngestedF = store.signalWhenIngested(tx3Offset)
        tx4OffsetIngestedF = store.signalWhenIngested(tx4Offset)
        tx5OffsetIngestedF = store.signalWhenIngested(tx5Offset)
        _ <- store.ingestionSink.ingestTransaction(tx4)
      } yield {
        acsOffsetIngestedF.isCompleted shouldBe true
        tx2OffsetIngestedF.isCompleted shouldBe true
        // tx 3 is never directly ingested, however, since tx4 with the higher offset is ingested, it should still be signalled as
        tx3OffsetIngestedF.isCompleted shouldBe true
        tx4OffsetIngestedF.isCompleted shouldBe true
        // never ingested.
        tx5OffsetIngestedF.isCompleted shouldBe false
      }
    }

    "handles transfers" in {
      for {
        store <- mkStore()
        result <- store.listContracts(directoryCodegen.AppRewardCoupon.COMPANION)
        _ = result shouldBe appRewardCoupons
        transferOuts = result.zipWithIndex.map { case (contract, i) =>
          mkTransfer(s"016$i", toTransferOutEvent(contract.contractId))
        }
        _ <- transferOuts.toList.traverse_(store.ingestionSink.ingestTransfer(_))
        result <- store.listContracts(directoryCodegen.AppRewardCoupon.COMPANION)
        _ = result shouldBe empty
        transferIns = appRewardCoupons.zipWithIndex.map { case (contract, i) =>
          mkTransfer(s"017$i", toTransferInEvent(contract))
        }
        _ <- transferIns.toList.traverse_(store.ingestionSink.ingestTransfer(_))
        result <- store.listContracts(directoryCodegen.AppRewardCoupon.COMPANION)
        _ = result shouldBe appRewardCoupons
      } yield succeed
    }

    "return tx log entries in correct order" in {
      for {
        store <- mkStore()
        reader = new TxLogStore.Reader[TestTxLogIndexRecord, TestTxLogEntry](
          txLogStore = store,
          transactionTreeSource = TransactionTreeSource.StaticForTesting(Seq(tx1, tx2, tx3, tx4)),
        )
        indices <- store.getTxLogIndicesByOffset(0, 1000)
        entries <- reader.getTxLogByOffset(0, 1000)
        indices2 <- store.getTxLogIndicesByOffset(2, 3)
        entries2 <- reader.getTxLogByOffset(2, 3)
      } yield {
        // The test tx log creates one entry for each create event.
        // The test transactions only contain root nodes, the tx log should preserve their order.
        val expectedEventsInTxLog = Seq(tx1, tx2).flatMap(tx =>
          tx.getRootEventIds.asScala.toList
            .collect(i => tx.getEventsById.get(i) match { case c: CreatedEvent => c })
        )
        val expectedEventIds = expectedEventsInTxLog.map(_.getEventId)

        indices.map(_.eventId) should contain theSameElementsInOrderAs expectedEventIds
        entries.map(_.payload) should contain theSameElementsInOrderAs expectedEventIds

        indices2.map(_.eventId) should contain theSameElementsInOrderAs expectedEventIds.slice(2, 3)
        entries2.map(_.payload) should contain theSameElementsInOrderAs expectedEventIds.slice(2, 3)
      }
    }

  }
}
