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
              removedInFlightTransfer = inFlightTransfers.get(eventNumber)
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
              addedEarlyTransferIn = Some(transfer)
            ),
          )
      }
    }

    private def ingestTransferOut(
        transfer: TransferEvent.Out
    ): (State, IngestionChange) = {
      val tid = TransferId.fromTransferOut(transfer)
      earlyTransferIns.get(tid) match {
        case Some(_) =>
          // We saw the transfer in before the transfer out, remove the transfer in and ignore the tranfer out.
          (
            copy(
              earlyTransferIns = earlyTransferIns - tid
            ),
            IngestionChange.empty.copy(
              removedEarlyTransferIn = earlyTransferIns.get(tid)
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
              addedInFlightTransfer = Some(transfer)
            ),
          )
      }
    }
  }

  private case class IngestionChange(
      addedInFlightTransfer: Option[TransferEvent.Out],
      removedInFlightTransfer: Option[TransferEvent.Out],
      addedEarlyTransferIn: Option[TransferEvent.In],
      removedEarlyTransferIn: Option[TransferEvent.In],
  )

  private object IngestionChange {
    val empty: IngestionChange = IngestionChange(
      None,
      None,
      None,
      None,
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
        paramIfDefined("addedInFlightTransfer", _.change.addedInFlightTransfer),
        paramIfDefined("removedInFlightTransfer", _.change.removedInFlightTransfer),
        paramIfDefined("addedEarlyTransferIn", _.change.addedEarlyTransferIn),
        paramIfDefined("removedEarlyTransferIn", _.change.removedEarlyTransferIn),
        param("newNumInFlightTransfers", _.newNumInFlightTransfers),
        param("newNumEarlyTransferIns", _.newNumEarlyTransferIns),
        param("domain", _.domain),
        param("offset", _.offset.map(_.unquoted)),
      )
    }
  }
}
