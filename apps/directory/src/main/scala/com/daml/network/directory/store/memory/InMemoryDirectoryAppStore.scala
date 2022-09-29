package com.daml.network.directory.store.memory
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.{ArchivedEvent, CreatedEvent, Event}
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.api.v1.transaction_filter.TransactionFilter
import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.codegen.CN.{Directory => directoryCodegen, Wallet => walletCodegen}
import com.daml.network.directory.store.{DirectoryAppStore, QueryResult}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.immutable
import scala.concurrent._

/** Example for in-memory store in the store pattern. */
class InMemoryDirectoryAppStore(
    override protected val loggerFactory: NamedLoggerFactory
)(implicit
    ec: ExecutionContext
) extends DirectoryAppStore
    with NamedLogging {

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryDirectoryAppStore.State =
    InMemoryDirectoryAppStore.State(
      None,
      0,
      immutable.SortedMap.empty,
      immutable.Map.empty,
      Promise(),
    )

  // Boolean flag to enable very verbose state update logging
  private val logAllStateUpdates: Boolean = false

  private val finishedAcsIngestion: Promise[Unit] = Promise()
  private val providerParty: Promise[PartyId] = Promise()

  private def updateState[T](
      f: InMemoryDirectoryAppStore.State => (InMemoryDirectoryAppStore.State, T)
  )(implicit traceContext: TraceContext): Future[T] = {
    Future {
      blocking {
        synchronized {
          val stOld = stateVar
          val (stNew, result) = f(stateVar)
          stateVar = stNew
          if (logAllStateUpdates)
            logger.debug(s"Updated state\nstOld=\n${stOld.pretty}\nstNew=\n${stNew.pretty}")
          result
        }
      }
    }
  }

  override def transactionFilter: Future[TransactionFilter] =
    getProviderParty().map(provider =>
      CoinLedgerConnection.transactionFilterByParty(
        Map(
          provider ->
            Seq(
              // TODO(#790): share this list with the ingestion predicate
              directoryCodegen.DirectoryEntry.id,
              directoryCodegen.DirectoryEntryRequest.id,
              directoryCodegen.DirectoryEntryOffer.id,
              walletCodegen.AcceptedAppPayment.id,
              directoryCodegen.DirectoryInstallRequest.id,
              directoryCodegen.DirectoryInstall.id,
            )
        )
      )
    )

  override def setProviderParty(partyId: PartyId): Future[Unit] =
    Future { providerParty.success(partyId) }

  override def getProviderParty(): Future[PartyId] = providerParty.future

  override def ingestActiveContracts(
      evs: Seq[CreatedEvent]
  )(implicit traceContext: TraceContext): Future[Unit] =
    getProviderParty().flatMap(provider =>
      updateState(st =>
        (st.ingestCreatedEvents(evs.filter(ingestionPredicate(provider.toPrim))), ())
      )
    )

  override def switchToIngestingTransactions(
      acsOffset: String
  )(implicit traceContext: TraceContext): Future[Unit] =
    updateState(
      _.switchToIngestingTransactions(acsOffset)
    ).map(offsetChanged => {
      offsetChanged.success(())
      finishedAcsIngestion.success(())
    })

  override def ingestTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    getProviderParty().flatMap(provider =>
      updateState(
        _.ingestTransaction(tx, ingestionPredicate(provider.toPrim))
      ).map(offsetChanged => offsetChanged.success(()))
    )

  def offsetAndStateAfterIngestingAcs(): Future[(String, InMemoryDirectoryAppStore.State)] =
    finishedAcsIngestion.future
      .map(_ => {
        val st = stateVar
        (
          st.offset.getOrElse(sys.error("Offset must be defined, as the ACS was ingested")),
          st,
        )
      })

  private def ingestionPredicate(provider: Primitive.Party)(ev: CreatedEvent): Boolean =
    // TODO(#790): branch based on the template-id instead of testing all of them
    DecodeUtil
      .decodeCreated(directoryCodegen.DirectoryEntry)(ev)
      .exists(co => co.value.provider == provider) ||
      DecodeUtil
        .decodeCreated(directoryCodegen.DirectoryEntryRequest)(ev)
        .exists(co => co.value.entry.provider == provider) ||
      DecodeUtil
        .decodeCreated(directoryCodegen.DirectoryEntryOffer)(ev)
        .exists(co => co.value.entry.provider == provider) ||
      DecodeUtil
        .decodeCreated(walletCodegen.AcceptedAppPayment)(ev)
        .exists(co => co.value.receiver == provider) ||
      DecodeUtil
        .decodeCreated(directoryCodegen.DirectoryInstallRequest)(ev)
        .exists(co => co.value.provider == provider) ||
      DecodeUtil
        .decodeCreated(directoryCodegen.DirectoryInstall)(ev)
        .exists(co => co.value.provider == provider)

  def findContract[T](
      templateCompanion: TemplateCompanion[T]
  )(p: Contract[T] => Boolean): Future[QueryResult[Option[Contract[T]]]] =
    offsetAndStateAfterIngestingAcs().map({ case (off, st) =>
      val optEntry = st.createEvents
        .collectFirst(Function.unlift(ev => {
          for {
            contract <- DecodeUtil
              .decodeCreated(templateCompanion)(ev._2)
              .map(Contract.fromCodegenContract)
            if p(contract)
          } yield contract
        }))
      QueryResult(off, optEntry)
    })

  def listContracts[T](
      templateCompanion: TemplateCompanion[T]
  ): Future[QueryResult[Seq[Contract[T]]]] =
    offsetAndStateAfterIngestingAcs().map { case (off, st) =>
      val result = st.createEvents
        .collect(Function.unlift(ev => {
          DecodeUtil
            .decodeCreated(templateCompanion)(ev._2)
            .map(Contract.fromCodegenContract)
        }))
        .toSeq
      QueryResult(off, result)
    }

  def lookupContractById[T](
      templateCompanion: TemplateCompanion[T]
  )(id: Primitive.ContractId[T]): Future[QueryResult[Option[Contract[T]]]] =
    offsetAndStateAfterIngestingAcs().map { case (off, st) =>
      st.createEventsById.get(ApiTypes.ContractId.unwrap(id)) match {
        case None =>
          QueryResult(off, None)
        case Some(evRev) =>
          QueryResult(
            off,
            st.createEvents
              .get(evRev)
              .flatMap(ev => DecodeUtil.decodeCreated(templateCompanion)(ev))
              .map(Contract.fromCodegenContract),
          )
      }
    }

  override def lookupInstall(
      user: PartyId
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryInstall]]]] =
    findContract(directoryCodegen.DirectoryInstall)(co => co.payload.user == user.toPrim)

  override def lookupEntryByName(
      name: String
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntry]]]] =
    // TODO(#790): add an index to make this more efficient
    findContract(directoryCodegen.DirectoryEntry)(co => co.payload.name == name)

  override def lookupEntryByParty(
      partyId: PartyId
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntry]]]] =
    // TODO(#790): add an index to make this more efficient
    findContract(directoryCodegen.DirectoryEntry)(co => co.payload.user == partyId.toPrim)

  def lookupEntryOfferById(
      id: Primitive.ContractId[directoryCodegen.DirectoryEntryOffer]
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntryOffer]]]] =
    lookupContractById(directoryCodegen.DirectoryEntryOffer)(id)

  override def lookupEntryRequestById(
      id: Primitive.ContractId[directoryCodegen.DirectoryEntryRequest]
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntryRequest]]]] =
    lookupContractById(directoryCodegen.DirectoryEntryRequest)(id)

  def streamActiveContracts[T](
      templateCompanion: TemplateCompanion[T]
  ): Source[Contract[T], NotUsed] =
    Source.unfoldAsync(0: Long)(eventNumber =>
      nextActiveContract(templateCompanion, eventNumber).map(Some(_))
    )

  private def nextActiveContract[T](
      templateCompanion: TemplateCompanion[T],
      startingFromIncl: Long,
  ): Future[(Long, Contract[T])] = {
    val st = stateVar
    val optEntry = st.createEvents
      .iteratorFrom(startingFromIncl)
      .collectFirst(Function.unlift(ev => {
        DecodeUtil
          .decodeCreated(templateCompanion)(ev._2)
          .map(co => (ev._1, Contract.fromCodegenContract(co)))
      }))
    optEntry match {
      case None =>
        st.offsetChanged.future.flatMap(_ =>
          nextActiveContract(templateCompanion, st.nextEventNumber)
        )
      case Some((eventNumber, co)) => Future((eventNumber + 1, co))
    }
  }

  override def streamAcceptedAppPayments()
      : Source[Contract[walletCodegen.AcceptedAppPayment], NotUsed] =
    streamActiveContracts(walletCodegen.AcceptedAppPayment)

  override def streamEntryRequests()
      : Source[Contract[directoryCodegen.DirectoryEntryRequest], NotUsed] =
    streamActiveContracts(directoryCodegen.DirectoryEntryRequest)

  // TODO(#790): consider removing these custom methods in favor of calling the `listContracts` method directly
  def listEntries(): Future[QueryResult[Seq[Contract[directoryCodegen.DirectoryEntry]]]] =
    listContracts(directoryCodegen.DirectoryEntry)

  override def streamInstallRequests()
      : Source[Contract[directoryCodegen.DirectoryInstallRequest], NotUsed] =
    streamActiveContracts(directoryCodegen.DirectoryInstallRequest)

  override def close(): Unit = ()

}

object InMemoryDirectoryAppStore {
  case class State(
      offset: Option[String],
      nextEventNumber: Long,
      createEvents: immutable.SortedMap[Long, CreatedEvent],
      createEventsById: immutable.Map[String, Long],
      offsetChanged: Promise[Unit],
  ) {
    def pretty: String = {
      def prettyEntry(entry: (Long, CreatedEvent)) = entry match {
        case (evNum, ev) => s"    $evNum -> ${ev.templateId} -- ${ev.contractId}"
      }
      val lines = Seq(
        s"  offset=$offset",
        s"  nextEventNumber=$nextEventNumber",
        s"  createEventsById=",
      ) ++ createEvents.map(prettyEntry)

      lines.mkString("\n")
    }
    def ingestCreatedEvent(ev: CreatedEvent): State = {
      State(
        offset = offset,
        nextEventNumber = nextEventNumber + 1,
        createEvents = createEvents + (nextEventNumber -> ev),
        createEventsById = createEventsById + (ev.contractId -> nextEventNumber),
        offsetChanged = offsetChanged,
      )
    }

    def ingestArchivedEvent(ev: ArchivedEvent): State =
      createEventsById.get(ev.contractId) match {
        case None =>
          // NOTE: this will occur when ingesting an archive for a create event that was filtered on ingestion
          this
        case Some(eventNumber) => {
          assert(createEvents.contains(eventNumber), s"event number $eventNumber defined")
          State(
            offset = offset,
            nextEventNumber = nextEventNumber,
            createEvents = createEvents - eventNumber,
            createEventsById = createEventsById - ev.contractId,
            offsetChanged = offsetChanged,
          )
        }
      }

    def ingestCreatedEvents(evs: Seq[CreatedEvent]): State = {
      assert(offset.isEmpty, "state was not switched to tx ingestion yet")
      evs.foldLeft(this)((st, ev) => st.ingestCreatedEvent(ev))
    }

    def switchToIngestingTransactions(acsOffset: String): (State, Promise[Unit]) = {
      assert(offset.isEmpty, "state was not switched to tx ingestion yet")
      (
        State(
          offset = Some(acsOffset),
          nextEventNumber = nextEventNumber,
          createEvents = createEvents,
          createEventsById = createEventsById,
          offsetChanged = Promise(),
        ),
        offsetChanged,
      )
    }

    /** Ingest a transaction with certain create events filtered out. */
    def ingestTransaction(tx: Transaction, p: CreatedEvent => Boolean): (State, Promise[Unit]) = {
      val stNew = tx.events.foldLeft(this)((st, ev) =>
        ev.event match {
          case Event.Event.Created(ev) => if (p(ev)) st.ingestCreatedEvent(ev) else st
          case Event.Event.Archived(ev) => st.ingestArchivedEvent(ev)
          case Event.Event.Empty =>
            // TODO(#790): log an error instead of bailing out
            throw new RuntimeException("Encountered unknown event")
        }
      )
      (
        State(
          offset = Some(tx.offset),
          nextEventNumber = stNew.nextEventNumber,
          createEvents = stNew.createEvents,
          createEventsById = stNew.createEventsById,
          offsetChanged = Promise(),
        ),
        offsetChanged,
      )
    }

  }
}
