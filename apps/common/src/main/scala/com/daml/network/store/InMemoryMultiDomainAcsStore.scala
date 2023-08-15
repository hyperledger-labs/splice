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
import com.daml.network.util.{Contract, ContractWithState, AssignedContract, Trees}
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
    with StoreErrors {

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
      incompleteUnassign = Set.empty,
      incompleteAssign = Set.empty,
      incompleteReassignmentsById = Map.empty,
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
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestAcs(
          acs,
          incompleteOut,
          incompleteIn,
          offset,
          txLogParser,
          contractFilter,
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
            _.ingestTransaction(domain, tree, contractFilter, txLogParser, logger)
          ).map { case (summary, offsetChanged, offsetIngestionsToSignal) =>
            logger.debug(show"Ingested transaction $summary")
            offsetIngestionsToSignal.foreach(_.success(()))
            offsetChanged.success(())
          }
        case ReassignmentUpdate(transfer) =>
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

  override def listAssignedContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[AssignedContract[TCid, T]]] = {
    filterAssignedContracts(companion, _ => true, limit)
  }

  def filterAssignedContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[AssignedContract[TCid, T]]] =
    for {
      contracts <- filterContracts(companion, filter, limit)
    } yield contracts.view.collect(Function.unlift(_.toAssignedContract)).toSeq

  override private[network] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[
    T
  ], T <: Template](companion: C)(
      expiresAt: T => Instant
  )(implicit companionClass: ContractCompanion[C, TCid, T]): ListExpiredContracts[TCid, T] =
    (now, limit) =>
      implicit traceContext =>
        filterAssignedContracts(
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

  def hasArchived(ids: Seq[ContractId[?]])(implicit
      traceContext: TraceContext
  ): Future[Boolean] = Future.sequence(ids.map(lookupContractStateById)).map(_.exists(_.isEmpty))

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

  override def streamReadyForAssign(
  ): Source[ReassignmentEvent.Unassign, NotUsed] =
    Source
      .unfoldAsync(0: Long)(eventNumber => nextReadyForAssign(eventNumber).map(Some(_)))

  private def nextReadyForAssign(
      startingFromIncl: Long
  ): Future[(Long, ReassignmentEvent.Unassign)] = {
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
        st.offsetChanged.future.flatMap(_ => nextReadyForAssign(st.nextContractStateEventNumber))
      case Some((eventNumber, co)) => Future((eventNumber + 1, co))
    }
  }

  override def isReadyForAssign(
      contractId: ContractId[_],
      transfer: ReassignmentId,
  ): Future[Boolean] =
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      st.contractStateEventsById.get(contractId).fold(false) { num =>
        val contractState = st.contractStateEvents(num).state
        contractState match {
          case StoreContractState.InFlight(out) =>
            ReassignmentId.fromUnassign(out) == transfer
          case _ => false
        }
      }
    }

  private def nextAssignedContract[T](
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
          nextAssignedContract(fromCreated, st.nextContractStateEventNumber)
        )
      case Some((eventNumber, co)) => Future((eventNumber + 1, co))
    }
  }

  private def streamAssignedContracts[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  ): Source[(T, DomainId), NotUsed] = {
    Source.unfoldAsync(0: Long)(eventNumber =>
      nextAssignedContract(fromCreatedEvent, eventNumber).map(Some(_))
    )
  }

  override def streamAssignedContracts[C, TCid <: ContractId[_], T](
      companion: C
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[AssignedContract[TCid, T], NotUsed] = {
    requireInScope(companion)
    streamAssignedContracts(companionClass.fromCreatedEvent(companion)(contractFilter, _)).map {
      case (contract, domain) => AssignedContract(contract, domain)
    }
  }

  /** TX log APIs
    */

  def filterTxLogIndicesByOffset(offset: Int, limit: Int)(filter: TXI => Boolean): Seq[TXI] =
    stateVar.txLog.view.drop(offset).filter(filter).take(limit).toSeq

  def filterTxLogIndicesAfterEventId(domainId: DomainId, beginAfterEventId: String, limit: Int)(
      filter: TXI => Boolean
  ): Seq[TXI] =
    stateVar.txLog.view
      .filter(txi => txi.domainId == domainId && filter(txi))
      .dropWhile(_.eventId != beginAfterEventId)
      .slice(1, 1 + limit)
      .toSeq

  def findLatestTxLogIndex[A, Z](init: Z)(p: (Z, TXI) => Either[A, Z])(implicit
      ec: ExecutionContext
  ): Future[A] = {
    import cats.implicits.*

    Future {
      stateVar.txLog
        .foldM[Either[A, *], Z](init) { case (z, t) => p(z, t) }
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
        .find(query)
        .getOrElse(throw txLogNotFound())
    }

  def getTxLogIndicesByFilter(filter: TXI => Boolean): Future[Seq[TXI]] =
    Future.successful(stateVar.txLog.view.filter(filter).toSeq)

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
  private[store] def incompleteReassignmentsById
      : Future[Map[ContractId[_], NonEmpty[Set[ReassignmentId]]]] =
    Future.successful(stateVar.incompleteReassignmentsById)

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

  import MultiDomainAcsStore.{ContractStateEvent, StoreContractState, ReassignmentId}

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
    * - incomplete assignment
    *   - added on assignment if contract matches filter and matching unassign has not been observed
    *   - removed on matching unassign
    * - incompleteReassignmentsById: index on top of incomplete assign/incomplete unassign, always updated together
    */
  private case class State[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      offset: Option[String],
      nextCreateEventNumber: Long,
      // All active contracts across all domains that match our contract filter. Contracts are inserted
      // when we see the first create or assign and removed on archive.
      // Event number stays stable across reassignments.
      createEvents: SortedMap[Long, CreatedEvent],
      createEventsById: Map[ContractId[_], Long],
      nextContractStateEventNumber: Long,
      // States of all active contracts.
      // Each contract will be in there only once
      // but contracts get readded with a new
      // event number as their state changes.
      contractStateEvents: SortedMap[Long, ContractStateEvent],
      contractStateEventsById: Map[ContractId[_], Long],

      // incomplete reassignments
      incompleteUnassign: Set[ReassignmentId],
      incompleteAssign: Set[ReassignmentId],
      incompleteReassignmentsById: Map[ContractId[_], NonEmpty[Set[ReassignmentId]]],
      offsetChanged: Promise[Unit],
      offsetIngestionsToSignal: SortedMap[String, Promise[Unit]],
      txLog: Queue[TXI],
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
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
        acsOffset: String,
        txLogParser: TxLogStore.Parser[TXI, TXE],
        contractFilter: MultiDomainAcsStore.ContractFilter,
    )(implicit
        tc: TraceContext
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isEmpty, "state was not switched to update ingestion yet")
      val (stAcs, summaryAcs) = ingestActiveContracts(evs, contractFilter)
      val (stInFlightOut, summaryUnassign) =
        stAcs.ingestIncompleteUnssigns(incompleteOut, summaryAcs, contractFilter.contains)
      val (stInFlightIn, summaryAssign) =
        stInFlightOut.ingestIncompleteAssigns(
          incompleteIn,
          summaryUnassign,
          contractFilter.contains,
        )
      val (stFinal, offsetsToRemove) = stInFlightIn.removeOffsetSignalsBefore(acsOffset)
      val parsedTxLogEntries = txLogParser.parseAcs(evs, incompleteOut, incompleteIn)
      (
        stFinal.copy(
          offset = Some(acsOffset),
          offsetChanged = Promise(),
          txLog = parsedTxLogEntries.reverse
            .map({ case (_, entry) =>
              entry.indexRecord
            })
            .to(collection.immutable.Queue),
        ),
        (
          summaryAssign.copy(
            newAcsSize = stFinal.createEvents.size,
            ingestedTxLogEntries = parsedTxLogEntries.map(_._2),
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
        contractFilter: MultiDomainAcsStore.ContractFilter,
        txLogParser: TxLogStore.Parser[TXI, TXE],
        logger: TracedLogger,
    )(implicit
        traceContext: TraceContext
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isDefined, "state was switched to update ingestion")

      val (stNew, summary) = Trees.foldTree(tx, (this, IngestionSummary.empty[TXE]))(
        onCreate = {
          case ((st, summary), ev, _) => {
            if (contractFilter.contains(ev)) {
              contractFilter.ensureStakeholderOf(ev)
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

      val ingestedTxLogEntries = txLogParser.parse(tx, domain, logger)

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
            ingestedTxLogEntries.reverse.map(entry => entry.indexRecord)
          ),
        ),
        (newSummary, offsetChanged, offsetsToRemove.values),
      )
    }

    def ingestTransfer(
        transfer: Reassignment[ReassignmentEvent],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isDefined, "state was switched to update ingestion")
      val (newSt, summary) = ingestReassignmentEvent(transfer.event, p)
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
          val incompleteTransfers = incompleteReassignmentsById.get(cid)
          if (incompleteTransfers.isEmpty) {
            val createNum = createEventsById(cid)
            (
              copy(
                createEvents = createEvents - createNum,
                createEventsById = createEventsById - cid,
                contractStateEvents = contractStateEvents - evNum,
                contractStateEventsById = contractStateEventsById - cid,
                incompleteReassignmentsById = incompleteReassignmentsById - cid,
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
        contractFilter: MultiDomainAcsStore.ContractFilter,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      assert(offset.isEmpty, "state was not switched to update ingestion yet")
      contracts.foldLeft((this, IngestionSummary.empty[TXE])) { case ((st, summary), contract) =>
        val ev = contract.createdEvent
        if (contractFilter.contains(ev)) {
          contractFilter.ensureStakeholderOf(ev)
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

    // Used during bootstrapping to ingest in-flight unassigns
    private def ingestIncompleteUnssigns(
        evs: Seq[IncompleteReassignmentEvent.Unassign],
        summary: IngestionSummary[TXE],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      evs
        .foldLeft((this, summary)) { case ((st, summary), ev) =>
          if (p(ev.createdEvent)) {
            val (newStCreate, createSummary) = ingestCreatedEvent(
              ev.createdEvent,
              ev.reassignmentEvent.source,
              // counter - 1 so that the unassign event below overwrites the state produced by this.
              ev.reassignmentEvent.counter - 1,
              summary,
            )
            newStCreate.ingestUnassignEvent(ev.reassignmentEvent, createSummary)
          } else {
            (
              st,
              summary.copy(numFilteredUnassignEvents = summary.numFilteredUnassignEvents + 1),
            )
          }
        }
    }

    private def ingestIncompleteAssigns(
        evs: Seq[IncompleteReassignmentEvent.Assign],
        summary: IngestionSummary[TXE],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      evs
        .foldLeft((this, summary)) { case ((st, summary), ev) =>
          if (p(ev.reassignmentEvent.createdEvent)) {
            st.ingestAssignEvent(ev.reassignmentEvent, summary)
          } else {
            (
              st,
              summary.copy(numFilteredUnassignEvents = summary.numFilteredUnassignEvents + 1),
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

    private def ingestReassignmentEvent(
        event: ReassignmentEvent,
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) =
      event match {
        case unassign: ReassignmentEvent.Unassign =>
          ingestUnassignEvent(unassign, IngestionSummary.empty[TXE])
        case assign: ReassignmentEvent.Assign =>
          if (p(assign.createdEvent)) {
            ingestAssignEvent(assign, IngestionSummary.empty[TXE])
          } else {
            (
              this,
              IngestionSummary
                .empty[TXE]
                .copy(
                  numFilteredAssignEvents = 1
                ),
            )
          }
      }

    private def ingestAssignEvent(
        assign: ReassignmentEvent.Assign,
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val reassignmentId = ReassignmentId.fromAssign(assign)
      val cid = new ContractId(assign.createdEvent.getContractId)
      // First update create events and contract locations by only looking at the create event.
      val (createSt, createSummary) = ingestCreatedEvent(
        assign.createdEvent,
        assign.target,
        assign.counter,
        summary,
      )
      if (incompleteUnassign.contains(reassignmentId)) {
        val newincompleteReassignmentsById = incompleteReassignmentsById.updatedWith(cid)(
          _.flatMap(prev => NonEmpty.from(prev - reassignmentId))
        )
        val newSt: State[TXI, TXE] = createSt
          .copy(
            incompleteUnassign = incompleteUnassign - reassignmentId,
            incompleteReassignmentsById = newincompleteReassignmentsById,
          )
        val newSummary = createSummary.copy(
          removedUnassignEvents = summary.removedUnassignEvents :+ (cid, reassignmentId)
        )
        newSt.pruneContractState(cid, newSummary)
      } else {
        (
          createSt.copy(
            incompleteAssign = incompleteAssign + reassignmentId,
            incompleteReassignmentsById = incompleteReassignmentsById.updatedWith(cid) { prev =>
              Some(prev.fold(NonEmpty(Set, reassignmentId))(_.incl(reassignmentId)))
            },
          ),
          createSummary.copy(
            addedAssignEvents = summary.addedAssignEvents :+ (cid, reassignmentId)
          ),
        )
      }
    }

    private def ingestUnassignEvent(
        out: ReassignmentEvent.Unassign,
        summary: IngestionSummary[TXE],
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val reassignmentId = ReassignmentId.fromUnassign(out)
      val cid = out.contractId
      if (!createEventsById.contains(out.contractId)) {
        (
          this,
          summary.copy(numFilteredUnassignEvents = summary.numFilteredUnassignEvents + 1),
        )
      } else {
        val (newSt, tfSummary): (State[TXI, TXE], IngestionSummary[TXE]) =
          if (incompleteAssign.contains(reassignmentId)) {
            val newincompleteReassignmentsById =
              incompleteReassignmentsById.updatedWith(cid)(
                _.flatMap(prev => NonEmpty.from(prev - reassignmentId))
              )
            (
              copy(
                incompleteAssign = incompleteAssign - reassignmentId,
                incompleteReassignmentsById = newincompleteReassignmentsById,
              ),
              summary.copy(
                removedAssignEvents = summary.removedAssignEvents :+ (cid, reassignmentId)
              ),
            )
          } else {
            (
              copy(
                incompleteUnassign = incompleteUnassign + reassignmentId,
                incompleteReassignmentsById = incompleteReassignmentsById.updatedWith(cid) { prev =>
                  Some(prev.fold(NonEmpty(Set, reassignmentId))(_.incl(reassignmentId)))
                },
              ),
              summary.copy(
                addedUnassignEvents = summary.addedUnassignEvents :+ (cid, reassignmentId)
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
