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
    with LimitHelpers {

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
      nextTransferOutEventNumber = 0,
      transferOutEvents = SortedMap.empty,
      transferOutEventsById = Map.empty,
      earlyTransferIns = Set.empty,
      pendingTransfersById = Map.empty,
      archivedTombstones = Set.empty,
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
        transferOuts: Seq[InFlightTransferOutEvent],
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestAcs(
          acs,
          transferOuts,
          offset,
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
            companionClass.fromCreatedEvent(companion)(contractFilter, ev).map { parsedEv =>
              val state = st
                .getContractState(new ContractId(ev.getContractId))
              (parsedEv, state)
            }
          }),
      )
        .filter { case (ev, _) => filter(ev) }
        .toSeq
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
        } yield {
          val state = st
            .getContractState(new ContractId(ev.getContractId))
          (parsedEv, state)
        }
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
      st.lookupContractState(id)
    }

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
          } yield contract
        )
      )
      QueryResult(
        offset,
        optEntry.map { contract =>
          val state = st.getContractState(contract.contractId)
          ContractWithState(contract, state)
        },
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
            if state == ContractState.Assigned(domain)
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
    val optEntry = st.transferOutEvents.minAfter(startingFromIncl)
    optEntry match {
      case None =>
        st.offsetChanged.future.flatMap(_ => nextReadyForTransferIn(st.nextTransferOutEventNumber))
      case Some((eventNumber, out)) => Future((eventNumber + 1, out))
    }
  }

  override def isReadyForTransferIn(transfer: TransferId): Future[Boolean] =
    offsetAndStateAfterIngestingAcs().map { case (_, st) =>
      st.transferOutEventsById.contains(transfer)
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

  override def findLatestTxLogIndex[A, Z](init: Z)(p: (Z, TXI) => Either[A, Z])(implicit
      ec: ExecutionContext
  ): Future[A] = {
    import cats.implicits.*

    Future {
      stateVar.txLog
        .foldM[Either[A, *], Z](init) { case (z, t) => p(z, t.payload) }
        .fold(
          identity,
          _ =>
            throw Status.NOT_FOUND
              .withDescription("No matching log indices found")
              .asRuntimeException,
        )
    }
  }

  override def getLatestTxLogIndex(query: (TXI) => Boolean = (_: TXI) => true)(implicit
      ec: ExecutionContext
  ): Future[TXI] =
    Future {
      stateVar.txLog.view
        .map(_.payload)
        .filter(query)
        .headOption
        .getOrElse(
          throw Status.NOT_FOUND.withDescription("No matching log indices found").asRuntimeException
        )
    }

  override def getTxLogIndicesByFilter(filter: TXI => Boolean)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] =
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

  private[store] def contractStateEventsById: Future[Map[ContractId[_], Long]] =
    Future.successful(stateVar.contractStateEventsById)
  private[store] def archivedTombstones: Future[Set[ContractId[_]]] =
    Future.successful(stateVar.archivedTombstones)
  private[store] def pendingTransfersById: Future[Map[ContractId[_], NonEmpty[Set[TransferId]]]] =
    Future.successful(stateVar.pendingTransfersById)

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

  import MultiDomainAcsStore.{ContractState, TransferId}

  private case class ContractStateEvent(
      contractId: ContractId[_],
      domains: Set[DomainId],
  ) {

    /** Get the assigned domain of the contract
      * Returns None if there is not exactly one.
      */
    def toAssigned: Option[DomainId] =
      domains.headOption.filter(_ => domains.size == 1)
  }

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
    *   - added on the first create/tranfer in that matches the filter out if the contract is not in archivedTombstones.
    *   - removed on archive
    * - contractStateEvents
    *   - entry added on first transfer in/cretae
    *   - domain added to entry on transfer in/create
    *   - domain removed on transfer out
    *   - on archive, full entry is removed
    * - transferOutEvents
    *   - added on transfer out if we have the contract in createdEvents (so it matched our filter) and we do not yet
    *     have the transfer id in earlyTransferIns.
    *   - added on initial bootstrapping on in-flight transfer outs independent of filter
    *   - removed on matching transferIn
    * - earlyTransferIns
    *   - added on transfer in if it matches our filter and we do not yet have the transfer id in earlyTransferIns
    *   - removed on matching transfer out
    * - pendingTransfersById: index on top of transferOutEvents/earlyTransferIns, always updated together
    * - archivedTombstones
    *   - added: archive if there are still pending transfers
    *   - removed: when the last pending transfer is removed.
    *
    * In-flight state: Currently, we cannot reliably order transfers for a given contract
    * across domains. Therefore we have to mark contracts as in-flight if we have seen it
    * move into different domains but have not yet seen all corresponding transfer outs. We hope that
    * eventually, Canton will provide us with a per-contract transfer counter
    * that allows us to resolve those cases faster.
    *
    * TODO(#3596) These edge cases should go away with transfer counters.
    * Bootstrapping: When bootstrapping from the ACS and in-flight contracts we end up with one
    * edge cases:
    * 1. We see the transfer in on the target domain but we don't see an in-flight transfer out
    *    when bootstrapping on the source domain (because the participant has
    *    already processed the transfer in). In this case, the transfer in will forever
    *    remain in `earyTransferIns` and therefore if we ever archive it it will also remain in
    *    `archivedTombstones`. This can only occur for contracts that are in-flight at bootstrapping
    *    and we only end up leaking constant-size data rather than the full payloads. Therefore,
    *    we accept this for now.
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

      // in-flights
      nextTransferOutEventNumber: Long,
      // Transfer out for which we have not yet seen the transfer in.
      transferOutEvents: SortedMap[Long, TransferEvent.Out],
      transferOutEventsById: Map[TransferId, Long],
      // Transfer ins for which we have not yet seen the transfer out.
      earlyTransferIns: Set[TransferId],
      // In-flight transfers that are either in transferOutEvents or
      // earlyTransferIns for the given contract id.
      pendingTransfersById: Map[ContractId[_], NonEmpty[Set[TransferId]]],

      // Contracts that have already been archived but
      // there are still in-flight requests we might receive.
      // We track those so we can make sure to ignore them instead of
      // e.g., readding the contract to the active contracts on a delayed
      // transfer in. We remove entries as soon as
      // there are no entries in transferOutEvents and earlyTransferIns
      // for the contract.
      archivedTombstones: Set[ContractId[_]],
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

    def lookupContractState(cid: ContractId[_]): Option[ContractState] =
      contractStateEventsById.get(cid).map { eventNum =>
        val stateEvent = contractStateEvents(eventNum)
        stateEvent.toAssigned match {
          case Some(domain) =>
            ContractState.Assigned(domain)
          case None =>
            assert(!pendingTransfersById.get(cid).isEmpty)
            ContractState.InFlight
        }
      }

    def getContractState(cid: ContractId[_]): ContractState =
      lookupContractState(cid).getOrElse(
        throw new IllegalStateException(s"Failed to find contract state for $cid")
      )

    def ingestAcs(
        evs: Seq[ActiveContract],
        transferOuts: Seq[InFlightTransferOutEvent],
        acsOffset: String,
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offset.isEmpty, "state was not switched to update ingestion yet")
      val (stAcs, summaryAcs) = ingestActiveContracts(evs, p)
      val (stInFlight, summaryTransferOut) = stAcs.ingestTransferOuts(transferOuts, summaryAcs, p)
      val (stFinal, offsetsToRemove) = stInFlight.removeOffsetSignalsBefore(acsOffset)
      (
        stFinal.copy(offset = Some(acsOffset), offsetChanged = Promise()),
        (
          summaryTransferOut.copy(
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

      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredCreatedEvents = 0
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredArchivedEvents = 0
      val ingestedCreatedEvents = Seq.newBuilder[CreatedEvent]
      val ingestedArchivedEvents = Seq.newBuilder[ExercisedEvent]
      val addedContractLocations = Seq.newBuilder[ContractLocation]
      val removedContractLocations = Seq.newBuilder[ContractLocation]
      val addedArchivedTombstones = Seq.newBuilder[ContractId[_]]

      val stNew = Trees.foldTree(tx, this)(
        onCreate = (st, ev, _) => {
          if (p(ev)) {
            val location = ContractLocation(new ContractId(ev.getContractId), domain)
            val (stNew, summary) = st.ingestCreatedEvent(
              ev,
              location,
            )
            summary match {
              case CreatedEventSummary.SkippedTombstone =>
              case CreatedEventSummary.Added(first, location) =>
                if (first) {
                  ingestedCreatedEvents += ev
                }
                addedContractLocations += location
            }
            stNew
          } else {
            numFilteredCreatedEvents += 1
            st
          }
        },
        onExercise = (st, ev, _) => {
          if (ev.isConsuming) {
            val cid = new ContractId(ev.getContractId)
            val (newSt, summary) = st.ingestArchivedEvent(cid)
            summary match {
              case ArchivedEventSummary.Filtered =>
                numFilteredArchivedEvents += 1
              case ArchivedEventSummary.Ingested(tombstone, removedLocations) =>
                if (tombstone) {
                  addedArchivedTombstones += cid
                }
                removedContractLocations ++= removedLocations
            }
            newSt
          } else {
            st
          }
        },
      )

      val ingestedTxLogEntries = txLogParser.parse(tx, logger)

      val newOffset = tx.getOffset

      val summary = IngestionSummary.empty.copy(
        txId = Some(tx.getTransactionId),
        offset = Some(newOffset),
        newAcsSize = stNew.createEvents.size,
        ingestedCreatedEvents = ingestedCreatedEvents.result(),
        ingestedArchivedEvents = ingestedArchivedEvents.result(),
        numFilteredCreatedEvents = numFilteredCreatedEvents,
        numFilteredArchivedEvents = numFilteredArchivedEvents,
        addedContractLocations = addedContractLocations.result(),
        removedContractLocations = removedContractLocations.result(),
        addedArchivedTombstones = addedArchivedTombstones.result(),
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
        (summary, offsetChanged, offsetsToRemove.values),
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
        update: Set[DomainId] => Set[DomainId],
    ): State[TXI, TXE] = {
      val evNum = contractStateEventsById.get(cid)
      val oldDomains = evNum.map(contractStateEvents(_)).fold(Set.empty[DomainId])(_.domains)
      val newEv = ContractStateEvent(cid, update(oldDomains))
      copy(
        contractStateEvents =
          contractStateEvents -- evNum + (nextContractStateEventNumber -> newEv),
        contractStateEventsById = contractStateEventsById + (cid -> nextContractStateEventNumber),
        nextContractStateEventNumber = nextContractStateEventNumber + 1,
      )
    }

    private def removeContractStateEvent(
        cid: ContractId[_]
    ): State[TXI, TXE] = {
      val evNum = contractStateEventsById.getOrElse(
        cid,
        throw new IllegalStateException(s"Contract id $cid is not in contractStateEventsById"),
      )
      copy(
        contractStateEvents = contractStateEvents - evNum,
        contractStateEventsById = contractStateEventsById - cid,
      )
    }

    private def ingestActiveContracts(
        contracts: Seq[ActiveContract],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      assert(offset.isEmpty, "state was not switched to update ingestion yet")
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredCreatedEvents = 0
      val ingestedCreatedEvents = Seq.newBuilder[CreatedEvent]
      val addedContractLocations = Seq.newBuilder[ContractLocation]
      val stNew = contracts.foldLeft(this)((st, contract) => {
        val ev = contract.createdEvent
        if (p(ev)) {
          val (stNew, summary) = st.ingestCreatedEvent(
            ev,
            ContractLocation(new ContractId(ev.getContractId), contract.domainId),
          )
          summary match {
            case CreatedEventSummary.SkippedTombstone =>
            case CreatedEventSummary.Added(first, location) =>
              if (first) {
                ingestedCreatedEvents += ev
              }
              addedContractLocations += location
          }
          stNew
        } else {
          numFilteredCreatedEvents += 1
          st
        }
      })
      (
        stNew,
        IngestionSummary
          .empty[TXE]
          .copy(
            ingestedCreatedEvents = ingestedCreatedEvents.result(),
            addedContractLocations = addedContractLocations.result(),
            numFilteredCreatedEvents = numFilteredCreatedEvents,
          ),
      )
    }

    // Used during bootstrapping to ingest in-flight transfer outs
    private def ingestTransferOuts(
        evs: Seq[InFlightTransferOutEvent],
        summary: IngestionSummary[TXE],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      val addedTransferOuts = Seq.newBuilder[(ContractId[_], TransferId)]
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredTransferOuts = 0
      val removedTransferIns = Seq.newBuilder[(ContractId[_], TransferId)]
      val removedArchivedTombstones = Seq.newBuilder[ContractId[_]]
      val stNew: State[TXI, TXE] = evs
        .foldLeft(this)((st, ev) => {
          if (p(ev.createdEvent)) {
            val (newStCreate, _) = ingestCreatedEvent(
              ev.createdEvent,
              ContractLocation(ev.transferEvent.contractId, ev.transferEvent.source),
            )
            val id = TransferId.fromTransferOut(ev.transferEvent)
            val (newSt, summary) = newStCreate.ingestTransferOutEvent(ev.transferEvent)
            summary match {
              case TransferOutEventSummary.Filtered =>
                numFilteredTransferOuts += 1
              case TransferOutEventSummary.AddedTransferOut =>
                addedTransferOuts += ((ev.transferEvent.contractId, id))
              case TransferOutEventSummary.RemovedTransferIn(removedTombstone) =>
                removedTransferIns += ((ev.transferEvent.contractId, id))
                if (removedTombstone) {
                  removedArchivedTombstones += ev.transferEvent.contractId
                }
            }
            newSt
          } else {
            numFilteredTransferOuts += 1
            st
          }
        })
      (
        stNew,
        summary.copy(
          addedTransferOutEvents = addedTransferOuts.result(),
          removedTransferInEvents = removedTransferIns.result(),
          removedArchivedTombstones = removedArchivedTombstones.result(),
        ),
      )
    }

    private def ingestCreatedEvent(
        ev: CreatedEvent,
        location: ContractLocation,
    ): (State[TXI, TXE], CreatedEventSummary) = {
      val cid = new ContractId(ev.getContractId)
      if (archivedTombstones.contains(cid)) {
        (this, CreatedEventSummary.SkippedTombstone)
      } else {
        val newSt =
          updateContractStateEvent(cid, _.incl(location.domain))
        createEventsById.get(cid) match {
          case None =>
            (
              newSt.copy(
                nextCreateEventNumber = nextCreateEventNumber + 1,
                createEvents = createEvents + (nextCreateEventNumber -> ev),
                createEventsById = createEventsById + (cid -> nextCreateEventNumber),
              ),
              CreatedEventSummary.Added(true, location),
            )
          case Some(_) =>
            // We already saw the contract before in an earlier transfer in or create
            (newSt, CreatedEventSummary.Added(false, location))
        }
      }
    }

    private def ingestArchivedEvent(
        contractId: ContractId[_]
    ): (State[TXI, TXE], ArchivedEventSummary) = {
      createEventsById.get(contractId) match {
        case None =>
          // This can only happen if we filtered out the create event. We just ignore it in this case.
          (this, ArchivedEventSummary.Filtered)
        case Some(createId) =>
          // When we see an archive, we drop all state for that contract immediately.
          // If we might still see events later, we add a tombstone marker that is only used to
          // ignore all events and will be removed once we know that there will be no further events.
          val stateEventNum = contractStateEventsById.getOrElse(
            contractId,
            throw new IllegalStateException(
              s"Contract id $contractId not in contractStateEventsById on archive"
            ),
          )
          val stateEvent = contractStateEvents(stateEventNum)
          val newSt: State[TXI, TXE] = removeContractStateEvent(contractId).copy(
            createEvents = createEvents - createId,
            createEventsById = createEventsById - contractId,
            archivedTombstones =
              if (pendingTransfersById.contains(contractId)) (archivedTombstones + contractId)
              else archivedTombstones,
          )
          (
            newSt,
            ArchivedEventSummary.Ingested(
              newSt.archivedTombstones.contains(contractId),
              stateEvent.domains.map(ContractLocation(contractId, _)),
            ),
          )
      }
    }

    private def ingestTransferEvent(
        event: TransferEvent,
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) =
      event match {
        case out: TransferEvent.Out =>
          val (newSt, tfSummary) = ingestTransferOutEvent(out)
          val ev: (ContractId[_], TransferId) = (out.contractId, TransferId.fromTransferOut(out))

          val summary = tfSummary match {
            case TransferOutEventSummary.Filtered =>
              IngestionSummary
                .empty[TXE]
                .copy(
                  numFilteredTransferOutEvents = 1
                )
            case TransferOutEventSummary.AddedTransferOut =>
              IngestionSummary
                .empty[TXE]
                .copy(
                  addedTransferOutEvents = Seq(ev)
                )
            case TransferOutEventSummary.RemovedTransferIn(removedTombstone) =>
              IngestionSummary
                .empty[TXE]
                .copy(
                  removedTransferInEvents = Seq(ev),
                  removedArchivedTombstones =
                    if (removedTombstone) Seq(out.contractId)
                    else Seq.empty,
                )
          }

          (newSt, summary)
        case in: TransferEvent.In =>
          if (p(in.createdEvent)) {
            val (newSt, createdSummary, transferInSummary) = ingestTransferInEvent(in)
            val summary = createdSummary match {
              case CreatedEventSummary.SkippedTombstone => IngestionSummary.empty[TXE]
              case CreatedEventSummary.Added(first, location) =>
                IngestionSummary
                  .empty[TXE]
                  .copy(
                    ingestedCreatedEvents = if (first) Seq(in.createdEvent) else Seq.empty,
                    addedContractLocations = Seq(location),
                  )
            }
            val tfid = TransferId.fromTransferIn(in)
            val cid = new ContractId(in.createdEvent.getContractId)
            val transferSummary = transferInSummary match {
              case TransferInEventSummary.AddedTransferIn =>
                summary.copy(
                  addedTransferInEvents = Seq((cid, tfid))
                )
              case TransferInEventSummary.RemovedTransferOut(
                    removedTombstone
                  ) =>
                summary.copy(
                  removedArchivedTombstones = if (removedTombstone) Seq(cid) else Seq.empty,
                  removedTransferOutEvents = Seq((cid, tfid)),
                )
            }
            (
              newSt,
              transferSummary,
            )
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
        in: TransferEvent.In
    ): (State[TXI, TXE], CreatedEventSummary, TransferInEventSummary) = {
      val transferId = TransferId.fromTransferIn(in)
      val cid = new ContractId(in.createdEvent.getContractId)
      // First update create events and contract locations by only looking at the create event.
      val (createSt, createSummary) = ingestCreatedEvent(
        in.createdEvent,
        ContractLocation(
          new ContractId(in.createdEvent.getContractId),
          in.target,
        ),
      )
      transferOutEventsById.get(transferId) match {
        case None =>
          // early transfer in
          (
            createSt.copy(
              earlyTransferIns = earlyTransferIns + transferId,
              pendingTransfersById = pendingTransfersById.updatedWith(cid) { prev =>
                Some(prev.fold(NonEmpty(Set, transferId))(_.incl(transferId)))
              },
            ),
            createSummary,
            TransferInEventSummary.AddedTransferIn,
          )
        case Some(eventId) =>
          val newPendingTransfersById = pendingTransfersById.updatedWith(cid)(
            _.flatMap(prev => NonEmpty.from(prev - transferId))
          )
          val removeTombstone = !newPendingTransfersById.contains(cid)
          (
            createSt.copy(
              transferOutEvents = transferOutEvents - eventId,
              transferOutEventsById = transferOutEventsById - transferId,
              pendingTransfersById = newPendingTransfersById,
              archivedTombstones =
                if (removeTombstone) archivedTombstones - cid else archivedTombstones,
            ),
            createSummary,
            TransferInEventSummary.RemovedTransferOut(
              removedTombstone = removeTombstone
            ),
          )
      }
    }

    private def ingestTransferOutEvent(
        out: TransferEvent.Out
    ): (State[TXI, TXE], TransferOutEventSummary) = {
      val transferId = TransferId.fromTransferOut(out)
      val cid = out.contractId
      val (newSt, summary): (State[TXI, TXE], TransferOutEventSummary) =
        if (earlyTransferIns.contains(transferId)) {
          val newPendingTransfersById =
            pendingTransfersById.updatedWith(cid)(
              _.flatMap(prev => NonEmpty.from(prev - transferId))
            )
          val removeTombstone = !newPendingTransfersById.contains(cid)
          (
            copy(
              earlyTransferIns = earlyTransferIns - transferId,
              pendingTransfersById = newPendingTransfersById,
              archivedTombstones =
                if (removeTombstone) archivedTombstones - cid
                else archivedTombstones,
            ),
            TransferOutEventSummary.RemovedTransferIn(removeTombstone),
          )
        } else {
          (
            copy(
              nextTransferOutEventNumber = nextTransferOutEventNumber + 1,
              transferOutEvents = transferOutEvents + (nextTransferOutEventNumber -> out),
              transferOutEventsById =
                transferOutEventsById + (transferId -> nextTransferOutEventNumber),
              pendingTransfersById = pendingTransfersById.updatedWith(cid) { prev =>
                Some(prev.fold(NonEmpty(Set, transferId))(_.incl(transferId)))
              },
            ),
            TransferOutEventSummary.AddedTransferOut,
          )
        }

      if (archivedTombstones.contains(out.contractId)) {
        // We've already seen the archive, all we care about is updating pending
        // transfers.
        (newSt, summary)
      } else {
        // Whether we saw the contract move into the domain
        // via a create or transfer in
        val sawMoveIn =
          contractStateEventsById.get(out.contractId).fold(false) { num =>
            val ev = contractStateEvents(num)
            ev.domains.contains(out.source)
          }
        if (!sawMoveIn) {
          // We did not see a transfer in or create. That means
          // we filtered it so we can just ignore it.
          // We deliberately use `this` instead of `newSt` here. We don't want to update
          // pending transfers for ignored contracts.
          (this, TransferOutEventSummary.Filtered)
        } else {
          (
            newSt.updateContractStateEvent(out.contractId, _.excl(out.source)),
            summary,
          )
        }
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

  private sealed abstract class CreatedEventSummary

  private object CreatedEventSummary {
    case object SkippedTombstone extends CreatedEventSummary
    // first is true if this is the first event we've seen for this contract.
    case class Added(first: Boolean, location: ContractLocation) extends CreatedEventSummary
  }

  private sealed abstract class ArchivedEventSummary
  private object ArchivedEventSummary {
    case object Filtered extends ArchivedEventSummary
    case class Ingested(tombstone: Boolean, removedLocations: Iterable[ContractLocation])
        extends ArchivedEventSummary
  }

  private sealed abstract class TransferOutEventSummary extends Product with Serializable
  private object TransferOutEventSummary {
    case object Filtered extends TransferOutEventSummary
    case object AddedTransferOut extends TransferOutEventSummary
    case class RemovedTransferIn(removedTombstone: Boolean) extends TransferOutEventSummary
  }

  private sealed abstract class TransferInEventSummary extends Product with Serializable
  private object TransferInEventSummary {
    case object AddedTransferIn extends TransferInEventSummary
    case class RemovedTransferOut(removedTombstone: Boolean) extends TransferInEventSummary
  }
}
