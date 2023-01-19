package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription, CoinRetries}
import com.daml.network.store.AcsStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class AcsIngestionService(
    name: String,
    ingestionSink: AcsStore.IngestionSink,
    domain: () => Future[DomainId], // TODO (M3-18) delay service setup until domains available
    connection: CoinLedgerConnection,
    override protected val retryProvider: CoinRetries,
    loggerFactory0: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends LedgerIngestionService {

  override protected val loggerFactory: NamedLoggerFactory =
    loggerFactory0.append("ingestionLoopFor", name)

  private val igFilter = ingestionSink.ingestionFilter

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CoinLedgerSubscription] =
    for {
      lastIngestedOffset <- ingestionSink.getLastIngestedOffset
      domain <- domain()
      subscribeFrom <- lastIngestedOffset match {
        case None => ingestAcs(domain)
        case Some(off) => Future.successful(off)
      }
    } yield connection.subscribeAsync(
      s"AcsIngestion($name)",
      new LedgerOffset.Absolute(subscribeFrom),
      igFilter.primaryParty,
      domain,
    )(
      // Ingest every transaction as we get it.
      ingestionSink.ingestTransaction(_)
    )

  /** Ingests the ACS and returns the offset of the ACS, as of which the transaction stream should be read. */
  private def ingestAcs(domain: DomainId)(implicit traceContext: TraceContext): Future[String] = {
    for {
      // TODO(M3-83): stream contracts instead of ingesting them as a single Seq
      (evs, off) <- connection.activeContractsWithOffset(domain, igFilter)
      _ <- ingestionSink.ingestActiveContracts(evs)
      offsetAsString = off match {
        case absolute: LedgerOffset.Absolute => absolute.getOffset
        case _ => sys.error("expected absolute offset")
      }
      _ <- ingestionSink.switchToIngestingTransactions(offsetAsString)
    } yield offsetAsString
  }

  // Kick-off the ingestion
  startIngestion()
}
