package com.daml.network.store

import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{Transfer, TransferEvent}
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** A per-domain store that stores all in-flight transfers targeted at store’s domain.
  */
abstract class TransferStore extends AutoCloseable {

  /** Stream all transfer out events that are ready for transfer in.
    * The only guarantee provided is that a transfer out that does not get transferred in
    * will eventually appear on the stream.
    */
  def streamReadyForTransferIn(): Source[Transfer[TransferEvent.Out], NotUsed]

  /** Returns true if the transfer out event can still potentially be transferred in.
    * Intended to be used as a staleness check for the results of `streamReadyForTransferIn`.
    */
  def isReadyForTransferIn(out: Transfer[TransferEvent.Out]): Future[Boolean]

  def ingestionSink: TransferStore.IngestionSink
}

object TransferStore {

  trait IngestionSink {
    def ingestionFilter: PartyId

    /** Ingest a transfer in event with target = store's domain.
      * This event has been observed on the target domain.
      */
    def ingestTransferIn(
        event: Transfer[TransferEvent.In]
    )(implicit traceContext: TraceContext): Future[Unit]

    /** Ingest a transfer out event with target = store's domain.
      * This event has been observed on the source domain.
      */
    def ingestTransferOut(
        event: Transfer[TransferEvent.Out]
    )(implicit traceContext: TraceContext): Future[Unit]
  }
}
