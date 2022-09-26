package com.daml.network.directory.store

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import com.daml.ledger.api.v1.event.{CreatedEvent, Event}
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.{Directory => directoryCodegen}
import com.daml.network.util.Contract
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.resource.MemoryStorage
import com.digitalasset.canton.topology.PartyId
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}

class DirectoryAppStoreTest extends AsyncWordSpec with BaseTest {

  implicit val actorSystem: ActorSystem = ActorSystem("DirectoryAppStoreTest")

  def mkPartyId(name: String) = PartyId.tryFromPrim(Primitive.Party(name + "::dummy"))
  val providerParty: PartyId = mkPartyId("provider")
  def userParty(i: Int) = mkPartyId(s"user-$i")

  def directoryEntry(
      number: Int,
      provider: PartyId,
      user: PartyId,
      name: String,
  ): Contract[directoryCodegen.DirectoryEntry] =
    Contract[directoryCodegen.DirectoryEntry](
      contractId = Primitive.ContractId(s"de#$number"),
      payload = directoryCodegen.DirectoryEntry(
        user = user.toPrim,
        provider = provider.toPrim,
        name = name,
      ),
    )

  def directoryEntryRequest(
      number: Int,
      provider: PartyId,
      user: PartyId,
      name: String,
  ): Contract[directoryCodegen.DirectoryEntryRequest] =
    Contract[directoryCodegen.DirectoryEntryRequest](
      contractId = Primitive.ContractId(s"der#$number"),
      payload = directoryCodegen.DirectoryEntryRequest(
        directoryEntry(number, provider, user, name).payload,
        entryFee = 1.0,
      ),
    )

  def toCreatedEvent[T](contract: Contract[T]): CreatedEvent = {
    val contractP = contract.toProtoV0
    CreatedEvent(
      eventId = "dummyEventId",
      contractId = contractP.contractId,
      templateId = contractP.templateId,
      createArguments = contractP.payload,
      witnessParties = Seq.empty,
      signatories = Seq.empty,
      observers = Seq.empty,
      agreementText = None,
      contractKey = None,
    )
  }

  // test values
  val (reqs, txReqs) = Seq(0, 1, 2, 3)
    .map(i =>
      directoryEntryRequest(
        i,
        providerParty,
        userParty(i),
        "the-one",
      )
    )
    .splitAt(2)

  val entries =
    Seq(0, 1).map(i => directoryEntry(i, providerParty, userParty(i), s"entry-name-$i"))
  // these entries have the provider party as a user, and should be disregarded in lookups
  val confusingEntries = Seq(0, 1).map(i =>
    directoryEntry(
      i + 100,
      mkPartyId(s"other-provider-$i"),
      providerParty,
      s"entry-name-$i",
    )
  )
  val testEvents =
    entries.map(toCreatedEvent) ++ reqs.map(toCreatedEvent) ++ confusingEntries.map(toCreatedEvent)

  val acsOffset = "001"
  val txOffset = "002"

  val tx: Transaction = Transaction(
    offset = txOffset,
    events = txReqs.map(co => Event.of(Event.Event.Created(toCreatedEvent(co)))),
  )

  def mkStore(): Future[DirectoryAppStore] = {
    val store = DirectoryAppStore(new MemoryStorage, loggerFactory)
    for {
      // store provider party after initialization
      () <- store.setProviderParty(providerParty)
      // ingest test events
      () <- store.ingestActiveContracts(testEvents)
      () <- store.switchToIngestingTransactions(acsOffset)
    } yield store
  }

  // TODO(#790): test queries running concurrently with ingestion
  // TODO(#790): test queries before and after ingesting a transaction
  // TODO(#790): review test coverage in general

  "Directory app store" should {

    "lookup ingested entry requests by id" in {
      for {
        store <- mkStore()
        results <- Future.sequence(reqs.map(req => {
          store.lookupEntryRequestById(req.contractId)
        }))
      } yield results shouldBe reqs.map(req => QueryResult(acsOffset, Some(req)))
    }

    "lookup a non-ingested entry by id" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryRequestById(Primitive.ContractId(s"non-existent#1"))
      } yield result shouldBe QueryResult(acsOffset, None)
    }

    "lookup an ingested entry by name" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryByName("entry-name-1")
      } yield result shouldBe QueryResult(acsOffset, Some(entries(1)))
    }

    "lookup an non-ingested entry by party" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryByParty(providerParty)
      } yield result shouldBe QueryResult(acsOffset, None)
    }

    "lookup an ingested entry by party" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryByParty(userParty(1))
      } yield result shouldBe QueryResult(acsOffset, Some(entries(1)))
    }

    "list all ingested directory entries" in {
      for {
        store <- mkStore()
        result <- store.listEntries()
      } yield result shouldBe QueryResult(acsOffset, entries)
    }

    "stream all ingested entry requests" in {
      for {
        store <- mkStore()
        streamedReqs <- store.streamEntryRequests().take(reqs.length.toLong).runWith(Sink.seq)
      } yield streamedReqs shouldBe reqs
    }

    "stream ingested entry requests and wait for new ones to come in" in {
      val acc: AtomicReference[List[Contract[directoryCodegen.DirectoryEntryRequest]]] =
        new AtomicReference(List.empty)
      for {
        store <- mkStore()
        reqsPromise: Promise[Unit] = Promise[Unit]()
        extraReqsPromise: Promise[Unit] = Promise[Unit]()
        _sourceWillNeverCompleteF = store
          .streamEntryRequests()
          .runForeach(co => {
            val cos = acc.get().appended(co)
            acc.set(cos)
            if (cos.length == reqs.length) reqsPromise.success(())
            if (cos.length == reqs.length + txReqs.length) extraReqsPromise.success(())
          })
        () <- reqsPromise.future
        // sleep for 10 millis so the source had a chance to produce extra elements if it was buggy
        _ = Threading.sleep(10)
        reqsBeforeIngestion = acc.get()
        () <- store.ingestTransaction(tx)
        () <- extraReqsPromise.future
        reqsAfterIngestion = acc.get()
      } yield {
        reqsBeforeIngestion shouldBe reqs
        reqsAfterIngestion shouldBe (reqs ++ txReqs)
      }
    }

  }
}
