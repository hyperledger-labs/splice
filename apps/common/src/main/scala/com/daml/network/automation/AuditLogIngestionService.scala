package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription, CoinRetries}
import com.daml.network.store.AuditLogIngestionSink
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A service that feeds an audit log ingestion sink from a Ledger API transaction stream. */
class AuditLogIngestionService(
    name: String,
    ingestionSink: AuditLogIngestionSink,
    connection: CoinLedgerConnection,
    override protected val retryProvider: CoinRetries,
    loggerFactory0: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends LedgerIngestionService {

  override protected val loggerFactory: NamedLoggerFactory =
    loggerFactory0.append("ingestLoopFor", name)

  private val txFilter = CoinLedgerConnection.transactionFilterByParty(
    Map(ingestionSink.filterParty -> ingestionSink.templateIds)
  )

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CoinLedgerSubscription] =
    Future.successful {
      val offset = LedgerOffset.LedgerBegin.getInstance()
      connection.subscribeAsync(s"AuditLogIngestion($name)", offset, txFilter)(
        ingestionSink.processTransaction(_)
      )
    }

  // Kick-off the ingestion
  startIngestion()
}
