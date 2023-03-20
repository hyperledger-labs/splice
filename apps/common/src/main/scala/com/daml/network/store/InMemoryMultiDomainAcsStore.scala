package com.daml.network.store

import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{
  TransactionTreeUpdate,
  Transfer,
  TransferEvent,
  TransferUpdate,
  TreeUpdate,
}
import com.daml.ledger.javaapi.data.codegen.{ContractCompanion, ContractId}
import com.daml.ledger.javaapi.data.{CreatedEvent, Template, TransactionTree}
import com.daml.network.util.{Contract, Trees}
import Contract.Companion.Template as TemplateCompanion
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.collection.immutable
import scala.concurrent.*

class InMemoryMultiDomainAcsStore(
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: AcsStore.ContractFilter,
)(implicit
    ec: ExecutionContext
) extends MultiDomainAcsStore
    with NamedLogging {

  import InMemoryMultiDomainAcsStore.TransferId
  import MultiDomainAcsStore.{ContractState, ContractWithState}

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryMultiDomainAcsStore.State =
    InMemoryMultiDomainAcsStore.State(
      offsets = Map.empty,
      nextCreateEventNumber = 0,
      createEvents = immutable.SortedMap.empty,
      createEventsById = Map.empty,
      nextContractLocationEventNumber = 0,
      contractLocations = immutable.SortedMap.empty,
      contractLocationsById = Map.empty,
      nextTransferOutEventNumber = 0,
      transferOutEvents = immutable.SortedMap.empty,
      transferOutEventsById = Map.empty,
      earlyTransferIns = Set.empty,
      pendingTransfersById = Map.empty,
      bootstrapTransferOutIds = Set.empty,
      archivedTombstones = Set.empty,
      Promise(),
    )

  private def updateState[T](
      f: InMemoryMultiDomainAcsStore.State => (InMemoryMultiDomainAcsStore.State, T)
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
        transferOuts: Seq[TransferEvent.Out],
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestAcsAndTransferOuts(domain, acs, transferOuts, contractFilter.contains)
      ).map { _ =>
        logger.debug(
          show"Ingested ACS and in-flight update for domain $domain"
        )
      }

    override def switchToIngestingUpdates(
        domain: DomainId,
        offset: String,
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.switchToIngestingUpdates(domain, offset)
      ).map {
        case offsetChanged => {
          logger.debug(
            show"Ingested complete ACS and in-flight transfers for domain $domain at offset ${offset}"
          )
          offsetChanged.success(())
        }
      }

    override def ingestUpdate(
        domain: DomainId,
        update: TreeUpdate,
    )(implicit traceContext: TraceContext): Future[Unit] =
      update match {
        case TransactionTreeUpdate(tree) =>
          updateState(
            _.ingestTransaction(domain, tree, contractFilter.contains)
          ).map { case offsetChanged =>
            offsetChanged.success(())
          }
        case TransferUpdate(transfer) =>
          updateState(
            _.ingestTransfer(domain, transfer, contractFilter.contains)
          ).map { case offsetChanged =>
            offsetChanged.success(())
          }
      }

    override def removeTransferOutIfBootstrap(
        transfer: TransferEvent.Out
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.removeTransferOutIfBootstrap(transfer)
      ).map { removed =>
        logger.debug(show"Result for remove attempt of $transfer: $removed")
      }
  }

  private def requireInScope[TC, TCid, T](templateCompanion: ContractCompanion[TC, TCid, T]): Unit =
    require(
      contractFilter.mightContain(templateCompanion),
      s"template ${templateCompanion.TEMPLATE_ID} is part of the contract filter",
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
            .getOrElse(
              throw new IllegalStateException(
                s"Failed to find contract state for ${ev.getContractId}"
              )
            )
          (parsedEv, state)
        }
      })
      .take(limit.fold(Int.MaxValue)(_.intValue()))
      .filter { case (ev, _) => filter(ev) }
      .toSeq
  }

  def listContracts[TCid <: ContractId[T], T <: Template](
      templateCompanion: TemplateCompanion[TCid, T],
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  ): Future[Seq[ContractWithState[TCid, T]]] = {
    requireInScope(templateCompanion)
    listContracts(Contract.fromCreatedEvent(templateCompanion), filter, limit).map(_.map {
      case (contract, state) => ContractWithState(contract, state)
    })
  }

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
          .getOrElse(
            throw new IllegalStateException(
              s"Failed to find contract state for ${ev.getContractId}"
            )
          )
        (parsedEv, state)
      }
    }
  }

  def lookupContractById[TCid <: ContractId[T], T <: Template](
      templateCompanion: TemplateCompanion[TCid, T]
  )(id: ContractId[T]): Future[Option[ContractWithState[TCid, T]]] = {
    requireInScope(templateCompanion)
    lookupContractById(Contract.fromCreatedEvent(templateCompanion))(id).map(_.map {
      case (contract, state) => ContractWithState(contract, state)
    })
  }

  /** Testing APIs
    */

  private[store] def contractLocationsById
      : Future[Map[ContractId[_], NonEmpty[Map[DomainId, Long]]]] =
    Future.successful(stateVar.contractLocationsById)
  private[store] def archivedTombstones: Future[Set[ContractId[_]]] =
    Future.successful(stateVar.archivedTombstones)
  private[store] def pendingTransfersById: Future[Map[ContractId[_], NonEmpty[Set[TransferId]]]] =
    Future.successful(stateVar.pendingTransfersById)
  private[store] def bootstrapTransferOutIds: Future[Set[TransferId]] =
    Future.successful(stateVar.bootstrapTransferOutIds)

  override def close(): Unit = ()
}

object InMemoryMultiDomainAcsStore {

  import MultiDomainAcsStore.ContractState

  case class TransferId(source: DomainId, id: String)

  object TransferId {
    def fromTransferIn(in: TransferEvent.In) =
      TransferId(in.source, in.transferOutId)
    def fromTransferOut(out: TransferEvent.Out) =
      TransferId(out.source, out.transferOutId)
  }

  private case class ContractLocation(
      contractId: ContractId[_],
      domain: DomainId,
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
    * - contractLocations
    *   - added on each create/transfer in
    *   - removed on the corresponding transfer out/archive
    *   - on archive, all locations are removed.
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
  private case class State(
      offsets: Map[DomainId, String],
      nextCreateEventNumber: Long,
      // All active contracts across all domains that match our contract filter. Contracts are inserted
      // when we see the first create or transfer in and removed on archive.
      // Event number stays stable across transfers.
      createEvents: immutable.SortedMap[Long, CreatedEvent],
      createEventsById: immutable.Map[ContractId[_], Long],

      // contract-locations
      nextContractLocationEventNumber: Long,
      // Domains that we have seen the contract move into (via a transfer in/create) but have not yet
      // seen a transfer out/archive.
      // There can be multiple entries if we see transfer ins before transfer outs.
      // At the moment, we treat the contract as in-flight in that case until domains have
      // caught up far enough that we're back to a single one.
      // There can also be no entry for a given contract if we have seen a transfer out
      // but not yet the transfer in.
      contractLocations: immutable.SortedMap[Long, ContractLocation],
      contractLocationsById: immutable.Map[ContractId[_], NonEmpty[Map[DomainId, Long]]],

      // in-flights
      nextTransferOutEventNumber: Long,
      // Transfer out for which we have not yet seen the transfer in.
      transferOutEvents: immutable.SortedMap[Long, TransferEvent.Out],
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
  ) {

    def getContractState(cid: ContractId[_]): Option[ContractState] =
      createEventsById.get(cid).map { _ =>
        val domains = contractLocationsById.get(cid).fold(Set.empty[DomainId])(_.keySet)
        domains.headOption match {
          case Some(domain) if domains.size == 1 =>
            // If we only have one domain the contract has moved in to and not yet moved out off
            // we assume that's the contract's current location. Note that there still may be
            // in-flight transfers at this point.
            ContractState.Assigned(domain)
          case _ =>
            // If we have more than one or no domain but the contract is active
            // we must be tracking at least one in-flight transfer
            assert(!pendingTransfersById.get(cid).isEmpty)
            ContractState.InFlight
        }
      }

    def ingestAcsAndTransferOuts(
        domain: DomainId,
        evs: Seq[CreatedEvent],
        transferOuts: Seq[TransferEvent.Out],
        p: CreatedEvent => Boolean,
    ): (State, Unit) = {
      assert(!offsets.contains(domain), "state was not switched to update ingestion yet")
      val stAcs = ingestCreatedEvents(domain, evs, p)
      val stInFlight = stAcs.ingestTransferOuts(transferOuts)
      (stInFlight, ())
    }

    def switchToIngestingUpdates(
        domain: DomainId,
        offset: String,
    ): (State, Promise[Unit]) = {
      assert(!offsets.contains(domain), "state was not switched to update ingestion yet")
      (
        copy(
          offsets = offsets + (domain -> offset),
          offsetChanged = Promise(),
        ),
        offsetChanged,
      )
    }

    /** Ingest a transaction while filtering out create events that do not satisfy the given predicate. */
    def ingestTransaction(
        domain: DomainId,
        tx: TransactionTree,
        p: CreatedEvent => Boolean,
    ): (State, Promise[Unit]) = {
      assert(offsets.contains(domain), "state was switched to update ingestion")

      val stNew = Trees.foldTree(tx, this)(
        (st, ev, _) => {
          if (p(ev)) {
            st.ingestCreatedEvent(
              ev,
              ContractLocation(new ContractId(ev.getContractId), domain),
            )
          } else {
            st
          }
        },
        (st, ev, _) => {
          if (ev.isConsuming) {
            val (newSt, _) = st.ingestArchivedEvent(new ContractId(ev.getContractId))
            newSt
          } else {
            st
          }
        },
      )

      val newOffset = tx.getOffset
      (
        stNew.copy(
          offsets = offsets + (domain -> newOffset),
          offsetChanged = Promise(),
        ),
        offsetChanged,
      )
    }

    def ingestTransfer(
        domain: DomainId,
        transfer: Transfer[TransferEvent],
        p: CreatedEvent => Boolean,
    ): (State, Promise[Unit]) = {
      assert(offsets.contains(domain), "state was switched to update ingestion")
      val newSt = ingestTransferEvent(transfer.event, p)
      (
        newSt.copy(
          offsets = offsets + (domain -> transfer.offset.getOffset),
          offsetChanged = Promise(),
        ),
        newSt.offsetChanged,
      )
    }

    def removeTransferOutIfBootstrap(transfer: TransferEvent.Out): (State, Boolean) = {
      val transferId = TransferId.fromTransferOut(transfer)
      val cid = transfer.contractId
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
              true,
            )
        }
      } else {
        (this, false)
      }
    }

    private def ingestCreatedEvents(
        domain: DomainId,
        evs: Seq[CreatedEvent],
        p: CreatedEvent => Boolean,
    ): State = {
      assert(!offsets.contains(domain), "state was not switched to update ingestion yet")
      evs.foldLeft(this)((st, ev) =>
        if (p(ev)) {
          st.ingestCreatedEvent(
            ev,
            ContractLocation(new ContractId(ev.getContractId), domain),
          )
        } else {
          st
        }
      )
    }

    // Used during bootstrapping to ingest in-flight transfer outs
    private def ingestTransferOuts(evs: Seq[TransferEvent.Out]): State = {
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var addedTransferIds = Set.empty[TransferId]
      evs
        .foldLeft(this)((st, ev) => {
          val (newSt, added) = st.ingestTransferOutEvent(ev, bootstrap = true)
          if (added) {
            addedTransferIds += TransferId.fromTransferOut(ev)
          }
          newSt
        })
        .copy(
          bootstrapTransferOutIds = bootstrapTransferOutIds ++ addedTransferIds
        )
    }

    private def ingestCreatedEvent(ev: CreatedEvent, location: ContractLocation): State = {
      val cid = new ContractId(ev.getContractId)
      if (archivedTombstones.contains(cid)) {
        this
      } else {
        val newSt =
          copy(
            nextContractLocationEventNumber = nextContractLocationEventNumber + 1,
            contractLocations = contractLocations + (nextContractLocationEventNumber -> location),
            contractLocationsById = contractLocationsById.updatedWith(cid) { prev =>
              val newEntry = location.domain -> nextContractLocationEventNumber
              Some(prev.fold(NonEmpty(Map, newEntry))(_.updated(newEntry._1, newEntry._2)))
            },
          )

        createEventsById.get(cid) match {
          case None =>
            newSt.copy(
              nextCreateEventNumber = nextCreateEventNumber + 1,
              createEvents = createEvents + (nextCreateEventNumber -> ev),
              createEventsById = createEventsById + (cid -> nextCreateEventNumber),
            )
          case Some(_) =>
            // We already saw the contract before in an earlier transfer in or create
            newSt
        }
      }
    }

    private def ingestArchivedEvent(contractId: ContractId[_]): (State, Boolean) = {
      createEventsById.get(contractId) match {
        case None =>
          // This can only happen if we filtered out the create event. We just ignore it in this case.
          (this, false)
        case Some(createId) =>
          // When we see an archive, we drop all state for that contract immediately.
          // If we might still see events later, we add a tombstone marker that is only used to
          // ignore all events and will be removed once we know that there will be no further events.
          val locations = contractLocationsById.get(contractId).fold(Iterable.empty[Long])(_.values)
          (
            copy(
              createEvents = createEvents - createId,
              createEventsById = createEventsById - contractId,
              contractLocations = contractLocations -- locations,
              contractLocationsById = contractLocationsById - contractId,
              archivedTombstones =
                if (pendingTransfersById.contains(contractId)) (archivedTombstones + contractId)
                else archivedTombstones,
            ),
            true,
          )
      }
    }

    private def ingestTransferEvent(
        event: TransferEvent,
        p: CreatedEvent => Boolean,
    ): State =
      event match {
        case out: TransferEvent.Out =>
          ingestTransferOutEvent(out)._1
        case in: TransferEvent.In =>
          if (p(in.createdEvent)) {
            ingestTransferInEvent(in)
          } else {
            this
          }
      }

    private def ingestTransferInEvent(in: TransferEvent.In): State = {
      val transferId = TransferId.fromTransferIn(in)
      val cid = new ContractId(in.createdEvent.getContractId)
      // First update create events and contract locations by only looking at the create event.
      val createSt = ingestCreatedEvent(
        in.createdEvent,
        ContractLocation(
          new ContractId(in.createdEvent.getContractId),
          in.target,
        ),
      )
      transferOutEventsById.get(transferId) match {
        case None =>
          // early transfer in
          createSt.copy(
            earlyTransferIns = earlyTransferIns + transferId,
            pendingTransfersById = pendingTransfersById.updatedWith(cid) { prev =>
              Some(prev.fold(NonEmpty(Set, transferId))(_.incl(transferId)))
            },
          )
        case Some(eventId) =>
          val newPendingTransfersById = pendingTransfersById.updatedWith(cid)(
            _.flatMap(prev => NonEmpty.from(prev - transferId))
          )
          createSt.copy(
            transferOutEvents = transferOutEvents - eventId,
            transferOutEventsById = transferOutEventsById - transferId,
            pendingTransfersById = newPendingTransfersById,
            archivedTombstones =
              if (newPendingTransfersById.contains(cid)) archivedTombstones
              else archivedTombstones - cid,
            bootstrapTransferOutIds = bootstrapTransferOutIds - transferId,
          )
      }
    }

    private def ingestTransferOutEvent(
        out: TransferEvent.Out,
        bootstrap: Boolean = false,
    ): (State, Boolean) = {
      val transferId = TransferId.fromTransferOut(out)
      val cid = out.contractId
      val (newSt, addedToInFlight) = if (earlyTransferIns.contains(transferId)) {
        val newPendingTransfersById =
          pendingTransfersById.updatedWith(cid)(_.flatMap(prev => NonEmpty.from(prev - transferId)))
        (
          copy(
            earlyTransferIns = earlyTransferIns - transferId,
            pendingTransfersById = newPendingTransfersById,
            archivedTombstones =
              if (newPendingTransfersById.contains(cid)) archivedTombstones
              else archivedTombstones - cid,
          ),
          false,
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
          true,
        )
      }

      if (archivedTombstones.contains(out.contractId)) {
        // We've already seen the archive, all we care about is updating pending
        // transfers.
        if (newSt.pendingTransfersById.contains(out.contractId)) {
          (newSt, addedToInFlight)
        } else {
          // We've received the last pending transfer. We won't see any more events for this
          // contract so we can remove the tombstone.
          (
            newSt.copy(
              archivedTombstones = archivedTombstones - out.contractId
            ),
            addedToInFlight,
          )
        }
      } else {
        contractLocationsById.get(out.contractId).flatMap(_.get(out.source)) match {
          case None =>
            if (bootstrap) {
              // If we hit this during bootstrapping, we need to store the contract
              // to guarantee we will submit the transfer in. We accept
              // potentially leaking the in-flight contract for now.
              (newSt, addedToInFlight)
            } else {
              // If the transfer is not from bootstrapping, then we can only hit this
              // because we filtered out the create/transfer in.
              // We deliberately use `this` instead of `newSt` here. We don't want to update
              // pending transfers for ignored contracts.
              (this, false)
            }
          case Some(id) =>
            (
              newSt.copy(
                contractLocations = contractLocations - id,
                contractLocationsById = contractLocationsById.updatedWith(out.contractId)(
                  _.flatMap(p => NonEmpty.from(p - out.source))
                ),
              ),
              addedToInFlight,
            )
        }
      }
    }
  }
}
