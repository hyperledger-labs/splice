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
  ): Source[Transfer[TransferEvent.Out], NotUsed] =
    Source
      .unfoldAsync(0: Long)(eventNumber => nextReadyForTransferIn(eventNumber).map(Some(_)))
      .filter(ev => ev.event.target == domainId)

  private def nextReadyForTransferIn(
      startingFromIncl: Long
  ): Future[(Long, Transfer[TransferEvent.Out])] = {
    val st = stateVar
    val optEntry = st.inFlightTransfers.minAfter(startingFromIncl)
    optEntry match {
      case None =>
        st.offsetChanged.future.flatMap(_ => nextReadyForTransferIn(st.nextTransferOutEventNumber))
      case Some((eventNumber, out)) => Future((eventNumber + 1, out))
    }
  }

  override def isReadyForTransferIn(transfer: Transfer[TransferEvent.Out]): Future[Boolean] =
    Future {
      val state = stateVar
      state.inFlightTransfersByTransferId.contains(TransferId.fromTransferOut(transfer))
    }

  override val ingestionSink: TransferStore.IngestionSink = new TransferStore.IngestionSink {
    override def ingestTransferIn(
        transfer: Transfer[TransferEvent.In]
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestTransferIn(transfer)
      ).map { case (summary, offsetChanged) =>
        logger.debug(show"Ingested transfer in: $summary")
        offsetChanged.success(())
      }

    override def ingestTransferOut(
        transfer: Transfer[TransferEvent.Out]
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.ingestTransferOut(transfer)
      ).map { case (summary, offsetChanged) =>
        logger.debug(show"Ingested transfer out: $summary")
        offsetChanged.success(())
      }

    override val ingestionFilter = party
  }

  override def close(): Unit = ()
}

object InMemoryTransferStore {
  private case class TransferId(source: DomainId, id: String)

  private object TransferId {
    def fromTransferIn(in: Transfer[TransferEvent.In]) =
      TransferId(in.event.source, in.event.transferOutId)
    def fromTransferOut(out: Transfer[TransferEvent.Out]) =
      TransferId(out.event.source, out.event.transferOutId)
  }

  private case class State(
      offsets: Map[DomainId, String],
      offsetChanged: Promise[Unit],
      nextTransferOutEventNumber: Long,
      inFlightTransfers: SortedMap[Long, Transfer[TransferEvent.Out]],
      inFlightTransfersByTransferId: Map[TransferId, Long],
      // Transfer in events for which we have not yet observed the transfer out.
      earlyTransferIns: Map[TransferId, Transfer[TransferEvent.In]],
  ) {
    def ingestTransferIn(
        transfer: Transfer[TransferEvent.In]
    ): (State, (IngestionSummary, Promise[Unit])) = {
      val newOffsets = offsets + (transfer.event.target -> transfer.offset.getOffset)
      val tid = TransferId.fromTransferIn(transfer)
      val (newState, addedEarlyTransferIn, removedInFlightTransfer) =
        inFlightTransfersByTransferId.get(tid) match {
          case Some(eventNumber) =>
            (
              State(
                newOffsets,
                Promise(),
                nextTransferOutEventNumber,
                inFlightTransfers = inFlightTransfers - eventNumber,
                inFlightTransfersByTransferId = inFlightTransfersByTransferId - tid,
                earlyTransferIns,
              ),
              None,
              inFlightTransfers.get(eventNumber),
            )
          case None =>
            // We haven't yet seen the transfer out so store it as an early transfer in
            // and remove it once we also see the transfer out.
            (
              State(
                newOffsets,
                Promise(),
                nextTransferOutEventNumber,
                inFlightTransfers,
                inFlightTransfersByTransferId,
                earlyTransferIns = earlyTransferIns + (tid -> transfer),
              ),
              Some(transfer),
              None,
            )
        }
      val summary = IngestionSummary(
        addedInFlightTransfer = None,
        removedInFlightTransfer = removedInFlightTransfer,
        addedEarlyTransferIn = addedEarlyTransferIn,
        removedEarlyTransferIn = None,
        newNumInFlightTransfers = newState.inFlightTransfers.size,
        newNumEarlyTransferIns = newState.earlyTransferIns.size,
      )
      (newState, (summary, offsetChanged))
    }
    def ingestTransferOut(
        transfer: Transfer[TransferEvent.Out]
    ): (State, (IngestionSummary, Promise[Unit])) = {
      val newOffsets = offsets + (transfer.event.source -> transfer.offset.getOffset)
      val tid = TransferId.fromTransferOut(transfer)
      val (newState, addedInFlightTransfer, removedEarlyTransferIn) =
        earlyTransferIns.get(tid) match {
          case Some(_) =>
            // We saw the transfer in before the transfer out, remove the transfer in and ignore the tranfer out.
            (
              State(
                newOffsets,
                Promise(),
                nextTransferOutEventNumber,
                inFlightTransfers = inFlightTransfers,
                inFlightTransfersByTransferId = inFlightTransfersByTransferId,
                earlyTransferIns = earlyTransferIns - tid,
              ),
              None,
              earlyTransferIns.get(tid),
            )
          case None =>
            (
              State(
                newOffsets,
                Promise(),
                nextTransferOutEventNumber = nextTransferOutEventNumber + 1,
                inFlightTransfers = inFlightTransfers + (nextTransferOutEventNumber -> transfer),
                inFlightTransfersByTransferId =
                  inFlightTransfersByTransferId + (tid -> nextTransferOutEventNumber),
                earlyTransferIns = earlyTransferIns,
              ),
              Some(transfer),
              None,
            )
        }
      val summary = IngestionSummary(
        addedInFlightTransfer = addedInFlightTransfer,
        removedInFlightTransfer = None,
        addedEarlyTransferIn = None,
        removedEarlyTransferIn = removedEarlyTransferIn,
        newNumInFlightTransfers = newState.inFlightTransfers.size,
        newNumEarlyTransferIns = newState.earlyTransferIns.size,
      )
      (newState, (summary, offsetChanged))
    }
  }

  private case class IngestionSummary(
      addedInFlightTransfer: Option[Transfer[TransferEvent.Out]],
      removedInFlightTransfer: Option[Transfer[TransferEvent.Out]],
      addedEarlyTransferIn: Option[Transfer[TransferEvent.In]],
      removedEarlyTransferIn: Option[Transfer[TransferEvent.In]],
      newNumInFlightTransfers: Int,
      newNumEarlyTransferIns: Int,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyNode(
        "", // intentionally left empty, as that worked better in the log messagesabove
        paramIfDefined("addedInFlightTransfer", _.addedInFlightTransfer),
        paramIfDefined("removedInFlightTransfer", _.removedInFlightTransfer),
        paramIfDefined("addedEarlyTransferIn", _.addedEarlyTransferIn),
        paramIfDefined("removedEarlyTransferIn", _.removedEarlyTransferIn),
        param("newNumInFlightTransfers", _.newNumInFlightTransfers),
        param("newNumEarlyTransferIns", _.newNumEarlyTransferIns),
      )
    }
  }
}
