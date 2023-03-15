package com.daml.network.store

import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{
  TreeUpdate,
  Transfer,
  TransferEvent,
}
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** A multi-domain store that stores all in-flight transfers based on ingesting updates from
  *  all connected domains.
  */
abstract class TransferStore extends AutoCloseable {

  /** Stream all transfer out events that are ready for transfer in.
    * The only guarantee provided is that a transfer out that does not get transferred in
    * will eventually appear on the stream.
    */
  def streamReadyForTransferIn(domainId: DomainId): Source[TransferEvent.Out, NotUsed]

  /** Returns true if the transfer out event can still potentially be transferred in.
    * Intended to be used as a staleness check for the results of `streamReadyForTransferIn`.
    */
  def isReadyForTransferIn(out: TransferEvent.Out): Future[Boolean]

  def ingestionSink: TransferStore.IngestionSink
}

object TransferStore {

  trait IngestionSink {
    def getLastIngestedOffset(domain: DomainId): Future[Option[String]]

    def ingestionFilter: PartyId

    def ingestUpdate(
        domainId: DomainId,
        update: TreeUpdate,
    )(implicit traceContext: TraceContext): Future[Unit]

    def ingestTransfer(
        event: Transfer[TransferEvent]
    )(implicit traceContext: TraceContext): Future[Unit]

    def ingestInFlightTransfers(
        observedOn: DomainId,
        transfers: Seq[TransferEvent.Out],
    )(implicit traceContext: TraceContext): Future[Unit]

    def switchToIngestingUpdates(domainId: DomainId, offset: String)(implicit
        traceContext: TraceContext
    ): Future[Unit]

    def removeCompletedTransferOut(
        event: TransferEvent.Out
    )(implicit traceContext: TraceContext): Future[Unit]
  }
}
