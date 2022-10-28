package com.daml.network.directory.store

import akka.actor.ActorSystem
import akka.stream.scaladsl.*
import com.daml.ledger.api.v1.event.{ArchivedEvent, CreatedEvent, Event}
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.Directory as directoryCodegen
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.Contract
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.resource.MemoryStorage
import com.digitalasset.canton.topology.PartyId
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}

class DirectoryStoreTest extends AsyncWordSpec with BaseTest {

  implicit val actorSystem: ActorSystem = ActorSystem("DirectoryAppStoreTest")

  def mkPartyId(name: String) = PartyId.tryFromPrim(Primitive.Party(name + "::dummy"))
  val providerParty: PartyId = mkPartyId("provider")
  val svcParty: PartyId = mkPartyId("svc")
  def userParty(i: Int) = mkPartyId(s"user-$i")

  def directoryEntry(
      number: Int,
      provider: PartyId,
      user: PartyId,
      name: String,
      expiresAt: Primitive.Timestamp,
  ): Contract[directoryCodegen.DirectoryEntry] =
    Contract[directoryCodegen.DirectoryEntry](
      contractId = Primitive.ContractId(s"de#$number"),
      payload = directoryCodegen.DirectoryEntry(
        user = user.toPrim,
        provider = provider.toPrim,
        name = name,
        expiresAt = expiresAt,
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
        user = user.toPrim,
        provider = provider.toPrim,
        name = name,
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

  def toArchivedEvent[T](contract: Contract[T]): ArchivedEvent = {
    val contractP = contract.toProtoV0
    ArchivedEvent(
      eventId = "dummyEventId",
      contractId = contractP.contractId,
      templateId = contractP.templateId,
      witnessParties = Seq.empty,
    )
  }

  // test values
  val (requests, txRequests) = Seq(0, 1, 2, 3).map(i => mkEntryRequest(i, "the-one")).splitAt(2)

  private def mkEntryRequest(i: Int, entry: String) = directoryEntryRequest(
    i,
    providerParty,
    userParty(i),
    entry,
  )

  val expiry: Primitive.Timestamp =
    Primitive.Timestamp.discardNanos(Instant.EPOCH).getOrElse(fail("Failed to convert timestamp"))

  val entries =
    Seq(0, 1).map(i => directoryEntry(i, providerParty, userParty(i), s"entry-name-$i", expiry))
  val entriesToArchive =
    Seq(2, 3).map(i => directoryEntry(i, providerParty, userParty(i), s"entry-name-$i", expiry))
  // these entries have the provider party as a user, and should be disregarded in lookups
  val nonIngestedEntries = Seq(0, 1, 3).map(i =>
    directoryEntry(
      i + 100,
      mkPartyId(s"other-provider-$i"),
      providerParty,
      s"entry-name-$i",
      expiry,
    )
  )
  val acsEvents =
    entriesToArchive.map(toCreatedEvent) ++ entries.map(toCreatedEvent)

  val acsOffset = "010"
  val tx1Offset = "011"
  val tx2Offset = "012"
  val tx3Offset = "013"
  val tx4Offset = "014"
  val tx5Offset = "015"

  val tx1: Transaction = Transaction(
    offset = tx1Offset,
    events = entriesToArchive.map(co => Event.of(Event.Event.Archived(toArchivedEvent(co))))
      ++ requests.map(co => Event.of(Event.Event.Created(toCreatedEvent(co)))),
  )

  private def createCreateTx[T](
      offset: String,
      createRequests: Seq[Contract[T]],
  ) = Transaction(
    offset = offset,
    events = createRequests.map(req => Event.of(Event.Event.Created(toCreatedEvent(req)))),
  )

  val tx2: Transaction = createCreateTx(tx2Offset, nonIngestedEntries)
  val tx3: Transaction = createCreateTx(tx3Offset, txRequests)
  val tx4: Transaction = createCreateTx(tx4Offset, Seq(mkEntryRequest(5, "smth")))
  val tx5: Transaction = createCreateTx(tx5Offset, Seq(mkEntryRequest(6, "smth-else")))

  def mkStore(): Future[DirectoryStore] = {
    val store = DirectoryStore(
      providerParty = providerParty,
      svcParty = svcParty,
      new MemoryStorage,
      loggerFactory,
    )
    for {
      // ingest test events
      () <- store.acsIngestionSink.ingestActiveContracts(acsEvents)
      () <- store.acsIngestionSink.switchToIngestingTransactions(acsOffset)
      // ingest test txs
      () <- store.acsIngestionSink.ingestTransaction(tx1)
      () <- store.acsIngestionSink.ingestTransaction(tx2)
    } yield store
  }

  // TODO(#790): test queries running concurrently with ingestion
  // TODO(#790): test queries before and after ingesting a transaction
  // TODO(#790): review test coverage in general

  "Directory app store" should {

    "lookup ingested entry requests by id" in {
      for {
        store <- mkStore()
        results <- Future.sequence(requests.map(req => {
          store.lookupEntryRequestById(req.contractId)
        }))
      } yield results shouldBe requests.map(req => QueryResult(tx2Offset, Some(req)))
    }

    "lookup a non-ingested entry by id" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryRequestById(Primitive.ContractId(s"non-existent#1"))
      } yield result shouldBe QueryResult(tx2Offset, None)
    }

    "lookup an ingested entry by name" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryByName("entry-name-1")
      } yield result shouldBe QueryResult(tx2Offset, Some(entries(1)))
    }

    "lookup an non-ingested entry by party" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryByParty(providerParty)
      } yield result shouldBe QueryResult(tx2Offset, None)
    }

    "lookup an ingested entry by party" in {
      for {
        store <- mkStore()
        result <- store.lookupEntryByParty(userParty(1))
      } yield result shouldBe QueryResult(tx2Offset, Some(entries(1)))
    }

    "list all ingested and active directory entries" in {
      for {
        store <- mkStore()
        result <- store.listEntries()
      } yield result shouldBe QueryResult(tx2Offset, entries)
    }

    "stream all ingested entry requests" in {
      for {
        store <- mkStore()
        streamedReqs <- store.streamEntryRequests().take(requests.length.toLong).runWith(Sink.seq)
      } yield streamedReqs shouldBe requests
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
            if (cos.length == requests.length) reqsPromise.success(())
            if (cos.length == requests.length + txRequests.length) extraReqsPromise.success(())
          })
        () <- reqsPromise.future
        // sleep for 10 millis so the source had a chance to produce extra elements if it was buggy
        _ = Threading.sleep(10)
        reqsBeforeIngestion = acc.get()
        () <- store.acsIngestionSink.ingestTransaction(tx3)
        () <- extraReqsPromise.future
        reqsAfterIngestion = acc.get()
      } yield {
        reqsBeforeIngestion shouldBe requests
        reqsAfterIngestion shouldBe (requests ++ txRequests)
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
        _ <- store.acsIngestionSink.ingestTransaction(tx4)
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
