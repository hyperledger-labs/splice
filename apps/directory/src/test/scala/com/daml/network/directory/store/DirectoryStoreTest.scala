package com.daml.network.directory.store

import akka.actor.ActorSystem
import akka.stream.scaladsl.*
import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{ArchivedEvent, CreatedEvent, Event, Transaction}
import com.daml.network.codegen.java.cn.{directory => directoryCodegen}
import com.daml.network.store.JavaAcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.resource.MemoryStorage
import com.digitalasset.canton.topology.PartyId
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

class DirectoryStoreTest extends AsyncWordSpec with BaseTest {

  implicit val actorSystem: ActorSystem = ActorSystem("DirectoryAppStoreTest")

  def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")
  val providerParty: PartyId = mkPartyId("provider")
  val svcParty: PartyId = mkPartyId("svc")
  def userParty(i: Int) = mkPartyId(s"user-$i")

  def directoryEntry(
      number: Int,
      provider: PartyId,
      user: PartyId,
      name: String,
      expiresAt: Instant,
  ): JavaContract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry] =
    JavaContract(
      contractId = new directoryCodegen.DirectoryEntry.ContractId(s"de#$number"),
      payload = new directoryCodegen.DirectoryEntry(
        user.toProtoPrimitive,
        provider.toProtoPrimitive,
        name,
        expiresAt,
      ),
    )

  def directoryEntryRequest(
      number: Int,
      provider: PartyId,
      user: PartyId,
      name: String,
  ): JavaContract[
    directoryCodegen.DirectoryEntryRequest.ContractId,
    directoryCodegen.DirectoryEntryRequest,
  ] =
    JavaContract(
      contractId = new directoryCodegen.DirectoryEntryRequest.ContractId(s"der#$number"),
      payload = new directoryCodegen.DirectoryEntryRequest(
        provider.toProtoPrimitive,
        user.toProtoPrimitive,
        name,
        BigDecimal(1.0).bigDecimal,
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
      templateId = contract.payload.getContractTypeId,
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
      templateId = contract.payload.getContractTypeId,
      witnessParties = Seq.empty.asJava,
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

  val expiry: Instant =
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

  val effectiveAt: Instant =
    Primitive.Timestamp.discardNanos(Instant.EPOCH).getOrElse(fail("Failed to convert timestamp"))

  val tx1: Transaction = new Transaction(
    transactionId = "",
    commandId = "",
    workflowId = "",
    effectiveAt = effectiveAt,
    offset = tx1Offset,
    events = (entriesToArchive.map(toArchivedEvent)
      ++ requests.map(toCreatedEvent)).asJava,
  )

  private def createCreateTx[TCid <: ContractId[T], T](
      offset: String,
      createRequests: Seq[JavaContract[TCid, T]],
  ) = new Transaction(
    transactionId = "",
    commandId = "",
    workflowId = "",
    effectiveAt = effectiveAt,
    offset = offset,
    events = createRequests.map[Event](toCreatedEvent).asJava,
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
        result <- store.lookupEntryRequestById(new ContractId(s"non-existent#1"))
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
      val acc: AtomicReference[List[JavaContract[
        directoryCodegen.DirectoryEntryRequest.ContractId,
        directoryCodegen.DirectoryEntryRequest,
      ]]] =
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
