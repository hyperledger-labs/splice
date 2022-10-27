package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.{ArchivedEvent, CreatedEvent, Event}
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.api.v1.transaction_filter.TransactionFilter
import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.tracing.{NoTracing, TraceContext}
import monocle.macros.syntax.lens.*

import scala.collection.immutable
import scala.collection.immutable.SortedMap
import scala.concurrent.*

/** In-memory implementation of an [[AcsStore]] intended to be embedded in the
  * in-memory implementations of application-specific stores.
  */
class InMemoryAcsStore(
    override protected val loggerFactory: NamedLoggerFactory,
    override val contractFilter: AcsStore.ContractFilter,

    // Boolean flag to enable very verbose state update logging
    logAllStateUpdates: Boolean = false,
)(implicit
    ec: ExecutionContext
) extends AcsStore
    with NamedLogging {

  import AcsStore.QueryResult

  private val finishedAcsIngestion: Promise[Unit] = Promise()

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryAcsStore.State =
    InMemoryAcsStore.State(
      None,
      0,
      immutable.SortedMap.empty,
      immutable.Map.empty,
      Promise(),
      SortedMap.empty,
    )

  val ingestionSink: AcsStore.IngestionSink = new AcsStore.IngestionSink with NoTracing {

    override def transactionFilter: TransactionFilter = contractFilter.transactionFilter

    private def updateState[T](
        f: InMemoryAcsStore.State => (InMemoryAcsStore.State, T)
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

    override def ingestActiveContracts(
        evs: Seq[CreatedEvent]
    ): Future[Unit] =
      updateState(st => (st.ingestCreatedEvents(evs.filter(contractFilter.contains)), ()))

    override def switchToIngestingTransactions(
        acsOffset: String
    ): Future[Unit] =
      updateState(
        _.switchToIngestingTransactions(acsOffset)
      ).map {
        case (offsetChanged, offsetIngestionsToSignal) => {
          offsetIngestionsToSignal.foreach(_.success(()))
          offsetChanged.success(())
          finishedAcsIngestion.success(())
        }
      }

    override def ingestTransaction(tx: Transaction): Future[Unit] =
      updateState(
        _.ingestTransaction(tx, contractFilter.contains)
      ).map {
        case (offsetChanged, offsetIngestionsToSignal) => {
          offsetIngestionsToSignal.foreach(_.success(()))
          offsetChanged.success(())
        }
      }

    /** The implementation is idempotent. */
    override def signalWhenIngested(offset: String): Future[Unit] = {
      val alreadyIngested = stateVar.offset.exists(_ >= offset)
      if (alreadyIngested) {
        Future.successful(())
      } else {
        updateState(_.addOffsetToSignal(offset)).flatMap(p => p.future)
      }
    }
  }

  private def offsetAndStateAfterIngestingAcs(): Future[(String, InMemoryAcsStore.State)] =
    finishedAcsIngestion.future
      .map(_ => {
        val st = stateVar
        (
          st.offset.getOrElse(sys.error("Offset must be defined, as the ACS was ingested")),
          st,
        )
      })

  private def requireInScope[T](templateCompanion: TemplateCompanion[T]): Unit =
    require(
      contractFilter.mightContain(templateCompanion),
      s"template ${templateCompanion.id} is part of the contract filter",
    )

  def findContract[T](
      templateCompanion: TemplateCompanion[T]
  )(p: Contract[T] => Boolean): Future[QueryResult[Option[Contract[T]]]] = {
    requireInScope(templateCompanion)
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
  }

  def listContracts[T](
      templateCompanion: TemplateCompanion[T],
      filter: Contract[T] => Boolean = (_: Contract[T]) => true,
  ): Future[QueryResult[Seq[Contract[T]]]] = {
    requireInScope(templateCompanion)
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
  }

  def lookupContractById[T](
      templateCompanion: TemplateCompanion[T]
  )(id: Primitive.ContractId[T]): Future[QueryResult[Option[Contract[T]]]] = {
    requireInScope(templateCompanion)
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
  }

  def streamContracts[T](
      templateCompanion: TemplateCompanion[T]
  ): Source[Contract[T], NotUsed] = {
    requireInScope(templateCompanion)
    Source.unfoldAsync(0: Long)(eventNumber =>
      nextActiveContract(templateCompanion, eventNumber).map(Some(_))
    )
  }

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

  override def close(): Unit = ()
}

object InMemoryAcsStore {

  /**  Internal state of the InMemoryAcsStore
    * @param offsetIngestionsToSignal we assume that the underlying map is sorted to efficiently remove elements from it.
    */
  private case class State(
      offset: Option[String],
      nextEventNumber: Long,
      createEvents: immutable.SortedMap[Long, CreatedEvent],
      createEventsById: immutable.Map[String, Long],
      offsetChanged: Promise[Unit],
      offsetIngestionsToSignal: SortedMap[String, Promise[Unit]],
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

      lines.mkString(System.lineSeparator())
    }

    def ingestCreatedEvent(ev: CreatedEvent): State = {
      State(
        offset = offset,
        nextEventNumber = nextEventNumber + 1,
        createEvents = createEvents + (nextEventNumber -> ev),
        createEventsById = createEventsById + (ev.contractId -> nextEventNumber),
        offsetChanged = offsetChanged,
        offsetIngestionsToSignal = offsetIngestionsToSignal,
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
            offsetIngestionsToSignal = offsetIngestionsToSignal,
          )
        }
      }

    def ingestCreatedEvents(evs: Seq[CreatedEvent]): State = {
      assert(offset.isEmpty, "state was not switched to tx ingestion yet")
      evs.foldLeft(this)((st, ev) => st.ingestCreatedEvent(ev))
    }

    def switchToIngestingTransactions(
        acsOffset: String
    ): (State, (Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isEmpty, "state was not switched to tx ingestion yet")
      val offsetsToRemove = computeOffsetsToRemove(acsOffset)
      (
        State(
          offset = Some(acsOffset),
          nextEventNumber = nextEventNumber,
          createEvents = createEvents,
          createEventsById = createEventsById,
          offsetChanged = Promise(),
          offsetIngestionsToSignal = offsetIngestionsToSignal.removedAll(offsetsToRemove.keys),
        ),
        (offsetChanged, offsetsToRemove.values),
      )
    }

    // since the ACS store subscribes to the flat transaction stream, it may not see
    // offsets assigned to the transaction tree stream and thus we cannot only check for exact matches
    private def computeOffsetsToRemove(offset: String): Map[String, Promise[Unit]] =
      // because we use a SortedMap, we can use takeWhile.
      offsetIngestionsToSignal.takeWhile(_._1 <= offset)

    /** Ingest a transaction while filtering out create events that do not satisfy the given predicate. */
    def ingestTransaction(
        tx: Transaction,
        p: CreatedEvent => Boolean,
    ): (State, (Promise[Unit], Iterable[Promise[Unit]])) = {
      val stNew = tx.events.foldLeft(this)((st, ev) =>
        ev.event match {
          case Event.Event.Created(ev) => if (p(ev)) st.ingestCreatedEvent(ev) else st
          case Event.Event.Archived(ev) => st.ingestArchivedEvent(ev)
          case Event.Event.Empty =>
            throw new IllegalArgumentException(s"Encountered unknown event: $ev")
        }
      )
      val offsetsToRemove = stNew.computeOffsetsToRemove(tx.offset)
      (
        State(
          offset = Some(tx.offset),
          nextEventNumber = stNew.nextEventNumber,
          createEvents = stNew.createEvents,
          createEventsById = stNew.createEventsById,
          offsetChanged = Promise(),
          offsetIngestionsToSignal = stNew.offsetIngestionsToSignal.removedAll(offsetsToRemove.keys),
        ),
        (offsetChanged, offsetsToRemove.values),
      )
    }

    /** Update the state by adding another offset whose ingestion should be signalled. If the signalling of that
      * offset has already been requested, don't change the state.
      */
    def addOffsetToSignal(offset: String): (State, Promise[Unit]) = {
      offsetIngestionsToSignal.get(offset) match {
        case None =>
          val p = Promise[Unit]()
          (this.focus(_.offsetIngestionsToSignal).modify(_ + (offset -> p)), p)
        case Some(existingP) => (this, existingP)
      }
    }
  }
}
