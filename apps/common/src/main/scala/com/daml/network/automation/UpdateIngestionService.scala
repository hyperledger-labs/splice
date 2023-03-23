package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CNLedgerConnection, CNLedgerSubscription, RetryProvider}
import com.daml.network.store.MultiDomainAcsStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Ingestion for ACS and transfer stores.
  * We ingest them independently but we ensure that the acs store
  * is never caught up further than the transfer store to avoid losing
  * track of contracts on a transfer out.
  */
class UpdateIngestionService(
    ingestionTargetName: String,
    ingestionSink: MultiDomainAcsStore.IngestionSink,
    domain: DomainId,
    connection: CNLedgerConnection,
    override protected val retryProvider: RetryProvider,
    baseLoggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends LedgerIngestionService {

  private val filter = ingestionSink.ingestionFilter

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", ingestionTargetName)

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CNLedgerSubscription[?]] =
    for {
      lastIngestedOffset <- ingestionSink.getLastIngestedOffset(domain)
      subscribeFrom <- lastIngestedOffset match {
        case None =>
          for {
            offset <- connection.ledgerEnd(domain).map {
              // Documented as always being absolute
              case absolute: LedgerOffset.Absolute => absolute.getOffset
              case _ => sys.error("expected absolute offset")
            }
            _ <- ingestAcsAndInFlight(offset)
          } yield offset
        case Some(offset) => Future.successful(offset)
      }
    } yield connection.subscribeAsync(
      this.getClass.getSimpleName,
      loggerFactory,
      new LedgerOffset.Absolute(subscribeFrom),
      None,
      filter.primaryParty,
      domain,
    ) { ingestionSink.ingestUpdate(domain, _) }

  private def ingestAcsAndInFlight(
      offset: String
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      // TODO(M3-83): stream contracts instead of ingesting them as a single Seq
      evs <- connection.activeContracts(domain, filter, Some(new LedgerOffset.Absolute(offset)))
      tfs <- connection.getInFlightTransfers(
        domain,
        filter.primaryParty,
        Some(new LedgerOffset.Absolute(offset)),
      )
      _ <- ingestionSink.ingestAcsAndTransferOuts(domain, evs, tfs)
      _ <- ingestionSink.switchToIngestingUpdates(domain, offset)
    } yield ()
  }

  // Kick-off the ingestion
  startIngestion()
}
