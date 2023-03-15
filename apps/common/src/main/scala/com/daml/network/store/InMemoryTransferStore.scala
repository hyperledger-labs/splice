package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{Transfer, TransferEvent}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, blocking, Future, Promise}
import scala.collection.immutable.SortedMap

/** There are two edge cases where the data stored in the store does not quite correspond to what one may expect
  * purely based on the offsets:
  * 1. The initial set of in-flight transfer outs can include a transfer out but we might start streaming on the target domain past the offset of the transfer in
  *    so we never see the corresponding transfer in. We handle that by catching the error when submitting the transfer in and removing it from the store.
  * 2. The initial set of in-flight transfer outs can omit a transfer out if the participant has already seen the transfer in.
  *    However, we might start streaming on the target domain before the offset of the transfer in so we see a transfer in but never the transfer out.
  *    At the moment, this will forever remain in the store which incurrs additional storage costs but is harmless since it does not affect the result of `streamReadyForTransferIn`.
  * TODO(#3463) Evict transfer in events in case 2.
  */
class InMemoryTransferStore(
    override protected val loggerFactory: NamedLoggerFactory,
    party: PartyId,
)(implicit ec: ExecutionContext)
    extends TransferStore
    with NamedLogging {
  import InMemoryTransferStore.{TransferId, State}

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: State =
    State(
      Map.empty,
      Promise(),
      0,
      SortedMap.empty,
      Map.empty,
      Map.empty,
    )

  private def updateState[T](
      f: State => (State, T)
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

  override def streamReadyForTransferIn(
      domainId: DomainId
  ): Source[TransferEvent.Out, NotUsed] =
    Source
      .unfoldAsync(0: Long)(eventNumber => nextReadyForTransferIn(eventNumber).map(Some(_)))
      .filter(ev => ev.target == domainId)

  private def nextReadyForTransferIn(
      startingFromIncl: Long
  ): Future[(Long, TransferEvent.Out)] = {
    val st = stateVar
    val optEntry = st.inFlightTransfers.minAfter(startingFromIncl)
    optEntry match {
      case None =>
        st.offsetChanged.future.flatMap(_ => nextReadyForTransferIn(st.nextTransferOutEventNumber))
      case Some((eventNumber, out)) => Future((eventNumber + 1, out))
    }
  }

  override def isReadyForTransferIn(transfer: TransferEvent.Out): Future[Boolean] =
    Future {
      val state = stateVar
      state.inFlightTransfersByTransferId.contains(TransferId.fromTransferOut(transfer))
    }

  override val ingestionSink: TransferStore.IngestionSink = new TransferStore.IngestionSink {
    override def ingestTransfer(
        transfer: Transfer[TransferEvent]
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestTransfer(transfer)
      ).map { case (summary, offsetChanged) =>
        logger.debug(show"Ingested transfer: $summary")
        offsetChanged.success(())
      }

    override def getLastIngestedOffset(domain: DomainId): Future[Option[String]] =
      Future.successful(stateVar.offsets.get(domain))

    override def ingestInFlightTransfers(domainId: DomainId, transferOuts: Seq[TransferEvent.Out])(
        implicit traceContext: TraceContext
    ): Future[Unit] =
      updateState(
        _.ingestInFlightTransfers(domainId, transferOuts)
      ).map(summary =>
        logger.debug(show"Ingested in-flight transfers from domain $domainId: $summary")
      )

    override def removeCompletedTransferOut(transferOut: TransferEvent.Out)(implicit
        traceContext: TraceContext
    ): Future[Unit] =
      updateState(
        _.removeCompletedTransferOut(transferOut)
      ).map(summary =>
        logger.debug(
          show"Removed completed transfer out after failure to submit transfer in to ${transferOut.target}: $summary"
        )
      )

    override def switchToIngestingUpdates(domainId: DomainId, offset: String)(implicit
        traceContext: TraceContext
    ): Future[Unit] = {
      updateState(
        _.switchToIngestingUpdates(domainId, offset)
      ).map { offsetChanged =>
        logger.debug(
          show"Finished ingesteing in-flight transfers from domain $domainId at offset ${offset.singleQuoted}"
        )
        offsetChanged.success(())
      }
    }

    override val ingestionFilter = party
  }

  override def close(): Unit = ()
}

object InMemoryTransferStore {
  private case class TransferId(source: DomainId, id: String)

  private object TransferId {
    def fromTransferIn(in: TransferEvent.In) =
      TransferId(in.source, in.transferOutId)
    def fromTransferOut(out: TransferEvent.Out) =
      TransferId(out.source, out.transferOutId)
  }

  private case class State(
      offsets: Map[DomainId, String],
      offsetChanged: Promise[Unit],
      nextTransferOutEventNumber: Long,
      inFlightTransfers: SortedMap[Long, TransferEvent.Out],
      inFlightTransfersByTransferId: Map[TransferId, Long],
      // Transfer in events for which we have not yet observed the transfer out.
      earlyTransferIns: Map[TransferId, TransferEvent.In],
  ) {
    def ingestTransfer(transfer: Transfer[TransferEvent]) = {
      val ((newState, ingestionChange), observedOn) = transfer.event match {
        case in: TransferEvent.In =>
          (ingestTransferIn(in), in.target)
        case out: TransferEvent.Out =>
          (ingestTransferOut(out), out.source)
      }
      val summary = IngestionSummary(
        change = ingestionChange,
        newNumInFlightTransfers = newState.inFlightTransfers.size,
        newNumEarlyTransferIns = newState.earlyTransferIns.size,
        observedOn,
        Some(transfer.offset.getOffset),
      )
      (
        newState.copy(
          offsets = offsets + (observedOn -> transfer.offset.getOffset),
          offsetChanged = Promise(),
        ),
        (summary, offsetChanged),
      )
    }

    def ingestInFlightTransfers(
        observedOn: DomainId,
        transferOuts: Seq[TransferEvent.Out],
    ): (State, IngestionSummary) = {
      val (newState, change) = transferOuts.foldLeft((this, IngestionChange.empty)) {
        case ((st, change), ev) => {
          val (newState, newChange) = st.ingestTransferOut(ev)
          (newState, change.merge(newChange))
        }
      }
      (
        newState,
        IngestionSummary(
          change,
          newNumInFlightTransfers = newState.inFlightTransfers.size,
          newNumEarlyTransferIns = newState.earlyTransferIns.size,
          observedOn,
          None,
        ),
      )
    }

    def removeCompletedTransferOut(transferOut: TransferEvent.Out): (State, IngestionSummary) = {
      val tfId = TransferId.fromTransferOut(transferOut)
      val (newState, change) =
        inFlightTransfersByTransferId.get(tfId) match {
          case None => (this, IngestionChange.empty)
          case Some(id) =>
            (
              this.copy(
                inFlightTransfers = this.inFlightTransfers - id,
                inFlightTransfersByTransferId = this.inFlightTransfersByTransferId - tfId,
              ),
              IngestionChange.empty.copy(
                removedInFlightTransfers = Seq(transferOut)
              ),
            )

        }
      (
        newState,
        IngestionSummary(
          change,
          newNumInFlightTransfers = newState.inFlightTransfers.size,
          newNumEarlyTransferIns = newState.earlyTransferIns.size,
          transferOut.target,
          None,
        ),
      )
    }

    def switchToIngestingUpdates(domain: DomainId, offset: String): (State, Promise[Unit]) = {
      assert(
        offsets.get(domain).isEmpty,
        show"state was not switched to update ingestion yet for domain $domain",
      )
      (
        State(
          offsets = offsets + (domain -> offset),
          offsetChanged = Promise(),
          nextTransferOutEventNumber = nextTransferOutEventNumber,
          inFlightTransfers = inFlightTransfers,
          inFlightTransfersByTransferId = inFlightTransfersByTransferId,
          earlyTransferIns = earlyTransferIns,
        ),
        offsetChanged,
      )
    }

    private def ingestTransferIn(
        transfer: TransferEvent.In
    ): (State, IngestionChange) = {
      val tid = TransferId.fromTransferIn(transfer)
      inFlightTransfersByTransferId.get(tid) match {
        case Some(eventNumber) =>
          (
            copy(
              inFlightTransfers = inFlightTransfers - eventNumber,
              inFlightTransfersByTransferId = inFlightTransfersByTransferId - tid,
            ),
            IngestionChange.empty.copy(
              removedInFlightTransfers = inFlightTransfers.get(eventNumber).toList
            ),
          )
        case None =>
          // We haven't yet seen the transfer out so store it as an early transfer in
          // and remove it once we also see the transfer out.
          (
            copy(
              earlyTransferIns = earlyTransferIns + (tid -> transfer)
            ),
            IngestionChange.empty.copy(
              addedEarlyTransferIns = Seq(transfer)
            ),
          )
      }
    }

    private def ingestTransferOut(
        transfer: TransferEvent.Out
    ): (State, IngestionChange) = {
      val tid = TransferId.fromTransferOut(transfer)
      earlyTransferIns.get(tid) match {
        case Some(earlyTransferIn) =>
          // We saw the transfer in before the transfer out, remove the transfer in and ignore the tranfer out.
          (
            copy(
              earlyTransferIns = earlyTransferIns - tid
            ),
            IngestionChange.empty.copy(
              removedEarlyTransferIns = Seq(earlyTransferIn)
            ),
          )
        case None =>
          (
            copy(
              nextTransferOutEventNumber = nextTransferOutEventNumber + 1,
              inFlightTransfers = inFlightTransfers + (nextTransferOutEventNumber -> transfer),
              inFlightTransfersByTransferId =
                inFlightTransfersByTransferId + (tid -> nextTransferOutEventNumber),
            ),
            IngestionChange.empty.copy(
              addedInFlightTransfers = Seq(transfer)
            ),
          )
      }
    }
  }

  private case class IngestionChange(
      addedInFlightTransfers: Seq[TransferEvent.Out],
      removedInFlightTransfers: Seq[TransferEvent.Out],
      addedEarlyTransferIns: Seq[TransferEvent.In],
      removedEarlyTransferIns: Seq[TransferEvent.In],
  ) {
    def merge(other: IngestionChange): IngestionChange =
      IngestionChange(
        addedInFlightTransfers = addedInFlightTransfers ++ other.addedInFlightTransfers,
        removedInFlightTransfers = removedInFlightTransfers ++ other.removedInFlightTransfers,
        addedEarlyTransferIns = addedEarlyTransferIns ++ other.addedEarlyTransferIns,
        removedEarlyTransferIns = removedEarlyTransferIns ++ other.removedEarlyTransferIns,
      )
  }

  private object IngestionChange {
    val empty: IngestionChange = IngestionChange(
      Seq.empty,
      Seq.empty,
      Seq.empty,
      Seq.empty,
    )
  }

  private case class IngestionSummary(
      change: IngestionChange,
      newNumInFlightTransfers: Int,
      newNumEarlyTransferIns: Int,
      domain: DomainId,
      offset: Option[String],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyNode(
        "", // intentionally left empty, as that worked better in the log messages above
        paramIfNonEmpty("addedInFlightTransfers", _.change.addedInFlightTransfers),
        paramIfNonEmpty("removedInFlightTransfers", _.change.removedInFlightTransfers),
        paramIfNonEmpty("addedEarlyTransferIns", _.change.addedEarlyTransferIns),
        paramIfNonEmpty("removedEarlyTransferIns", _.change.removedEarlyTransferIns),
        param("newNumInFlightTransfers", _.newNumInFlightTransfers),
        param("newNumEarlyTransferIns", _.newNumEarlyTransferIns),
        param("domain", _.domain),
        param("offset", _.offset.map(_.unquoted)),
      )
    }
  }
}
