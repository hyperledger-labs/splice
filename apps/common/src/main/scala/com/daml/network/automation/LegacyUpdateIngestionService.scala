package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CNLedgerConnection, CNLedgerSubscription, RetryProvider}
import com.daml.network.store.AcsStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Ingestion for ACS stores.
  */
// TODO(#3181) Remove this once everything is switched over to the new stores.
class LegacyUpdateIngestionService(
    ingestionTargetName: String,
    acsIngestionSink: AcsStore.IngestionSink,
    domain: DomainId,
    connection: CNLedgerConnection,
    override protected val retryProvider: RetryProvider,
    baseLoggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends LedgerIngestionService {

  private val acsFilter = acsIngestionSink.ingestionFilter

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("legacyIngestionLoopFor", ingestionTargetName)

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CNLedgerSubscription[?]] =
    for {
      lastIngestedAcsOffset <- acsIngestionSink.getLastIngestedOffset
      subscribeFrom <- lastIngestedAcsOffset match {
        case None =>
          for {
            offset <- connection.ledgerEnd(domain).map {
              // Documented as always being aboslute
              case absolute: LedgerOffset.Absolute => absolute.getOffset
              case _ => sys.error("expected absolute offset")
            }
            _ <- ingestAcs(offset)
          } yield offset
        case Some(offset) => Future.successful(offset)
      }
    } yield connection.subscribeAsync(
      this.getClass.getSimpleName,
      loggerFactory,
      new LedgerOffset.Absolute(subscribeFrom),
      None,
      acsFilter.primaryParty,
      domain,
    )(acsIngestionSink.ingestUpdate(_))

  /** Ingests the ACS at the given offset */
  private def ingestAcs(
      offset: String
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      // TODO(M3-83): stream contracts instead of ingesting them as a single Seq
      evs <- connection.activeContracts(domain, acsFilter, Some(new LedgerOffset.Absolute(offset)))
      _ <- acsIngestionSink.ingestActiveContracts(evs)
      _ <- acsIngestionSink.switchToIngestingTransactions(offset)
    } yield ()
  }

  // Kick-off the ingestion
  startIngestion()
}
