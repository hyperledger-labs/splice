package com.daml.network.store

import akka.actor.ActorSystem
import akka.stream.scaladsl.*
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{ArchivedEvent, CreatedEvent, Event, Transaction}
import com.daml.network.codegen.java.cc.{api as apiCodegen, coin as directoryCodegen}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class InMemoryAcsStoreTest extends AsyncWordSpec with BaseTest {

  implicit val actorSystem: ActorSystem = ActorSystem("InMemoryAcsStoreTest")

  def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")
  val svcParty: PartyId = mkPartyId("svc")
  def userParty(i: Int) = mkPartyId(s"user-$i")
  def providerParty(i: Int) = mkPartyId(s"provider-$i")

  def appReward(
      round: Int,
      provider: PartyId,
  ): JavaContract[directoryCodegen.AppReward.ContractId, directoryCodegen.AppReward] =
    JavaContract(
      identifier = directoryCodegen.AppReward.TEMPLATE_ID,
      contractId = new directoryCodegen.AppReward.ContractId(s"de#$round"),
      payload = new directoryCodegen.AppReward(
        svcParty.toProtoPrimitive,
        provider.toProtoPrimitive,
        BigDecimal(1.0).bigDecimal,
        new apiCodegen.v1.round.Round(round),
      ),
    )

  def validatorReward(
      round: Int,
      user: PartyId,
  ): JavaContract[
    directoryCodegen.ValidatorReward.ContractId,
    directoryCodegen.ValidatorReward,
  ] =
    JavaContract(
      identifier = directoryCodegen.ValidatorReward.TEMPLATE_ID,
      contractId = new directoryCodegen.ValidatorReward.ContractId(s"der#$round"),
      payload = new directoryCodegen.ValidatorReward(
        svcParty.toProtoPrimitive,
        user.toProtoPrimitive,
        BigDecimal(1.0).bigDecimal,
        new apiCodegen.v1.round.Round(round),
      ),
    )

  def toCreatedEvent[TCid <: ContractId[T], T](
      contract: JavaContract[TCid, T]
  ): CreatedEvent = {
    val contractP = contract.toProtoV0
    new CreatedEvent(
      eventId = "dummyEventId",
      contractId = contractP.contractId,
      interfaceViews = Map.empty.asJava,
      failedInterfaceViews = Map.empty.asJava,
      templateId = contract.identifier,
      arguments = contract.payload.toValue,
      witnessParties = Seq.empty.asJava,
      signatories = Seq.empty.asJava,
      observers = Seq.empty.asJava,
      agreementText = None.toJava,
      contractKey = None.toJava,
    )
  }

  def toArchivedEvent[TCid <: ContractId[T], T](
      contract: JavaContract[TCid, T]
  ): ArchivedEvent = {
    new ArchivedEvent(
      eventId = "dummyEventId",
      contractId = contract.contractId.contractId,
      templateId = contract.identifier,
      witnessParties = Seq.empty.asJava,
    )
  }

  // test values
  val (validatorRewardsForAcs, validatorRewardsForTxs) =
    Seq(0, 1, 2, 3).map(i => mkValidatorReward(i)).splitAt(2)

  private def mkValidatorReward(i: Int) = validatorReward(i, userParty(i))

  private val appRewards = Seq(0, 1).map(i => appReward(i, providerParty(i)))
  private val appRewardsToArchive = Seq(2, 3).map(i => appReward(i, providerParty(i)))
  // these entries have the provider party as a user, and should be disregarded in lookups
  private val filteredAppRewards =
    Seq(0, 1, 3).map(i => appReward(i + 100, mkPartyId(s"provider-$i")))
  private val acsEvents = appRewardsToArchive.map(toCreatedEvent) ++ appRewards.map(toCreatedEvent)

  val acsOffset = "010"
  val tx1Offset = "011"
  val tx2Offset = "012"
  val tx3Offset = "013"
  val tx4Offset = "014"
  val tx5Offset = "015"

  val effectiveAt: Instant = CantonTimestamp.Epoch.toInstant

  private def mkTx(offset: String, events: Seq[Event]): Transaction = new Transaction(
    transactionId = "",
    commandId = "",
    workflowId = "",
    effectiveAt = effectiveAt,
    offset = offset,
    events = events.asJava,
  )

  val tx1: Transaction = mkTx(
    tx1Offset,
    Seq(
      filteredAppRewards.map(toCreatedEvent),
      appRewardsToArchive.map(toArchivedEvent),
      validatorRewardsForAcs.map(toCreatedEvent),
    ).flatten,
  )

  private def mkCreateTx[TCid <: ContractId[T], T](
      offset: String,
      createRequests: Seq[JavaContract[TCid, T]],
  ) = mkTx(offset, createRequests.map[Event](toCreatedEvent))

  val tx2: Transaction = mkTx(
    tx2Offset,
    Seq(mkValidatorReward(100))
      .map(toCreatedEvent)
      .appendedAll(filteredAppRewards.map(toArchivedEvent)),
  )
  val tx3: Transaction = mkCreateTx(tx3Offset, validatorRewardsForTxs)
  val tx4: Transaction = mkCreateTx(tx4Offset, Seq(mkValidatorReward(5)))
  val tx5: Transaction = mkCreateTx(tx5Offset, Seq(mkValidatorReward(6)))

  val txFilter: AcsStore.ContractFilter = {
    import AcsStore.mkFilter

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(directoryCodegen.AppReward.COMPANION)(co => co.payload.round.number < 100),
        mkFilter(directoryCodegen.ValidatorReward.COMPANION)(co => co.payload.round.number < 100),
      ),
    )
  }

  def mkStore(): Future[InMemoryAcsStore] = {
    val store = new InMemoryAcsStore(
      loggerFactory,
      txFilter,
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
          Future.sequence(validatorRewardsForAcs.map(req => {
            store.lookupContractById(directoryCodegen.ValidatorReward.COMPANION)(req.contractId)
          }))
        results1 <- retrieveResults()
        // archive all results
        case () <- store.ingestionSink.ingestTransaction(
          mkTx(tx3Offset, validatorRewardsForAcs.map(toArchivedEvent))
        )
        results2 <- retrieveResults()
      } yield {
        results1 shouldBe validatorRewardsForAcs.map(reward => QueryResult(tx2Offset, Some(reward)))
        results2 shouldBe validatorRewardsForAcs.map(_ => QueryResult(tx3Offset, None))
      }
    }

    "lookup a non-ingested app reward by id" in {
      for {
        store <- mkStore()
        result <- store.lookupContractById(directoryCodegen.AppReward.COMPANION)(
          new ContractId(s"non-existent#1")
        )
      } yield result shouldBe QueryResult(tx2Offset, None)
    }

    "lookup an non-ingested app reward by party" in {
      for {
        store <- mkStore()
        result <- store.findContract(directoryCodegen.AppReward.COMPANION)(co =>
          co.payload.provider == userParty(100).toProtoPrimitive
        )
      } yield result shouldBe QueryResult(tx2Offset, None)
    }

    "lookup an ingested app reward by provider party" in {
      for {
        store <- mkStore()
        result <- store.findContract(directoryCodegen.AppReward.COMPANION)(co =>
          co.payload.provider == providerParty(1).toProtoPrimitive
        )
      } yield result shouldBe QueryResult(tx2Offset, Some(appRewards(1)))
    }

    "list all ingested and active app rewards" in {
      for {
        store <- mkStore()
        result <- store.listContracts(directoryCodegen.AppReward.COMPANION)
      } yield result shouldBe QueryResult(tx2Offset, appRewards)
    }

    "stream all ingested app rewards" in {
      for {
        store <- mkStore()
        streamedReqs <- store
          .streamContracts(directoryCodegen.AppReward.COMPANION)
          .take(appRewards.length.toLong)
          .runWith(Sink.seq)
      } yield streamedReqs shouldBe appRewards
    }

    "stream ingested entry requests and wait for new ones to come in" in {
      val acc: AtomicReference[List[JavaContract[
        directoryCodegen.ValidatorReward.ContractId,
        directoryCodegen.ValidatorReward,
      ]]] =
        new AtomicReference(List.empty)
      for {
        store <- mkStore()
        rewardsPromise: Promise[Unit] = Promise[Unit]()
        extraReqsPromise: Promise[Unit] = Promise[Unit]()
        _sourceWillNeverCompleteF = store
          .streamContracts(directoryCodegen.ValidatorReward.COMPANION)
          .runForeach(co => {
            val cos = acc.get().appended(co)
            acc.set(cos)
            if (cos.length == validatorRewardsForAcs.length) rewardsPromise.success(())
            if (cos.length == validatorRewardsForAcs.length + validatorRewardsForTxs.length)
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
        rewardsBeforeIngestion shouldBe validatorRewardsForAcs
        rewardsAfterIngestion shouldBe (validatorRewardsForAcs ++ validatorRewardsForTxs)
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

  }
}
