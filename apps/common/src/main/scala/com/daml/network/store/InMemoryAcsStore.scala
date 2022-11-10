package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.{
  Contract,
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
}
import com.daml.ledger.javaapi.data.{
  ArchivedEvent,
  CreatedEvent,
  Template,
  Transaction,
  TransactionFilter,
}
import com.daml.network.util.JavaContract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{NoTracing, TraceContext}
import monocle.macros.syntax.lens.*

import scala.collection.immutable
import scala.collection.immutable.SortedMap
import scala.concurrent._
import scala.jdk.CollectionConverters.*

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

  val ingestionSink: AcsStore.IngestionSink = new AcsStore.IngestionSink with NoTracing {

    override def transactionFilter: TransactionFilter = contractFilter.transactionFilter

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
  }

  /** The implementation is idempotent. */
  override def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit] = {
    val alreadyIngested = stateVar.offset.exists(_ >= offset)
    if (alreadyIngested) {
      Future.successful(())
    } else {
      updateState(_.addOffsetToSignal(offset)).flatMap(p => p.future)
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

  private def requireInScope[TC, TCid, T](templateCompanion: ContractCompanion[TC, TCid, T]): Unit =
    require(
      contractFilter.mightContain(templateCompanion),
      s"template ${templateCompanion.TEMPLATE_ID} is part of the contract filter",
    )

  private def requireInScope[I, Id, View](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  ): Unit =
    require(
      contractFilter.mightContain(interfaceCompanion),
      s"interface ${interfaceCompanion.TEMPLATE_ID} is part of the contract filter",
    )

  private def findContract[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  )(p: T => Boolean): Future[QueryResult[Option[T]]] = {
    offsetAndStateAfterIngestingAcs().map({ case (off, st) =>
      val optEntry = st.createEvents.values.collectFirst(Function.unlift(ev => {
        for {
          contract <- fromCreatedEvent(ev)
          if p(contract)
        } yield contract
      }))
      QueryResult(off, optEntry)
    })
  }

  def findContract[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(p: JavaContract[TCid, T] => Boolean): Future[QueryResult[Option[JavaContract[TCid, T]]]] = {
    requireInScope(templateCompanion)
    findContract(JavaContract.fromCreatedEvent(templateCompanion))(p)
  }

  def findContract[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(p: JavaContract[Id, View] => Boolean): Future[QueryResult[Option[JavaContract[Id, View]]]] = {
    requireInScope(interfaceCompanion)
    findContract(JavaContract.fromCreatedEvent(interfaceCompanion))(p)
  }

  private def listContracts[T](
      fromCreatedEvent: CreatedEvent => Option[T],
      filter: T => Boolean,
  ): Future[QueryResult[Seq[T]]] = {
    offsetAndStateAfterIngestingAcs().map { case (off, st) =>
      val result = st.createEvents.values
        .collect(Function.unlift(ev => fromCreatedEvent(ev)))
        .toSeq
      QueryResult(off, result)
    }
  }

  def listContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: JavaContract[TCid, T] => Boolean,
  ): Future[QueryResult[Seq[JavaContract[TCid, T]]]] = {
    requireInScope(templateCompanion)
    listContracts(JavaContract.fromCreatedEvent(templateCompanion), filter)
  }

  def listContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View],
      filter: JavaContract[Id, View] => Boolean,
  ): Future[QueryResult[Seq[JavaContract[Id, View]]]] = {
    requireInScope(interfaceCompanion)
    listContracts(JavaContract.fromCreatedEvent(interfaceCompanion), filter)
  }

  private def lookupContractById[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  )(id: ContractId[_]): Future[QueryResult[Option[T]]] = {
    offsetAndStateAfterIngestingAcs().map { case (off, st) =>
      st.createEventsById.get(id.contractId) match {
        case None =>
          QueryResult(off, None)
        case Some(evRev) =>
          QueryResult(
            off,
            st.createEvents
              .get(evRev)
              .flatMap(ev => fromCreatedEvent(ev)),
          )
      }
    }
  }

  def lookupContractById[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T]): Future[QueryResult[Option[JavaContract[TCid, T]]]] = {
    requireInScope(templateCompanion)
    lookupContractById(JavaContract.fromCreatedEvent(templateCompanion))(id)
  }

  def lookupContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id): Future[QueryResult[Option[JavaContract[Id, View]]]] = {
    requireInScope(interfaceCompanion)
    lookupContractById(JavaContract.fromCreatedEvent(interfaceCompanion))(id)
  }

  private def streamContracts[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  ): Source[T, NotUsed] = {
    Source.unfoldAsync(0: Long)(eventNumber =>
      nextActiveContract(fromCreatedEvent, eventNumber).map(Some(_))
    )
  }

  def streamContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Source[JavaContract[TCid, T], NotUsed] = {
    requireInScope(templateCompanion)
    streamContracts(JavaContract.fromCreatedEvent(templateCompanion))
  }

  def streamContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  ): Source[JavaContract[Id, View], NotUsed] = {
    requireInScope(interfaceCompanion)
    streamContracts(JavaContract.fromCreatedEvent(interfaceCompanion))
  }

  private def nextActiveContract[T](
      fromCreated: CreatedEvent => Option[T],
      startingFromIncl: Long,
  ): Future[(Long, T)] = {
    val st = stateVar
    val optEntry = st.createEvents
      .iteratorFrom(startingFromIncl)
      .collectFirst(
        Function.unlift(ev =>
          fromCreated(ev._2)
            .map(co => (ev._1, co))
        )
      )
    optEntry match {
      case None =>
        st.offsetChanged.future.flatMap(_ => nextActiveContract(fromCreated, st.nextEventNumber))
      case Some((eventNumber, co)) => Future((eventNumber + 1, co))
    }
  }

  override def close(): Unit = ()
}

object InMemoryAcsStore {

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
        case (evNum, ev) => s"    $evNum -> ${ev.getTemplateId} -- ${ev.getContractId}"
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
        createEventsById = createEventsById + (ev.getContractId -> nextEventNumber),
        offsetChanged = offsetChanged,
        offsetIngestionsToSignal = offsetIngestionsToSignal,
      )
    }

    def ingestArchivedEvent(ev: ArchivedEvent): State =
      createEventsById.get(ev.getContractId) match {
        case None =>
          // NOTE: this will occur when ingesting an archive for a create event that was filtered on ingestion
          this
        case Some(eventNumber) => {
          assert(createEvents.contains(eventNumber), s"event number $eventNumber defined")
          State(
            offset = offset,
            nextEventNumber = nextEventNumber,
            createEvents = createEvents - eventNumber,
            createEventsById = createEventsById - ev.getContractId,
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
      val stNew = tx.getEvents.asScala.foldLeft(this)((st, ev) =>
        ev match {
          case ev: CreatedEvent => if (p(ev)) st.ingestCreatedEvent(ev) else st
          case ev: ArchivedEvent => st.ingestArchivedEvent(ev)
          case _ =>
            throw new IllegalArgumentException(s"Encountered unknown event: $ev")
        }
      )
      val offsetsToRemove = stNew.computeOffsetsToRemove(tx.getOffset)
      (
        State(
          offset = Some(tx.getOffset),
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
