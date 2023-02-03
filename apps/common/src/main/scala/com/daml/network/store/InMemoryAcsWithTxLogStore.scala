package com.daml.network.store

import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{
  TransactionTreeUpdate,
  TransferUpdate,
  Transfer,
  TransferEvent,
  TreeUpdate,
}
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.{
  Contract as CodegenContract,
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
}
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Template, TransactionTree}
import com.daml.network.util.{Contract, Trees}
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
class InMemoryAcsWithTxLogStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: AcsStore.ContractFilter,
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    futureSupervisor: FutureSupervisor,

    // Boolean flag to enable very verbose state update logging
    logAllStateUpdates: Boolean = false,
)(implicit
    ec: ExecutionContext
) extends AcsWithTxLogStore[TXI, TXE]
    with NamedLogging {

  import AcsStore.QueryResult

  private val finishedAcsIngestion: Promise[Unit] = Promise()

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryAcsWithTxLogStore.State[TXI, TXE] =
    InMemoryAcsWithTxLogStore.State(
      None,
      0,
      immutable.SortedMap.empty,
      immutable.Map.empty,
      Promise(),
      SortedMap.empty,
      immutable.Queue.empty,
    )

  private def updateState[T](
      f: InMemoryAcsWithTxLogStore.State[TXI, TXE] => (InMemoryAcsWithTxLogStore.State[TXI, TXE], T)
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
        _.ingestTransaction(tx, contractFilter.contains, txLogParser)
      ).map {
        case (summary, offsetChanged, offsetIngestionsToSignal) => {
          logger.debug(show"Ingested transaction $summary")
          offsetIngestionsToSignal.foreach(_.success(()))
          offsetChanged.success(())
        }
      }

    override def ingestTransfer(
        event: Transfer[TransferEvent]
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestTransfer(event, contractFilter.contains)
      ).map {
        case (summary, offsetChanged, offsetIngestionsToSignal) => {
          logger.debug(show"Ingested transaction $summary")
          offsetIngestionsToSignal.foreach(_.success(()))
          offsetChanged.success(())
        }
      }

    override def ingestUpdate(
        update: TreeUpdate
    )(implicit traceContext: TraceContext): Future[Unit] =
      update match {
        case TransactionTreeUpdate(tree) => ingestTransaction(tree)
        case TransferUpdate(transfer) => ingestTransfer(transfer)
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

  private def offsetAndStateAfterIngestingAcs()
      : Future[(String, InMemoryAcsWithTxLogStore.State[TXI, TXE])] =
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

  def findContractWithOffset[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(p: Contract[TCid, T] => Boolean): Future[QueryResult[Option[Contract[TCid, T]]]] = {
    requireInScope(templateCompanion)
    findContractWithOffset(Contract.fromCreatedEvent(templateCompanion))(p)
  }

  def findContractWithOffset[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(p: Contract[Id, View] => Boolean): Future[QueryResult[Option[Contract[Id, View]]]] = {
    requireInScope(interfaceCompanion)
    findContractWithOffset(Contract.fromCreatedEvent(interfaceCompanion))(p)
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

  def listContracts[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  ): Future[Seq[Contract[TCid, T]]] = {
    requireInScope(templateCompanion)
    listContracts(Contract.fromCreatedEvent(templateCompanion), filter, limit)
  }

  def listContractsI[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View],
      filter: Contract[Id, View] => Boolean,
      limit: Option[Long],
  ): Future[Seq[Contract[Id, View]]] = {
    requireInScope(interfaceCompanion)
    listContracts(Contract.fromCreatedEvent(interfaceCompanion), filter, limit)
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

  def lookupContractById[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T]): Future[Option[Contract[TCid, T]]] = {
    requireInScope(templateCompanion)
    lookupContractById(Contract.fromCreatedEvent(templateCompanion))(id)
  }

  def lookupContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id): Future[Option[Contract[Id, View]]] = {
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

  def streamContracts[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Source[Contract[TCid, T], NotUsed] = {
    requireInScope(templateCompanion)
    streamContracts(Contract.fromCreatedEvent(templateCompanion))
  }

  def streamContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  ): Source[Contract[Id, View], NotUsed] = {
    requireInScope(interfaceCompanion)
    streamContracts(Contract.fromCreatedEvent(interfaceCompanion))
  }

  def signalWhenIngested[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Future[Unit] = {
    requireInScope(templateCompanion)
    nextActiveContract(Contract.fromCreatedEvent(templateCompanion), 0).map(ssss => ())
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

  override def getTxLogIndicesByOffset(offset: Int, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] =
    Future.successful(stateVar.txLog.slice(offset, limit))

  override def close(): Unit = ()
}

object InMemoryAcsWithTxLogStore {

  private case class State[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      offset: Option[String],
      nextEventNumber: Long,
      createEvents: immutable.SortedMap[Long, CreatedEvent],
      createEventsById: immutable.Map[String, Long],
      offsetChanged: Promise[Unit],
      offsetIngestionsToSignal: SortedMap[String, Promise[Unit]],
      txLog: immutable.Queue[TXI],
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

    def ingestCreatedEvent(ev: CreatedEvent): State[TXI, TXE] = {
      State(
        offset = offset,
        nextEventNumber = nextEventNumber + 1,
        createEvents = createEvents + (nextEventNumber -> ev),
        createEventsById = createEventsById + (ev.getContractId -> nextEventNumber),
        offsetChanged = offsetChanged,
        offsetIngestionsToSignal = offsetIngestionsToSignal,
        txLog = txLog,
      )
    }

    def ingestArchivedEvent(contractId: String): (State[TXI, TXE], Boolean) =
      createEventsById.get(contractId) match {
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
              createEventsById = createEventsById - contractId,
              offsetChanged = offsetChanged,
              offsetIngestionsToSignal = offsetIngestionsToSignal,
              txLog = txLog,
            ),
            true,
          )
        }
      }

    def ingestCreatedEvents(
        evs: Seq[CreatedEvent],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
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
          txId = None,
          ingestedCreatedEvents = ingestedCreatedEvents,
          numFilteredCreatedEvents = numFilteredCreatedEvents,
          ingestedArchivedEvents = mutable.ListBuffer.empty,
          numFilteredArchivedEvents = 0,
          ingestedTransferInEvents = mutable.ListBuffer.empty,
          numFilteredTransferInEvents = 0,
          ingestedTransferOutEvents = mutable.ListBuffer.empty,
          numFilteredTransferOutEvents = 0,
          None,
          stNew.createEvents.size,
          mutable.ListBuffer.empty,
        ),
      )
    }

    def ingestTxLogEntry(entry: Option[TXE]): State[TXI, TXE] = entry match {
      case Some(e) =>
        State(
          offset = offset,
          nextEventNumber = nextEventNumber,
          createEvents = createEvents,
          createEventsById = createEventsById,
          offsetChanged = offsetChanged,
          offsetIngestionsToSignal = offsetIngestionsToSignal,
          txLog = txLog.appended(e.indexRecord),
        )
      case None =>
        this
    }

    def ingestTransferInEvent(
        in: TransferEvent.In,
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], Boolean) =
      // TODO (M3-83) Consider if we want to treat those differently
      // than plain creates.
      if (p(in.createdEvent)) {
        (ingestCreatedEvent(in.createdEvent), true)
      } else {
        (this, false)
      }

    def ingestTransferOutEvent(out: TransferEvent.Out): (State[TXI, TXE], Boolean) =
      // TODO (M3-83) Consider if we want to treat those differently
      // than plain archives. In particular, we may want to
      // mark a contract as pending when we submit the transfer out until we get the completion.
      ingestArchivedEvent(out.contractId.contractId)

    def switchToIngestingTransactions(
        acsOffset: String
    ): (State[TXI, TXE], (Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isEmpty, "state was not switched to tx ingestion yet")
      // Note: tx log entries are only generated from ingested transactions.
      // History from before the initial ACS snapshot is currently not available.
      assert(txLog.isEmpty, "TX log contains items before switching to tx ingestion")
      val offsetsToRemove = computeOffsetsToRemove(acsOffset)
      (
        State(
          offset = Some(acsOffset),
          nextEventNumber = nextEventNumber,
          createEvents = createEvents,
          createEventsById = createEventsById,
          offsetChanged = Promise(),
          offsetIngestionsToSignal = offsetIngestionsToSignal.removedAll(offsetsToRemove.keys),
          txLog = txLog,
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
        txLogParser: TxLogStore.Parser[TXI, TXE],
    )(implicit
        traceContext: TraceContext
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredCreatedEvents = 0
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredArchivedEvents = 0
      val ingestedCreatedEvents = mutable.ListBuffer[CreatedEvent]()
      val ingestedArchivedEvents = mutable.ListBuffer[ExercisedEvent]()
      val ingestedTxLogEntries = mutable.ListBuffer[TXE]()

      val stNew = Trees.foldTree(tx, this)(
        (st, ev, _) => {
          val txLogEntry = txLogParser.parseCreate(tx, ev)
          txLogEntry.foreach(ale => ingestedTxLogEntries.append(ale))
          if (p(ev)) {
            ingestedCreatedEvents.append(ev)
            st.ingestCreatedEvent(ev).ingestTxLogEntry(txLogEntry)
          } else {
            numFilteredCreatedEvents += 1
            st.ingestTxLogEntry(txLogEntry)
          }
        },
        (st, ev, _) => {
          val txLogEntry = txLogParser.parseExercise(tx, ev)
          txLogEntry.foreach(ale => ingestedTxLogEntries.append(ale))
          if (ev.isConsuming) {
            val (newSt, ingested) = st.ingestArchivedEvent(ev.getContractId)
            if (ingested)
              ingestedArchivedEvents.append(ev)
            else
              numFilteredArchivedEvents += 1
            newSt.ingestTxLogEntry(txLogEntry)
          } else {
            st.ingestTxLogEntry(txLogEntry)
          }
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
          offsetIngestionsToSignal =
            stNew.offsetIngestionsToSignal.removedAll(offsetsToRemove.keys),
          txLog = stNew.txLog,
        ),
        (
          IngestionSummary(
            txId = Some(tx.getTransactionId),
            ingestedCreatedEvents = ingestedCreatedEvents,
            numFilteredCreatedEvents = numFilteredCreatedEvents,
            ingestedArchivedEvents = ingestedArchivedEvents,
            numFilteredArchivedEvents = numFilteredArchivedEvents,
            ingestedTransferInEvents = mutable.ListBuffer.empty,
            numFilteredTransferInEvents = 0,
            ingestedTransferOutEvents = mutable.ListBuffer.empty,
            numFilteredTransferOutEvents = 0,
            offset = newOffset,
            newAcsSize = stNew.createEvents.size,
            ingestedTxLogEntries = ingestedTxLogEntries,
          ),
          offsetChanged,
          offsetsToRemove.values,
        ),
      )
    }

    def ingestTransfer(
        transfer: Transfer[TransferEvent],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      val newOffset = Some(transfer.offset.getOffset)
      val baseSummary = IngestionSummary[TXE](
        txId = Some(transfer.updateId),
        ingestedCreatedEvents = mutable.ListBuffer.empty,
        numFilteredCreatedEvents = 0,
        ingestedArchivedEvents = mutable.ListBuffer.empty,
        numFilteredArchivedEvents = 0,
        ingestedTransferInEvents = mutable.ListBuffer.empty,
        numFilteredTransferInEvents = 0,
        ingestedTransferOutEvents = mutable.ListBuffer.empty,
        numFilteredTransferOutEvents = 0,
        offset = newOffset,
        newAcsSize = 0,
        ingestedTxLogEntries = mutable.ListBuffer.empty,
      )
      val (stNew, summary) = transfer.event match {
        case out: TransferEvent.Out =>
          val (stNew, ingested) = this.ingestTransferOutEvent(out)
          (
            stNew,
            baseSummary.copy(
              ingestedTransferOutEvents =
                if (ingested) mutable.ListBuffer(out) else mutable.ListBuffer.empty,
              numFilteredTransferOutEvents = if (ingested) 0 else 1,
            ),
          )
        case in: TransferEvent.In =>
          val (stNew, ingested) = this.ingestTransferInEvent(in, p)
          (
            stNew,
            baseSummary.copy(
              ingestedTransferInEvents =
                if (ingested) mutable.ListBuffer(in) else mutable.ListBuffer.empty,
              numFilteredTransferInEvents = if (ingested) 0 else 1,
            ),
          )
      }
      val offsetsToRemove = stNew.computeOffsetsToRemove(transfer.offset.getOffset)
      (
        State(
          offset = newOffset,
          nextEventNumber = stNew.nextEventNumber,
          createEvents = stNew.createEvents,
          createEventsById = stNew.createEventsById,
          offsetChanged = Promise(),
          offsetIngestionsToSignal =
            stNew.offsetIngestionsToSignal.removedAll(offsetsToRemove.keys),
          txLog = stNew.txLog,
        ),
        (
          summary.copy(newAcsSize = stNew.createEvents.size),
          offsetChanged,
          offsetsToRemove.values,
        ),
      )
    }

    /** Update the state by adding another offset whose ingestion should be signalled. If the signalling of that
      * offset has already been requested, don't change the state.
      */
    def addOffsetToSignal(offset: String): (State[TXI, TXE], Promise[Unit]) = {
      offsetIngestionsToSignal.get(offset) match {
        case None =>
          val p = Promise[Unit]()
          (this.focus(_.offsetIngestionsToSignal).modify(_ + (offset -> p)), p)
        case Some(existingP) => (this, existingP)
      }
    }
  }

  private case class IngestionSummary[TXE <: TxLogStore.Entry[?]](
      txId: Option[String],
      ingestedCreatedEvents: mutable.ListBuffer[CreatedEvent],
      numFilteredCreatedEvents: Int,
      ingestedArchivedEvents: mutable.ListBuffer[ExercisedEvent],
      numFilteredArchivedEvents: Int,
      // For now transfers are either 0 or 1 per ingestion, we use a list for consistency with creates/archives
      // and to support batching later.
      ingestedTransferInEvents: mutable.ListBuffer[TransferEvent.In],
      numFilteredTransferInEvents: Int,
      ingestedTransferOutEvents: mutable.ListBuffer[TransferEvent.Out],
      numFilteredTransferOutEvents: Int,
      offset: Option[String],
      newAcsSize: Int,
      ingestedTxLogEntries: mutable.ListBuffer[TXE],
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = {
      import com.daml.network.util.PrettyInstances.*

      @SuppressWarnings(Array("org.wartremover.warts.Product"))
      implicit val txLogPretty: Pretty[TXE] = adHocPrettyInstance

      prettyNode(
        "", // intentionally left empty, as that worked better in the log messages above
        paramIfDefined("offset", _.offset.map(_.unquoted)),
        paramIfDefined("txId", _.txId.map(_.readableHash)),
        param("ingestedCreates", _.ingestedCreatedEvents.toSeq, _.ingestedCreatedEvents.nonEmpty),
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
        param(
          "ingestedTransferIns",
          _.ingestedTransferInEvents.toSeq,
          _.ingestedTransferInEvents.nonEmpty,
        ),
        param(
          "numFilteredTransferIns",
          _.numFilteredTransferInEvents,
          _.numFilteredTransferInEvents != 0,
        ),
        param(
          "ingestedTransferOuts",
          _.ingestedTransferOutEvents.toSeq,
          _.ingestedTransferOutEvents.nonEmpty,
        ),
        param(
          "numFilteredTransferOuts",
          _.numFilteredTransferOutEvents,
          _.numFilteredTransferOutEvents != 0,
        ),
        param("newAcsSize", _.newAcsSize),
        param(
          "ingestedTxLogEntries",
          _.ingestedTxLogEntries.toSeq,
          _.ingestedTxLogEntries.nonEmpty,
        ),
      )
    }
  }
}
