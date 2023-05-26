package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.{
  InFlightTransferOutEvent,
  TransactionTreeUpdate,
  Transfer,
  TransferEvent,
  TransferUpdate,
  TreeUpdate,
}
import com.daml.network.util.{Contract, Trees}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import scala.collection.immutable.{Queue, SortedMap}
import scala.concurrent.*

class InMemoryMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: MultiDomainAcsStore.ContractFilter,
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    futureSupervisor: FutureSupervisor,
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends MultiDomainAcsStore
    with TxLogStore[TXI, TXE]
    with NamedLogging {

  import MultiDomainAcsStore.*

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryMultiDomainAcsStore.State[TXI, TXE] =
    InMemoryMultiDomainAcsStore.State(
      offsets = Map.empty,
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
      bootstrapTransferOutIds = Set.empty,
      archivedTombstones = Set.empty,
      offsetChanged = Promise(),
      offsetIngestionsToSignal = Map.empty,
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

    override def getLastIngestedOffset(domain: DomainId): Future[Option[String]] =
      Future.successful(stateVar.offsets.get(domain))

    override def ingestAcsAndTransferOuts(
        domain: DomainId,
        acs: Seq[CreatedEvent],
        transferOuts: Seq[InFlightTransferOutEvent],
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestAcsAndTransferOuts(domain, acs, transferOuts, contractFilter.contains)
      ).map { summary =>
        logger.debug(
          show"Ingested ACS and in-flight update: $summary"
        )
      }

    override def switchToIngestingUpdates(
        domain: DomainId,
        offset: String,
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.switchToIngestingUpdates(domain, offset)
      ).map {
        case (offsetChanged, offsetIngestionsToSignal) => {
          logger.debug(
            show"Ingested complete ACS and in-flight transfers for domain $domain at offset ${offset}"
          )
          offsetIngestionsToSignal.foreach(_.success(()))
          offsetChanged.success(())
        }
      }

    override def ingestOffset(domain: DomainId, offset: String)(implicit
        traceContext: TraceContext
    ): Future[Unit] =
      updateState(
        _.ingestOffset(domain, offset)
      ).map { case (offsetChanged, offsetIngestionsToSignal) =>
        logger.debug(show"Advanced offset for domain $domain to offset $offset")
        offsetIngestionsToSignal.foreach(_.success(()))
        offsetChanged.success(())
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
            _.ingestTransfer(domain, transfer, contractFilter.contains)
          ).map { case (summary, offsetChanged, offsetIngestionsToSignal) =>
            logger.debug(show"Ingested transfer $summary")
            offsetIngestionsToSignal.foreach(_.success(()))
            offsetChanged.success(())
          }
      }

    override def removeTransferOutIfBootstrap(
        cid: ContractId[_],
        transferId: TransferId,
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.removeTransferOutIfBootstrap(cid, transferId)
      ).map { summary =>
        logger.debug(show"Ingested removeTransferOutIfBootstrap: $summary")
      }
  }

  private def requireInScope[C, TCid <: ContractId[_], T](
      companion: C
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Unit =
    require(
      companionClass.mightContain(contractFilter)(companion),
      s"template ${companionClass.typeId(companion)} is part of the contract filter",
    )

  private def listContracts[T](
      fromCreatedEvent: CreatedEvent => Option[T],
      filter: T => Boolean,
      limit: Option[Long],
  ): Future[Seq[(T, ContractState)]] = Future {
    val st = stateVar
    st.createEvents.values
      .collect(Function.unlift { ev =>
        fromCreatedEvent(ev).map { parsedEv =>
          val state = st
            .getContractState(new ContractId(ev.getContractId))
          (parsedEv, state)
        }
      })
      .take(limit.fold(Int.MaxValue)(_.intValue()))
      .filter { case (ev, _) => filter(ev) }
      .toSeq
  }

  override def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Seq[ContractWithState[TCid, T]]] = {
    requireInScope(companion)
    listContracts(
      fromCreatedEvent = companionClass.fromCreatedEvent(companion)(contractFilter, _),
      filter = filter,
      limit = limit,
    ).map(_.map { case (contract, state) =>
      ContractWithState(contract, state)
    })
  }

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domainId: DomainId,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Seq[Contract[TCid, T]]] =
    listContracts(companion, filter, None).map(
      _.collect {
        case ContractWithState(contract, ContractState.Assigned(domain)) if domain == domainId =>
          contract
      }.take(limit.fold(Int.MaxValue)(_.intValue()))
    )

  def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
      limit: Option[Long] = None,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[ReadyContract[TCid, T]]] =
    for {
      contracts <- listContracts(companion, filter)(companionClass)
    } yield contracts.view
      .collect(Function.unlift(_.toReadyContract))
      .take(limit.fold(Int.MaxValue)(_.intValue()))
      .toSeq

  private def lookupContractById[T](
      fromCreatedEvent: CreatedEvent => Option[T]
  )(id: ContractId[_]): Future[Option[(T, ContractState)]] = Future {
    val st = stateVar
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

  override def lookupContractById[C, TCid <: ContractId[_], T](
      companion: C
  )(id: ContractId[_])(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Option[ContractWithState[TCid, T]]] = {
    requireInScope(companion)
    lookupContractById(companionClass.fromCreatedEvent(companion)(contractFilter, _))(id)
      .map(_.map { case (contract, state) =>
        ContractWithState(contract, state)
      })
  }

  override def findContractWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(
      p: Contract[TCid, T] => Boolean = (_: Any) => true
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]] = Future {
    requireInScope(companion)
    val st = stateVar
    val optEntry = st.createEvents.values.collectFirst(
      Function.unlift(ev =>
        for {
          contract <- companionClass.fromCreatedEvent(companion)(contractFilter, ev)
          if p(contract)
        } yield contract
      )
    )
    QueryResult(
      st.offsets,
      optEntry.map { contract =>
        val state = st.getContractState(contract.contractId)
        ContractWithState(contract, state)
      },
    )
  }

  override def findContractOnDomainWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, p: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[Contract[TCid, T]]]] = Future {
    requireInScope(companion)
    val st = stateVar
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
      st.offsets.filter({ case (k, _) => k == domain }),
      optEntry,
    )
  }

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
    Future {
      val state = stateVar
      state.transferOutEventsById.contains(transfer)
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
      companionClass: ContractCompanion[C, TCid, T]
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    requireInScope(companion)
    streamReadyContracts(companionClass.fromCreatedEvent(companion)(contractFilter, _)).map {
      case (contract, domain) => ReadyContract(contract, domain)
    }
  }

  /** TX log APIs
    */

  override def getTxLogIndicesByOffset(offset: Int, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] =
    Future.successful(stateVar.txLog.slice(offset, offset + limit).map(_.payload))

  override def getTxLogIndicesAfterEventId(
      domainId: DomainId,
      beginAfterEventId: String,
      limit: Int,
  )(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] =
    Future.successful(
      stateVar.txLog.view
        .filter(_.domain == domainId)
        .map(_.payload)
        .dropWhile(_.eventId != beginAfterEventId)
        .slice(1, 1 + limit)
        .toSeq
    )

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

  override def signalWhenIngestedOrShutdown(domainId: DomainId, offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] =
    signalWhenIngestedOrShutdown(domainId, InMemoryMultiDomainAcsStore.OffsetSignal.Offset(offset))

  def signalWhenAcsCompletedOrShutdown(domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Unit] =
    signalWhenIngestedOrShutdown(domainId, InMemoryMultiDomainAcsStore.OffsetSignal.Acs)

  private def signalWhenIngestedOrShutdown(
      domainId: DomainId,
      offset: InMemoryMultiDomainAcsStore.OffsetSignal,
  )(implicit
      tc: TraceContext
  ): Future[Unit] =
    updateState[Future[Unit]](state =>
      if (
        state.offsets
          .get(domainId)
          .exists(actualOffset =>
            InMemoryMultiDomainAcsStore.OffsetSignal.Offset(actualOffset) >= offset
          )
      ) {
        (state, Future.unit)
      } else {
        val (newState, offsetIngested) = state.addOffsetToSignal(domainId, offset)
        val name = s"signalWhenIngested($offset)"
        val ingestedOrShutdown = retryProvider
          .waitUnlessShutdown(offsetIngested)
          .onShutdown(
            logger.debug(s"Aborted $name, as we are shutting down")
          )
        val supervisedFuture = futureSupervisor.supervised(name)(ingestedOrShutdown)
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
  private[store] def bootstrapTransferOutIds: Future[Set[TransferId]] =
    Future.successful(stateVar.bootstrapTransferOutIds)

  override def close(): Unit = ()
}

object InMemoryMultiDomainAcsStore {

  import MultiDomainAcsStore.{ContractState, TransferId}

  private sealed abstract class OffsetSignal extends Ordered[OffsetSignal] {
    override def compare(that: OffsetSignal) =
      (this, that) match {
        case (OffsetSignal.Acs, OffsetSignal.Acs) => 0
        case (OffsetSignal.Acs, OffsetSignal.Offset(_)) => -1
        case (OffsetSignal.Offset(_), OffsetSignal.Acs) => 1
        case (OffsetSignal.Offset(thisO), OffsetSignal.Offset(thatO)) => thisO.compare(thatO)
      }
  }

  private object OffsetSignal {
    final case object Acs extends OffsetSignal
    final case class Offset(offset: String) extends OffsetSignal
  }

  private case class ContractLocation(
      contractId: ContractId[_],
      domain: DomainId,
  )

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
    *   - removed on removeTransferOutIfBootstrap if transfer in bootstrapTransferOutIds
    * - earlyTransferIns
    *   - added on transfer in if it matches our filter and we do not yet have the transfer id in earlyTransferIns
    *   - removed on matching transfer out
    * - pendingTransfersById: index on top of transferOutEvents/earlyTransferIns, always updated together
    * - bootstrapTransferOutIds
    *   - added: transfer outs ingested as part of initial bootstrapping
    *   - removed: transfer in or removeTransferOutIfBootstrap
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
    * Bootstrapping: When bootstrapping from the ACS and in-flight contracts we end up with two
    * edge cases:
    * 1. We see the in-flight transfer out on the source domain
    *    but we start streaming on the target domain after the transfer in.
    *    Our automation will detect the failed attempt to submit the transfer in and remove
    *    the transfer out through `removeTransferOutIfBootstrap`. Note that if automation
    *    calls this before we see the transfer in, this will result in situation 2.
    * 2. We see the transfer in on the target domain but we don't see an in-flight transfer out
    *    when bootstrapping on the source domain (because the participant has
    *    already processed the transfer in). In this case, the transfer in will forever
    *    remain in `earyTransferIns` and therefore if we ever archive it it will also remain in
    *    `archivedTombstones`. This can only occur for contracts that are in-flight at bootstrapping
    *    and we only end up leaking constant-size data rather than the full payloads. Therefore,
    *    we accept this for now.
    */
  private case class State[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      offsets: Map[DomainId, String],
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
      // Transfer ids that we added during bootstrapping. This is used
      // to make sure that we only limit the problematic cases described under
      // "Bootstrapping" to really only the contracts ingested during bootstrapping.
      bootstrapTransferOutIds: Set[TransferId],

      // Contracts that have already been archived but
      // there are still in-flight requests we might receive.
      // We track those so we can make sure to ignore them instead of
      // e.g., readding the contract to the active contracts on a delayed
      // transfer in. We remove entries as soon as
      // there are no entries in transferOutEvents and earlyTransferIns
      // for the contract.
      archivedTombstones: Set[ContractId[_]],
      offsetChanged: Promise[Unit],
      offsetIngestionsToSignal: Map[DomainId, SortedMap[OffsetSignal, Promise[Unit]]],
      txLog: Queue[IndexRecordWithDomain[TXI]],
  ) {

    private def removeOffsetSignalsBefore(
        domainId: DomainId,
        offset: OffsetSignal,
    ): (State[TXI, TXE], Map[OffsetSignal, Promise[Unit]]) = {
      // because we use a SortedMap, we can use takeWhile.
      val offsetsToRemove = offsetIngestionsToSignal
        .getOrElse(domainId, SortedMap.empty[OffsetSignal, Promise[Unit]])
        .takeWhile(_._1 <= offset)
      (
        copy(
          offsetIngestionsToSignal = offsetIngestionsToSignal.updatedWith(domainId)(prev =>
            Some(
              prev
                .getOrElse(SortedMap.empty[OffsetSignal, Promise[Unit]])
                .removedAll(offsetsToRemove.keys)
            )
          )
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

    def ingestAcsAndTransferOuts(
        domain: DomainId,
        evs: Seq[CreatedEvent],
        transferOuts: Seq[InFlightTransferOutEvent],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      assert(!offsets.contains(domain), "state was not switched to update ingestion yet")
      val (stAcs, summaryAcs) = ingestCreatedEvents(domain, evs, p)
      val (stInFlight, summaryTransferOut) = stAcs.ingestTransferOuts(transferOuts, summaryAcs, p)
      (
        stInFlight,
        summaryTransferOut.copy(
          domain = Some(domain),
          newAcsSize = stInFlight.createEvents.size,
        ),
      )
    }

    def switchToIngestingUpdates(
        domain: DomainId,
        offset: String,
    ): (State[TXI, TXE], (Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(!offsets.contains(domain), "state was not switched to update ingestion yet")
      ingestOffset(domain, offset)
    }

    def ingestOffset(
        domain: DomainId,
        offset: String,
    ): (State[TXI, TXE], (Promise[Unit], Iterable[Promise[Unit]])) = {
      val (newSt, offsetsToRemove) = removeOffsetSignalsBefore(domain, OffsetSignal.Offset(offset))
      (
        newSt.copy(offsets = offsets + (domain -> offset), offsetChanged = Promise()),
        (offsetChanged, offsetsToRemove.values),
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
      assert(offsets.contains(domain), "state was switched to update ingestion")

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
        (st, ev, _) => {
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
        (st, ev, _) => {
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
        domain = Some(domain),
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
        stNew.removeOffsetSignalsBefore(domain, OffsetSignal.Offset(newOffset))

      (
        stNewWithoutOffsets.copy(
          offsets = offsets + (domain -> newOffset),
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
        domain: DomainId,
        transfer: Transfer[TransferEvent],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], (IngestionSummary[TXE], Promise[Unit], Iterable[Promise[Unit]])) = {
      assert(offsets.contains(domain), "state was switched to update ingestion")
      val (newSt, summary) = ingestTransferEvent(transfer.event, p)
      val newOffset = transfer.offset.getOffset
      val (newStWithoutOffsets, offsetsToRemove) =
        newSt.removeOffsetSignalsBefore(domain, OffsetSignal.Offset(newOffset))
      (
        newStWithoutOffsets.copy(
          offsets = offsets + (domain -> newOffset),
          offsetChanged = Promise(),
        ),
        (
          summary.copy(
            offset = Some(newOffset),
            domain = Some(domain),
            newAcsSize = newSt.createEvents.size,
          ),
          newSt.offsetChanged,
          offsetsToRemove.values,
        ),
      )
    }

    def removeTransferOutIfBootstrap(
        cid: ContractId[_],
        transferId: TransferId,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      if (bootstrapTransferOutIds.contains(transferId)) {
        transferOutEventsById.get(transferId) match {
          case None =>
            throw new IllegalStateException(
              s"Found $transferId in bootstrapInFlight but not in $transferOutEventsById"
            )
          case Some(id) =>
            (
              copy(
                bootstrapTransferOutIds = bootstrapTransferOutIds - transferId,
                transferOutEvents = transferOutEvents - id,
                transferOutEventsById = transferOutEventsById - transferId,
                pendingTransfersById = pendingTransfersById.updatedWith(cid)(
                  _.flatMap(prev => NonEmpty.from(prev - transferId))
                ),
              ),
              IngestionSummary
                .empty[TXE]
                .copy(removedBootstrapTransferOutIds = Seq((cid, transferId))),
            )
        }
      } else {
        (this, IngestionSummary.empty[TXE])
      }
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

    private def ingestCreatedEvents(
        domain: DomainId,
        evs: Seq[CreatedEvent],
        p: CreatedEvent => Boolean,
    ): (State[TXI, TXE], IngestionSummary[TXE]) = {
      assert(!offsets.contains(domain), "state was not switched to update ingestion yet")
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var numFilteredCreatedEvents = 0
      val ingestedCreatedEvents = Seq.newBuilder[CreatedEvent]
      val addedContractLocations = Seq.newBuilder[ContractLocation]
      val stNew = evs.foldLeft(this)((st, ev) =>
        if (p(ev)) {
          val (stNew, summary) = st.ingestCreatedEvent(
            ev,
            ContractLocation(new ContractId(ev.getContractId), domain),
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
      )
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
        .copy(
          bootstrapTransferOutIds = bootstrapTransferOutIds ++ addedTransferOuts.result().map(_._2)
        )
      (
        stNew,
        summary.copy(
          addedTransferOutEvents = addedTransferOuts.result(),
          addedBootstrapTransferOutIds = addedTransferOuts.result(),
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
                    removedTombstone,
                    removedBootstrapTransferOut,
                  ) =>
                summary.copy(
                  removedArchivedTombstones = if (removedTombstone) Seq(cid) else Seq.empty,
                  removedTransferOutEvents = Seq((cid, tfid)),
                  removedBootstrapTransferOutIds =
                    if (removedBootstrapTransferOut) Seq((cid, tfid))
                    else Seq.empty,
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
              bootstrapTransferOutIds = bootstrapTransferOutIds - transferId,
            ),
            createSummary,
            TransferInEventSummary.RemovedTransferOut(
              removedTombstone = removeTombstone,
              removedBootstrapTransferOut = bootstrapTransferOutIds.contains(transferId),
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
        domainId: DomainId,
        offset: OffsetSignal,
    ): (State[TXI, TXE], Future[Unit]) = {
      val perDomainOffsets =
        offsetIngestionsToSignal.getOrElse(domainId, SortedMap.empty[OffsetSignal, Promise[Unit]])
      perDomainOffsets.get(offset) match {
        case None =>
          val p = Promise[Unit]()
          val newSt: State[TXI, TXE] = copy(
            offsetIngestionsToSignal =
              offsetIngestionsToSignal + (domainId -> (perDomainOffsets + (offset -> p)))
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
    case class RemovedTransferOut(removedTombstone: Boolean, removedBootstrapTransferOut: Boolean)
        extends TransferInEventSummary
  }

  private case class IngestionSummary[TXE <: TxLogStore.Entry[_]](
      txId: Option[String],
      offset: Option[String],
      domain: Option[DomainId],
      newAcsSize: Int,
      ingestedCreatedEvents: Seq[CreatedEvent],
      numFilteredCreatedEvents: Int,
      ingestedArchivedEvents: Seq[ExercisedEvent],
      numFilteredArchivedEvents: Int,
      addedContractLocations: Seq[ContractLocation],
      removedContractLocations: Seq[ContractLocation],
      addedTransferInEvents: Seq[(ContractId[_], TransferId)],
      numFilteredTransferInEvents: Int,
      removedTransferInEvents: Seq[(ContractId[_], TransferId)],
      addedTransferOutEvents: Seq[(ContractId[_], TransferId)],
      numFilteredTransferOutEvents: Int,
      removedTransferOutEvents: Seq[(ContractId[_], TransferId)],
      addedBootstrapTransferOutIds: Seq[(ContractId[_], TransferId)],
      removedBootstrapTransferOutIds: Seq[(ContractId[_], TransferId)],
      addedArchivedTombstones: Seq[ContractId[_]],
      removedArchivedTombstones: Seq[ContractId[_]],
      ingestedTxLogEntries: Seq[TXE],
  ) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances
    import com.daml.network.util.PrettyInstances.*

    @SuppressWarnings(Array("org.wartremover.warts.Product"))
    implicit val txLogPretty: Pretty[TXE] = adHocPrettyInstance

    implicit def prettyContractLocation: Pretty[ContractLocation] =
      prettyNode("ContractLocation", param("cid", _.contractId), param("domain", _.domain))

    implicit def prettyTransferId: Pretty[TransferId] =
      prettyNode(
        "TransferId",
        param("source", _.source),
        param[TransferId, String]("id", _.id)(PrettyInstances.prettyString),
      )

    override def pretty: Pretty[this.type] = {

      def paramIfNonZero[T](name: String, getValue: T => Int) =
        param(name, getValue(_), (x: T) => getValue(x) != 0)

      prettyNode(
        "", // intentionally left empty, as that worked better in the log messages above
        paramIfDefined("txId", _.txId.map(_.unquoted)),
        paramIfDefined("offset", _.offset.map(_.unquoted)),
        paramIfDefined("domain", _.domain),
        param("newAcsSize", _.newAcsSize),
        paramIfNonEmpty("ingestedCreatedEvents", _.ingestedCreatedEvents),
        paramIfNonZero("numFilteredCreatedEvents", _.numFilteredCreatedEvents),
        paramIfNonEmpty("ingestedArchivedEvents", _.ingestedArchivedEvents),
        paramIfNonZero("numFilteredArchivedEvents", _.numFilteredArchivedEvents),
        paramIfNonEmpty("addedContractLocations", _.addedContractLocations),
        paramIfNonEmpty("removedContractLocations", _.removedContractLocations),
        paramIfNonEmpty("addedTransferInEvents", _.addedTransferInEvents),
        paramIfNonZero("numFilteredTransferInEvents", _.numFilteredTransferInEvents),
        paramIfNonEmpty("removedTransferInEvents", _.removedTransferInEvents),
        paramIfNonEmpty("addedTransferOutEvents", _.addedTransferOutEvents),
        paramIfNonZero("numFilteredTransferOutEvents", _.numFilteredTransferOutEvents),
        paramIfNonEmpty("removedTransferOutEvents", _.removedTransferOutEvents),
        paramIfNonEmpty("addedBootstrapTransferOutEvents", _.addedBootstrapTransferOutIds),
        paramIfNonEmpty(
          "removedBootstrapTransferOutEvents",
          _.removedBootstrapTransferOutIds,
        ),
        paramIfNonEmpty("addedArchivedTombstones", _.addedArchivedTombstones),
        paramIfNonEmpty("removedArchivedTombstones", _.removedArchivedTombstones),
        paramIfNonEmpty(
          "ingestedTxLogEntries",
          _.ingestedTxLogEntries,
        ),
      )
    }
  }

  private object IngestionSummary {
    def empty[TXE <: TxLogStore.Entry[_]]: IngestionSummary[TXE] = IngestionSummary(
      txId = None,
      offset = None,
      domain = None,
      newAcsSize = 0,
      ingestedCreatedEvents = Seq.empty,
      numFilteredCreatedEvents = 0,
      ingestedArchivedEvents = Seq.empty,
      numFilteredArchivedEvents = 0,
      addedContractLocations = Seq.empty,
      removedContractLocations = Seq.empty,
      addedTransferInEvents = Seq.empty,
      numFilteredTransferInEvents = 0,
      removedTransferInEvents = Seq.empty,
      addedTransferOutEvents = Seq.empty,
      numFilteredTransferOutEvents = 0,
      removedTransferOutEvents = Seq.empty,
      addedBootstrapTransferOutIds = Seq.empty,
      removedBootstrapTransferOutIds = Seq.empty,
      addedArchivedTombstones = Seq.empty,
      removedArchivedTombstones = Seq.empty,
      ingestedTxLogEntries = Seq.empty,
    )
  }
}
