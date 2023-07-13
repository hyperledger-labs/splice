package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  ExercisedEvent,
  Template,
  TransactionTree,
  Identifier,
}
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.*
import com.daml.network.util.{Contract, Trees}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.collection.immutable.{Queue, SortedMap}
import scala.concurrent.*

class InMemoryMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: MultiDomainAcsStore.ContractFilter,
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends MultiDomainAcsStore
    with TxLogStore[TXI, TXE]
    with NamedLogging
    with LimitHelpers
    with TxLogStoreErrors {

  import MultiDomainAcsStore.*

  private val finishedAcsIngestion: Promise[Unit] = Promise()

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryMultiDomainAcsStore.State[TXI, TXE] =
    InMemoryMultiDomainAcsStore.State(
      offset = None,
      nextCreateEventNumber = 0,
      createEvents = SortedMap.empty,
      createEventsById = Map.empty,
      nextContractStateEventNumber = 0,
      contractStateEvents = SortedMap.empty,
      contractStateEventsById = Map.empty,
      incompleteTransferOut = Set.empty,
      incompleteTransferIn = Set.empty,
      incompleteTransfersById = Map.empty,
      offsetChanged = Promise(),
      offsetIngestionsToSignal = SortedMap.empty,
      txLog = Queue.empty,
    )

  private def updateState[T](
      f: InMemoryMultiDomainAcsStore.State[TXI, TXE] => (
          InMemoryMultiDomainAcsStore.State[TXI, TXE],
          T,
      )
  ): Future[T] = {
    Future {
      blocking {
        synchronized {
          val (stNew, result) = f(stateVar)
          stateVar = stNew
          result
        }
      }
    }
  }

  val ingestionSink: MultiDomainAcsStore.IngestionSink = new MultiDomainAcsStore.IngestionSink {

    override def ingestionFilter = contractFilter.ingestionFilter

    override def initialize()(implicit traceContext: TraceContext): Future[Option[String]] =
      Future {
        stateVar.offset
      }

    override def ingestAcs(
        offset: String,
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteTransferEvent.Out],
        incompleteIn: Seq[IncompleteTransferEvent.In],
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestAcs(
          acs,
          incompleteOut,
          incompleteIn,
          offset,
          txLogParser,
          contractFilter.contains,
        )
      ).map { case (summary, offsetChanged, offsetIngestionsToSignal) =>
        logger.debug(
          show"Ingested complete ACS and in-flight transfers at offset $offset: $summary"
        )
        offsetIngestionsToSignal.foreach(_.success(()))
        offsetChanged.success(())
        finishedAcsIngestion.success(())
      }

    override def ingestUpdate(
        domain: DomainId,
        update: TreeUpdate,
    )(implicit traceContext: TraceContext): Future[Unit] =
      update match {
        case TransactionTreeUpdate(tree) =>
          updateState(
            _.ingestTransaction(domain, tree, contractFilter.contains, txLogParser, logger)
          ).map { case (summary, offsetChanged, offsetIngestionsToSignal) =>
            logger.debug(show"Ingested transaction $summary")
            offsetIngestionsToSignal.foreach(_.success(()))
            offsetChanged.success(())
          }
        case TransferUpdate(transfer) =>
          updateState(
            _.ingestTransfer(transfer, contractFilter.contains)
          ).map { case (summary, offsetChanged, offsetIngestionsToSignal) =>
            logger.debug(show"Ingested transfer $summary")
            offsetIngestionsToSignal.foreach(_.success(()))
            offsetChanged.success(())
          }
      }
  }

  private def requireInScope[C, TCid <: ContractId[_], T](
      companion: C
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Unit =
    require(
      companionClass.mightContain(contractFilter)(companion),
      s"template ${companionClass.typeId(companion)} is part of the contract filter",
    )

  override def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ) = {
    filterContracts(companion, _ => true, limit)
  }

  /** Find all contract that satisfy a predicate up to `limit`.
    */
  def filterContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]] = {
    requireInScope(companion)
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      applyLimit(
        limit,
        st.createEvents.values
          .collect(Function.unlift { ev =>
            for {
              parsedEv <- companionClass.fromCreatedEvent(companion)(contractFilter, ev)
              state <- st.getContractState(new ContractId(ev.getContractId)).toActiveState
            } yield (parsedEv, state)
          })
          .filter { case (ev, _) => filter(ev) },
      ).toSeq
        .map { case (contract, state) =>
          ContractWithState(contract, state)
        }
    }
  }

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domainId: DomainId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]] =
    filterContractsOnDomain(companion, domainId, _ => true, limit)

  def filterContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domainId: DomainId,
      filter: Contract[TCid, T] => Boolean,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]] =
    filterContracts(companion, filter, limit).map { contracts =>
      contracts.collect {
        case ContractWithState(contract, ContractState.Assigned(domain)) if domain == domainId =>
          contract
      }
    }

  override def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ReadyContract[TCid, T]]] = {
    filterReadyContracts(companion, _ => true, limit)
  }

  def filterReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ReadyContract[TCid, T]]] =
    for {
      contracts <- filterContracts(companion, filter, limit)
    } yield contracts.view.collect(Function.unlift(_.toReadyContract)).toSeq

  override private[network] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[
    T
  ], T <: Template](companion: C)(
      expiresAt: T => Instant
  )(implicit companionClass: ContractCompanion[C, TCid, T]): ListExpiredContracts[TCid, T] =
    (now, limit) =>
      implicit traceContext =>
        filterReadyContracts(
          companion = companion,
          filter = co => now.toInstant isAfter expiresAt(co.payload),
          limit = PageLimit( // this is called until the result size is 0, effectively paginating
            limit.toLong
          ),
        )

  private def lookupContractById[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  )(id: ContractId[_]): Future[Option[(T, ContractState)]] = {
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      st.createEventsById.get(id).flatMap { evRev =>
        for {
          ev <- st.createEvents.get(evRev)
          parsedEv <- fromCreatedEvent(ev)
          state <- st.getContractState(new ContractId(ev.getContractId)).toActiveState
        } yield (parsedEv, state)
      }
    }
  }

  override def lookupContractById[C, TCid <: ContractId[_], T](
      companion: C
  )(id: ContractId[_])(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] = {
    requireInScope(companion)
    lookupContractById(companionClass.fromCreatedEvent(companion)(contractFilter, _))(id)
      .map(_.map { case (contract, state) =>
        ContractWithState(contract, state)
      })
  }

  override def lookupContractStateById(id: ContractId[?])(implicit
      traceContext: TraceContext
  ): Future[Option[ContractState]] =
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      st.lookupContractState(id).flatMap(_.toActiveState)
    }

  def allKnownAndNotArchived(ids: Seq[ContractId[?]])(implicit
      traceContext: TraceContext
  ): Future[Boolean] =
    Future.sequence(ids.map(lookupContractStateById)).map(_.forall(_.isDefined))

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContractWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(
      p: Contract[TCid, T] => Boolean = (_: Any) => true
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]] = {
    requireInScope(companion)
    offsetAndStateAfterIngestingAcs().map { case (offset, st) =>
      val optEntry = st.createEvents.values.collectFirst(
        Function.unlift(ev =>
          for {
            contract <- companionClass.fromCreatedEvent(companion)(contractFilter, ev)
            if p(contract)
            state <- st.getContractState(contract.contractId).toActiveState
          } yield ContractWithState(contract, state)
        )
      )
      QueryResult(
        offset,
        optEntry,
      )
    }
  }

  /** Find a contract that satisfies a predicate on the given domain.
    * Only contracts with state ContractState.Assigned(domain) are considered
    * so contracts are omitted if they have been transferred or are in-flight.
    * This should generally only be used
    * for contracts that exist in per-domain variations and are never transferred, e.g.,
    * install contracts.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContractOnDomainWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, p: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[Contract[TCid, T]]]] = {
    requireInScope(companion)
    offsetAndStateAfterIngestingAcs().map { case (offset, st) =>
      val optEntry = st.createEvents.values.collectFirst(
        Function.unlift(ev =>
          for {
            contract <- companionClass.fromCreatedEvent(companion)(contractFilter, ev)
            if p(contract)
            state = st.getContractState(contract.contractId)
            if state == StoreContractState.Assigned(domain)
          } yield contract
        )
      )
      QueryResult(
        offset,
        optEntry,
      )
    }
  }

  def findContractOnDomain[C, TCid <: ContractId[_], T](
      companion: C
  )(
      domain: DomainId,
      p: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Option[Contract[TCid, T]]] =
    findContractOnDomainWithOffset(companion)(domain, p).map(_.value)

  override def streamReadyForTransferIn(
  ): Source[TransferEvent.Out, NotUsed] =
    Source
      .unfoldAsync(0: Long)(eventNumber => nextReadyForTransferIn(eventNumber).map(Some(_)))

  private def nextReadyForTransferIn(
      startingFromIncl: Long
  ): Future[(Long, TransferEvent.Out)] = {
    val st = stateVar
    val optEntry = st.contractStateEvents
      .iteratorFrom(startingFromIncl)
      .collectFirst(Function.unlift { case (num, state) =>
        for {
          inFlight <- state.state match {
            case StoreContractState.InFlight(out) => Some(out)
            case _ => None
          }
        } yield (num, inFlight)
      })
    optEntry match {
      case None =>
        st.offsetChanged.future.flatMap(_ =>
          nextReadyForTransferIn(st.nextContractStateEventNumber)
        )
      case Some((eventNumber, co)) => Future((eventNumber + 1, co))
    }
  }

  override def isReadyForTransferIn(
      contractId: ContractId[_],
      transfer: TransferId,
  ): Future[Boolean] =
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      st.contractStateEventsById.get(contractId).fold(false) { num =>
        val contractState = st.contractStateEvents(num).state
        contractState match {
          case StoreContractState.InFlight(out) =>
            TransferId.fromTransferOut(out) == transfer
          case _ => false
        }
      }
    }

  private def nextReadyContract[T](
      fromCreated: CreatedEvent => Option[T],
      startingFromIncl: Long,
  ): Future[(Long, (T, DomainId))] = {
    val st = stateVar
    val optEntry = st.contractStateEvents
      .iteratorFrom(startingFromIncl)
      .collectFirst(Function.unlift { case (num, state) =>
        for {
          domain <- state.toAssigned
          evNum <- st.createEventsById.get(state.contractId)
          ev <- st.createEvents.get(evNum)
          t <- fromCreated(ev)
        } yield (num, (t, domain))
      })
    optEntry match {
      case None =>
        st.offsetChanged.future.flatMap(_ =>
          nextReadyContract(fromCreated, st.nextContractStateEventNumber)
        )
      case Some((eventNumber, co)) => Future((eventNumber + 1, co))
    }
  }

  private def streamReadyContracts[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  ): Source[(T, DomainId), NotUsed] = {
    Source.unfoldAsync(0: Long)(eventNumber =>
      nextReadyContract(fromCreatedEvent, eventNumber).map(Some(_))
    )
  }

  override def streamReadyContracts[C, TCid <: ContractId[_], T](
      companion: C
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    requireInScope(companion)
    streamReadyContracts(companionClass.fromCreatedEvent(companion)(contractFilter, _)).map {
      case (contract, domain) => ReadyContract(contract, domain)
    }
  }

  /** TX log APIs
    */

  def getTxLogIndicesByOffset(offset: Int, limit: Int): Seq[TXI] =
    stateVar.txLog.slice(offset, offset + limit).map(_.payload)

  def getTxLogIndicesAfterEventId(
      domainId: DomainId,
      beginAfterEventId: String,
      limit: Int,
  ): Seq[TXI] =
    stateVar.txLog.view
      .filter(_.domain == domainId)
      .map(_.payload)
      .dropWhile(_.eventId != beginAfterEventId)
      .slice(1, 1 + limit)
      .toSeq

  def findLatestTxLogIndex[A, Z](init: Z)(p: (Z, TXI) => Either[A, Z])(implicit
      ec: ExecutionContext
  ): Future[A] = {
    import cats.implicits.*

    Future {
      stateVar.txLog
        .foldM[Either[A, *], Z](init) { case (z, t) => p(z, t.payload) }
        .fold(
          identity,
          _ => throw txLogNotFound(),
        )
    }
  }

  def getLatestTxLogIndex(query: (TXI) => Boolean = (_: TXI) => true)(implicit
      ec: ExecutionContext
  ): Future[TXI] =
    Future {
      stateVar.txLog.view
        .map(_.payload)
        .find(query)
        .getOrElse(throw txLogNotFound())
    }

  def getTxLogIndicesByFilter(filter: TXI => Boolean): Future[Seq[TXI]] =
    Future.successful(stateVar.txLog.view.map(_.payload).filter(filter).toSeq)

  private def offsetAndStateAfterIngestingAcs()
      : Future[(String, InMemoryMultiDomainAcsStore.State[TXI, TXE])] =
    finishedAcsIngestion.future
      .map(_ => {
        val st = stateVar
        (
          st.offset.getOrElse(sys.error("Offset must be defined, as the ACS was ingested")),
          st,
        )
      })

  override def signalWhenIngestedOrShutdown(offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] =
    updateState[Future[Unit]](state =>
      if (
        state.offset
          .exists(actualOffset => actualOffset >= offset)
      ) {
        (state, Future.unit)
      } else {
        val (newState, offsetIngested) = state.addOffsetToSignal(offset)
        val name = s"signalWhenIngested($offset)"
        val ingestedOrShutdown = retryProvider
          .waitUnlessShutdown(offsetIngested)
          .onShutdown(
            logger.debug(s"Aborted $name, as we are shutting down")
          )
        val supervisedFuture = retryProvider.futureSupervisor.supervised(name)(ingestedOrShutdown)
        (newState, supervisedFuture)
      }
    ).flatten

  /** Testing APIs
    */

  private[store] def contractStateEventsById: Future[Map[ContractId[_], ContractStateEvent]] =
    Future.successful(
      stateVar.contractStateEventsById.view
        .mapValues(num => stateVar.contractStateEvents(num))
        .toMap
    )
  private[store] def incompleteTransfersById
      : Future[Map[ContractId[_], NonEmpty[Set[TransferId]]]] =
    Future.successful(stateVar.incompleteTransfersById)

  override def close(): Unit = ()

  override def getJsonAcsSnapshot(ignoredContracts: Set[Identifier]): Future[JsonAcsSnapshot] =
    Future {
      val state = stateVar
      state.offset match {
        case None =>
          throw Status.NOT_FOUND
            .withDescription("ACS has not yet been fully ingested.")
            .asRuntimeException()
        case Some(offset) =>
          JsonAcsSnapshot(
            offset,
            state.createEvents.values
              .collect(
                Function.unlift(ev => {
                  if (ignoredContracts.contains(ev.getTemplateId))
                    None
                  else
                    contractFilter.decodeMatchingContract(ev)
                })
              )
              .toSeq,
          )
      }
    }
}

object InMemoryMultiDomainAcsStore {

  import MultiDomainAcsStore.{ContractStateEvent, StoreContractState, TransferId}

  private case class IndexRecordWithDomain[+TXI](
      domain: DomainId,
      payload: TXI,
  )

  /** General assumption: We're connected to both source and target domain.
    * Trivially given for non-multi hosted parties. For multi-hosted parties
    * still a given provided all participants hosting a party are connected
    * to the same domains.
    *
    * State machine:
    * - createdEvents
    *   - added on the first create/tranfer that matches the filter.
    *   - removed once the contract is archived and there are no incomplete transfers for the contract.
    * - contractStateEvents
    *   - added added on first transfer/create
    *   - updated to the state with the highest transfer counter
    *   - removed together with the entry in createdEvents
    * - incompleteTransferOut/In
    *   - added on transfer out/in if contract matches filter and matching transfer in/out has not been observed
    *   - removed on matching transfer in/out
    * - incompleteTransfersById: index on top of incompleteTransferIn/incompleteTransferOut, always updated together
    */
  private case class State[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      offset: Option[String],
      nextCreateEventNumber: Long,
      // All active contracts across all domains that match our contract filter. Contracts are inserted
      // when we see the first create or transfer in and removed on archive.
      // Event number stays stable across transfers.
      createEvents: SortedMap[Long, CreatedEvent],
      createEventsById: Map[ContractId[_], Long],
      nextContractStateEventNumber: Long,
      // States of all active contracts.
      // Each contract will be in there only once
      // but contracts get readded with a new
      // event number as their state changes.
      contractStateEvents: SortedMap[Long, ContractStateEvent],
      contractStateEventsById: Map[ContractId[_], Long],

      // incomplete transfers
      incompleteTransferOut: Set[TransferId],
      incompleteTransferIn: Set[TransferId],
      incompleteTransfersById: Map[ContractId[_], NonEmpty[Set[TransferId]]],
      offsetChanged: Promise[Unit],
      offsetIngestionsToSignal: SortedMap[String, Promise[Unit]],
      txLog: Queue[IndexRecordWithDomain[TXI]],
  ) {

    private def removeOffsetSignalsBefore(
        offset: String
    ): (State[TXI, TXE], Map[String, Promise[Unit]]) = {
      // because we use a SortedMap, we can use takeWhile.
      val offsetsToRemove = offsetIngestionsToSignal
        .takeWhile(_._1 <= offset)
      (
        copy(
          offsetIngestionsToSignal = offsetIngestionsToSignal.removedAll(offsetsToRemove.keys)
        ),
        offsetsToRemove,
      )
    }

    def lookupContractState(cid: ContractId[_]): Option[StoreContractState] =
      contractStateEventsById.get(cid).map { eventNum =>
        contractStateEvents(eventNum).state
      }

    def getContractState(cid: ContractId[_]): StoreContractState =
      lookupContractState(cid).getOrElse(
        throw new IllegalStateException(s"Failed to find contract state for $cid")
      )

    def ingestAcs(
        evs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteTransferEvent.Out],
        incompleteIn: Seq[IncompleteTransferEvent.In],
        acsOffset: String,
        txLogParser: TxLogStore.Parser[TXI, TXE],
        p: CreatedEvent => Boolean,
    )(implicit
        tc: TraceContext
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isEmpty, "state was not switched to update ingestion yet")
      val (stAcs, summaryAcs) = ingestActiveContracts(evs, p)
      val (stInFlightOut, summaryTransferOut) =
        stAcs.ingestIncompleteTransferOuts(incompleteOut, summaryAcs, p)
      val (stInFlightIn, summaryTransferIn) =
        stInFlightOut.ingestIncompleteTransferIns(incompleteIn, summaryTransferOut, p)
      val (stFinal, offsetsToRemove) = stInFlightIn.removeOffsetSignalsBefore(acsOffset)
      val parsedTxLogEntries = txLogParser.parseAcs(evs, incompleteOut, incompleteIn)
      (
        stFinal.copy(
          offset = Some(acsOffset),
          offsetChanged = Promise(),
          txLog = parsedTxLogEntries.reverse
            .map({ case (domain, entry) =>
              IndexRecordWithDomain(domain, entry.indexRecord)
            })
            .to(collection.immutable.Queue),
        ),
        (
          summaryTransferIn.copy(
            newAcsSize = stFinal.createEvents.size
          ),
          offsetChanged,
          offsetsToRemove.values,
        ),
      )
    }

    /** Ingest a transaction while filtering out create events that do not satisfy the given predicate. */
    def ingestTransaction(
        domain: DomainId,
        tx: TransactionTree,
        p: CreatedEvent => Boolean,
        txLogParser: TxLogStore.Parser[TXI, TXE],
        logger: TracedLogger,
    )(implicit
        traceContext: TraceContext
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isDefined, "state was switched to update ingestion")

      val (stNew, summary) = Trees.foldTree(tx, (this, IngestionSummary.empty[TXE]))(
        onCreate = {
          case ((st, summary), ev, _) => {
            if (p(ev)) {
              st.ingestCreatedEvent(
                ev,
                domain,
                0,
                summary,
              )
            } else {
              (
                st,
                summary.copy(
                  numFilteredCreatedEvents = summary.numFilteredCreatedEvents + 1
                ),
              )
            }
          }
        },
        onExercise = {
          case ((st, summary), ev, _) => {
            if (ev.isConsuming) {
              st.ingestArchivedEvent(ev, summary)
            } else {
              (st, summary)
            }
          }
        },
      )

      val ingestedTxLogEntries = txLogParser.parse(tx, logger)

      val newOffset = tx.getOffset

      val newSummary = summary.copy(
        txId = Some(tx.getTransactionId),
        offset = Some(newOffset),
        newAcsSize = stNew.createEvents.size,
        ingestedTxLogEntries = ingestedTxLogEntries,
      )

      val (stNewWithoutOffsets, offsetsToRemove) =
        stNew.removeOffsetSignalsBefore(newOffset)

      (
        stNewWithoutOffsets.copy(
          offset = Some(newOffset),
          offsetChanged = Promise(),
          txLog = stNew.txLog.prependedAll(
            ingestedTxLogEntries.reverse.map(entry =>
              IndexRecordWithDomain(domain, entry.indexRecord)
            )
          ),
        ),
        (newSummary, offsetChanged, offsetsToRemove.values),
      )
    }

    def ingestTransfer(
        transfer: Transfer[TransferEvent],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isDefined, "state was switched to update ingestion")
      val (newSt, summary) = ingestTransferEvent(transfer.event, p)
      val newOffset = transfer.offset.getOffset
      val (newStWithoutOffsets, offsetsToRemove) =
        newSt.removeOffsetSignalsBefore(newOffset)
      (
        newStWithoutOffsets.copy(
          offset = Some(newOffset),
          offsetChanged = Promise(),
        ),
        (
          summary.copy(
            offset = Some(newOffset),
            newAcsSize = newSt.createEvents.size,
          ),
          newSt.offsetChanged,
          offsetsToRemove.values,
        ),
      )
    }

    private def updateContractStateEvent(
        cid: ContractId[_],
        transferCounter: Long,
        state: StoreContractState,
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val evNum = contractStateEventsById.get(cid)
      val stateEvent = ContractStateEvent(
        cid,
        transferCounter,
        state,
      )
      lazy val updatedState: (State[TXI, TXE], IngestionSummary[TXE]) =
        (
          copy(
            contractStateEvents = contractStateEvents -- evNum + (
              nextContractStateEventNumber -> stateEvent
            ),
            contractStateEventsById =
              contractStateEventsById + (cid -> nextContractStateEventNumber),
            nextContractStateEventNumber = nextContractStateEventNumber + 1,
          ),
          summary.copy(
            updatedContractStates = summary.updatedContractStates :+ stateEvent
          ),
        )
      evNum match {
        case None => updatedState
        case Some(num) =>
          val ev = contractStateEvents(num)
          (ev.state, state) match {
            case (StoreContractState.Archived, _) => (this, summary)
            case (_, StoreContractState.Archived) => updatedState
            case (_, StoreContractState.Assigned(_)) if transferCounter >= ev.transferCounter =>
              updatedState
            case (_, StoreContractState.InFlight(_)) if transferCounter > ev.transferCounter =>
              updatedState
            case _ => (this, summary)
          }
      }
    }

    // Prune a contract if possible, i.e., it has been archived and there are no incomplete
    // transfers for it. Noop if the contract cannot be pruned.
    private def pruneContractState(
        cid: ContractId[_],
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val evNum = contractStateEventsById(cid)
      val ev = contractStateEvents(evNum)
      ev.state match {
        case StoreContractState.Archived =>
          val incompleteTransfers = incompleteTransfersById.get(cid)
          if (incompleteTransfers.isEmpty) {
            val createNum = createEventsById(cid)
            (
              copy(
                createEvents = createEvents - createNum,
                createEventsById = createEventsById - cid,
                contractStateEvents = contractStateEvents - evNum,
                contractStateEventsById = contractStateEventsById - cid,
                incompleteTransfersById = incompleteTransfersById - cid,
              ),
              summary.copy(
                prunedContracts = summary.prunedContracts :+ cid
              ),
            )
          } else {
            (this, summary)
          }
        case _ => (this, summary)
      }
    }

    private def ingestActiveContracts(
        contracts: Seq[ActiveContract],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      assert(offset.isEmpty, "state was not switched to update ingestion yet")
      contracts.foldLeft((this, IngestionSummary.empty[TXE])) { case ((st, summary), contract) =>
        val ev = contract.createdEvent
        if (p(ev)) {
          st.ingestCreatedEvent(
            ev,
            contract.domainId,
            contract.transferCounter,
            summary,
          )
        } else {
          (st, summary.copy(numFilteredCreatedEvents = summary.numFilteredCreatedEvents + 1))
        }
      }
    }

    // Used during bootstrapping to ingest in-flight transfer outs
    private def ingestIncompleteTransferOuts(
        evs: Seq[IncompleteTransferEvent.Out],
        summary: IngestionSummary[TXE],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      evs
        .foldLeft((this, summary)) { case ((st, summary), ev) =>
          if (p(ev.createdEvent)) {
            val (newStCreate, createSummary) = ingestCreatedEvent(
              ev.createdEvent,
              ev.transferEvent.source,
              // counter - 1 so that the transfer out event below overwrites the state produced by this.
              ev.transferEvent.counter - 1,
              summary,
            )
            newStCreate.ingestTransferOutEvent(ev.transferEvent, createSummary)
          } else {
            (
              st,
              summary.copy(numFilteredTransferOutEvents = summary.numFilteredTransferOutEvents + 1),
            )
          }
        }
    }

    private def ingestIncompleteTransferIns(
        evs: Seq[IncompleteTransferEvent.In],
        summary: IngestionSummary[TXE],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      evs
        .foldLeft((this, summary)) { case ((st, summary), ev) =>
          if (p(ev.transferEvent.createdEvent)) {
            st.ingestTransferInEvent(ev.transferEvent, summary)
          } else {
            (
              st,
              summary.copy(numFilteredTransferOutEvents = summary.numFilteredTransferOutEvents + 1),
            )
          }
        }
    }

    private def ingestCreatedEvent(
        ev: CreatedEvent,
        domain: DomainId,
        counter: Long,
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val cid = new ContractId(ev.getContractId)
      val (newSt, newSummary) = createEventsById.get(cid) match {
        case None =>
          (
            copy[TXI, TXE](
              nextCreateEventNumber = nextCreateEventNumber + 1,
              createEvents = createEvents + (nextCreateEventNumber -> ev),
              createEventsById = createEventsById + (cid -> nextCreateEventNumber),
            ),
            summary.copy(
              ingestedCreatedEvents = summary.ingestedCreatedEvents :+ ev
            ),
          )
        case Some(_) => (this, summary)
      }
      newSt.updateContractStateEvent(cid, counter, StoreContractState.Assigned(domain), newSummary)
    }

    private def ingestArchivedEvent(
        ev: ExercisedEvent,
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val contractId = new ContractId(ev.getContractId)
      createEventsById.get(contractId) match {
        case None =>
          // This can only happen if we filtered out the create event. We just ignore it in this case.
          (
            this,
            summary.copy(
              numFilteredArchivedEvents = summary.numFilteredArchivedEvents + 1
            ),
          )
        case Some(_) =>
          val stateEventNum = contractStateEventsById(contractId)
          val stateEvent = contractStateEvents(stateEventNum)
          val archivedSummary = summary.copy(
            ingestedArchivedEvents = summary.ingestedArchivedEvents :+ ev
          )
          val (newSt, newSummary) = updateContractStateEvent(
            contractId,
            stateEvent.transferCounter,
            StoreContractState.Archived,
            archivedSummary,
          )
          newSt.pruneContractState(contractId, newSummary)
      }
    }

    private def ingestTransferEvent(
        event: TransferEvent,
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) =
      event match {
        case out: TransferEvent.Out =>
          ingestTransferOutEvent(out, IngestionSummary.empty[TXE])
        case in: TransferEvent.In =>
          if (p(in.createdEvent)) {
            ingestTransferInEvent(in, IngestionSummary.empty[TXE])
          } else {
            (
              this,
              IngestionSummary
                .empty[TXE]
                .copy(
                  numFilteredTransferInEvents = 1
                ),
            )
          }
      }

    private def ingestTransferInEvent(
        in: TransferEvent.In,
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val transferId = TransferId.fromTransferIn(in)
      val cid = new ContractId(in.createdEvent.getContractId)
      // First update create events and contract locations by only looking at the create event.
      val (createSt, createSummary) = ingestCreatedEvent(
        in.createdEvent,
        in.target,
        in.counter,
        summary,
      )
      if (incompleteTransferOut.contains(transferId)) {
        val newIncompleteTransfersById = incompleteTransfersById.updatedWith(cid)(
          _.flatMap(prev => NonEmpty.from(prev - transferId))
        )
        val newSt: State[TXI, TXE] = createSt
          .copy(
            incompleteTransferOut = incompleteTransferOut - transferId,
            incompleteTransfersById = newIncompleteTransfersById,
          )
        val newSummary = createSummary.copy(
          removedTransferOutEvents = summary.removedTransferOutEvents :+ (cid, transferId)
        )
        newSt.pruneContractState(cid, newSummary)
      } else {
        (
          createSt.copy(
            incompleteTransferIn = incompleteTransferIn + transferId,
            incompleteTransfersById = incompleteTransfersById.updatedWith(cid) { prev =>
              Some(prev.fold(NonEmpty(Set, transferId))(_.incl(transferId)))
            },
          ),
          createSummary.copy(
            addedTransferInEvents = summary.addedTransferInEvents :+ (cid, transferId)
          ),
        )
      }
    }

    private def ingestTransferOutEvent(
        out: TransferEvent.Out,
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val transferId = TransferId.fromTransferOut(out)
      val cid = out.contractId
      if (!createEventsById.contains(out.contractId)) {
        (
          this,
          summary.copy(numFilteredTransferOutEvents = summary.numFilteredTransferOutEvents + 1),
        )
      } else {
        val (newSt, tfSummary): (State[TXI, TXE], IngestionSummary[TXE]) =
          if (incompleteTransferIn.contains(transferId)) {
            val newIncompleteTransfersById =
              incompleteTransfersById.updatedWith(cid)(
                _.flatMap(prev => NonEmpty.from(prev - transferId))
              )
            (
              copy(
                incompleteTransferIn = incompleteTransferIn - transferId,
                incompleteTransfersById = newIncompleteTransfersById,
              ),
              summary.copy(
                removedTransferInEvents = summary.removedTransferInEvents :+ (cid, transferId)
              ),
            )
          } else {
            (
              copy(
                incompleteTransferOut = incompleteTransferOut + transferId,
                incompleteTransfersById = incompleteTransfersById.updatedWith(cid) { prev =>
                  Some(prev.fold(NonEmpty(Set, transferId))(_.incl(transferId)))
                },
              ),
              summary.copy(
                addedTransferOutEvents = summary.addedTransferOutEvents :+ (cid, transferId)
              ),
            )
          }

        val (updatedSt, updatedSummary) = newSt
          .updateContractStateEvent(
            out.contractId,
            out.counter,
            StoreContractState.InFlight(out),
            tfSummary,
          )
        updatedSt.pruneContractState(cid, updatedSummary)
      }
    }

    /** Update the state by adding another offset whose ingestion should be signalled. If the signalling of that
      * offset has already been requested, don't change the state.
      */
    def addOffsetToSignal(
        offset: String
    ): (State[TXI, TXE], Future[Unit]) = {
      offsetIngestionsToSignal.get(offset) match {
        case None =>
          val p = Promise[Unit]()
          val newSt: State[TXI, TXE] = copy(
            offsetIngestionsToSignal = offsetIngestionsToSignal + (offset -> p)
          )
          (newSt, p.future)
        case Some(existingP) => (this, existingP.future)
      }
    }
  }
}
