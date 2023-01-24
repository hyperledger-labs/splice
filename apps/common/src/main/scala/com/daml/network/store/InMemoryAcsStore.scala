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
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Template, TransactionTree}
import com.daml.network.util.{JavaContract, Trees}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import monocle.macros.syntax.lens.*

import scala.collection.immutable.SortedMap
import scala.collection.{immutable, mutable}
import scala.concurrent.*

/** In-memory implementation of an [[AcsStore]] intended to be embedded in the
  * in-memory implementations of application-specific stores.
  */
class InMemoryAcsStore(
    override protected val loggerFactory: NamedLoggerFactory,
    override val contractFilter: AcsStore.ContractFilter,
    futureSupervisor: FutureSupervisor,

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

  val ingestionSink: AcsStore.IngestionSink = new AcsStore.IngestionSink {

    override def ingestionFilter = contractFilter.ingestionFilter

    override def getLastIngestedOffset: Future[Option[String]] = Future.successful(stateVar.offset)

    override def ingestActiveContracts(
        evs: Seq[CreatedEvent]
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(_.ingestCreatedEvents(evs, contractFilter.contains)).map(summary =>
        logger.debug(show"Ingested ACS update $summary")
      )

    override def switchToIngestingTransactions(
        acsOffset: String
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.switchToIngestingTransactions(acsOffset)
      ).map {
        case (offsetChanged, offsetIngestionsToSignal) => {
          logger.debug(show"Ingested complete ACS at offset ${acsOffset.singleQuoted}")
          offsetIngestionsToSignal.foreach(_.success(()))
          offsetChanged.success(())
          finishedAcsIngestion.success(())
        }
      }

    override def ingestTransaction(
        tx: TransactionTree
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestTransaction(tx, contractFilter.contains)
      ).map {
        case (summary, offsetChanged, offsetIngestionsToSignal) => {
          logger.debug(show"Ingested transaction $summary")
          offsetIngestionsToSignal.foreach(_.success(()))
          offsetChanged.success(())
        }
      }
  }

  /** The implementation is idempotent. */
  override def signalWhenIngested(
      offset: String
  )(implicit tc: TraceContext): Future[Unit] = {
    updateState[Future[Unit]](state =>
      if (state.offset.exists(_ >= offset)) {
        (state, Future.unit)
      } else {
        val (newState, promise) = state.addOffsetToSignal(offset)
        val future = futureSupervisor.supervised(s"signalWhenIngested($offset)")(promise.future)
        (newState, future)
      }
    ).flatten
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

  private def findContractWithOffset[T](
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

  def findContractWithOffset[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(p: JavaContract[TCid, T] => Boolean): Future[QueryResult[Option[JavaContract[TCid, T]]]] = {
    requireInScope(templateCompanion)
    findContractWithOffset(JavaContract.fromCreatedEvent(templateCompanion))(p)
  }

  def findContractWithOffset[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(p: JavaContract[Id, View] => Boolean): Future[QueryResult[Option[JavaContract[Id, View]]]] = {
    requireInScope(interfaceCompanion)
    findContractWithOffset(JavaContract.fromCreatedEvent(interfaceCompanion))(p)
  }

  private def listContracts[T](
      fromCreatedEvent: CreatedEvent => Option[T],
      filter: T => Boolean,
      limit: Option[Long],
  ): Future[Seq[T]] = {
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      st.createEvents.values
        .collect(Function.unlift(ev => fromCreatedEvent(ev)))
        .take(limit.fold(Int.MaxValue)(_.intValue()))
        .filter(filter)
        .toSeq
    }
  }

  def listContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: JavaContract[TCid, T] => Boolean,
      limit: Option[Long],
  ): Future[Seq[JavaContract[TCid, T]]] = {
    requireInScope(templateCompanion)
    listContracts(JavaContract.fromCreatedEvent(templateCompanion), filter, limit)
  }

  def listContractsI[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View],
      filter: JavaContract[Id, View] => Boolean,
      limit: Option[Long],
  ): Future[Seq[JavaContract[Id, View]]] = {
    requireInScope(interfaceCompanion)
    listContracts(JavaContract.fromCreatedEvent(interfaceCompanion), filter, limit)
  }

  private def lookupContractById[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  )(id: ContractId[_]): Future[Option[T]] = {
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      st.createEventsById.get(id.contractId) match {
        case None => None
        case Some(evRev) =>
          st.createEvents.get(evRev).flatMap(ev => fromCreatedEvent(ev))
      }
    }
  }

  def lookupContractById[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T]): Future[Option[JavaContract[TCid, T]]] = {
    requireInScope(templateCompanion)
    lookupContractById(JavaContract.fromCreatedEvent(templateCompanion))(id)
  }

  def lookupContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id): Future[Option[JavaContract[Id, View]]] = {
    requireInScope(interfaceCompanion)
    lookupContractById(contractFilter.decodeInterface(interfaceCompanion))(id)
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

  def signalWhenIngested[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Future[Unit] = {
    requireInScope(templateCompanion)
    nextActiveContract(JavaContract.fromCreatedEvent(templateCompanion), 0).map(ssss => ())
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

      lines.mkString("\n")
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

    def ingestArchivedEvent(ev: ExercisedEvent): (State, Boolean) =
      createEventsById.get(ev.getContractId) match {
        case None =>
          // NOTE: this will occur when ingesting an archive for a create event that was filtered on ingestion
          (this, false)
        case Some(eventNumber) => {
          assert(createEvents.contains(eventNumber), s"event number $eventNumber defined")
          (
            State(
              offset = offset,
              nextEventNumber = nextEventNumber,
              createEvents = createEvents - eventNumber,
              createEventsById = createEventsById - ev.getContractId,
              offsetChanged = offsetChanged,
              offsetIngestionsToSignal = offsetIngestionsToSignal,
            ),
            true,
          )
        }
      }

    def ingestCreatedEvents(
        evs: Seq[CreatedEvent],
        p: CreatedEvent => Boolean,
    ): (State, IngestionSummary) = {
      assert(offset.isEmpty, "state was not switched to tx ingestion yet")
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredCreatedEvents = 0
      val ingestedCreatedEvents = mutable.ListBuffer[CreatedEvent]()

      val stNew = evs.foldLeft(this)((st, ev) =>
        if (p(ev)) {
          ingestedCreatedEvents.append(ev)
          st.ingestCreatedEvent(ev)
        } else {
          numFilteredCreatedEvents += 1
          st
        }
      )
      (
        stNew,
        IngestionSummary(
          None,
          ingestedCreatedEvents,
          numFilteredCreatedEvents,
          mutable.ListBuffer.empty,
          0,
          None,
          stNew.createEvents.size,
        ),
      )
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
        tx: TransactionTree,
        p: CreatedEvent => Boolean,
    ): (State, (IngestionSummary, Promise[Unit], Iterable[Promise[Unit]])) = {
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredCreatedEvents = 0
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredArchivedEvents = 0
      val ingestedCreatedEvents = mutable.ListBuffer[CreatedEvent]()
      val ingestedArchivedEvents = mutable.ListBuffer[ExercisedEvent]()

      val stNew = Trees.foldTree(tx, this)(
        (st, ev, _) =>
          if (p(ev)) {
            ingestedCreatedEvents.append(ev)
            st.ingestCreatedEvent(ev)
          } else {
            numFilteredCreatedEvents += 1
            st
          },
        (st, ev, _) =>
          if (ev.isConsuming) {
            val (newSt, ingested) = st.ingestArchivedEvent(ev)
            if (ingested)
              ingestedArchivedEvents.append(ev)
            else
              numFilteredArchivedEvents += 1
            newSt
          } else {
            st
          },
      )
      val offsetsToRemove = stNew.computeOffsetsToRemove(tx.getOffset)
      val newOffset = Some(tx.getOffset)
      (
        State(
          offset = newOffset,
          nextEventNumber = stNew.nextEventNumber,
          createEvents = stNew.createEvents,
          createEventsById = stNew.createEventsById,
          offsetChanged = Promise(),
          offsetIngestionsToSignal = stNew.offsetIngestionsToSignal.removedAll(offsetsToRemove.keys),
        ),
        (
          IngestionSummary(
            Some(tx.getTransactionId),
            ingestedCreatedEvents,
            numFilteredCreatedEvents,
            ingestedArchivedEvents,
            numFilteredArchivedEvents,
            newOffset,
            stNew.createEvents.size,
          ),
          offsetChanged,
          offsetsToRemove.values,
        ),
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

  private case class IngestionSummary(
      txId: Option[String],
      ingestedCreatedEvents: mutable.ListBuffer[CreatedEvent],
      numFilteredCreatedEvents: Int,
      ingestedArchivedEvents: mutable.ListBuffer[ExercisedEvent],
      numFilteredArchivedEvents: Int,
      offset: Option[String],
      newAcsSize: Int,
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = {
      import com.daml.network.util.PrettyInstances.*

      prettyNode(
        "", // intentionally left empty, as that worked better in the log messages above
        paramIfDefined("offset", _.offset.map(_.unquoted)),
        paramIfDefined("txId", _.txId.map(_.readableHash)),
        param("ingestedCreates", _.ingestedCreatedEvents.toSeq),
        param(
          "numFilteredCreates",
          _.numFilteredCreatedEvents,
          _.numFilteredCreatedEvents != 0,
        ),
        param(
          "ingestedArchivals",
          _.ingestedArchivedEvents.toSeq,
          _.ingestedArchivedEvents.nonEmpty,
        ),
        param(
          "numFilteredArchivals",
          _.numFilteredArchivedEvents,
          _.numFilteredArchivedEvents != 0,
        ),
        param("newAcsSize", _.newAcsSize),
      )
    }
  }
}
