package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.store.AuditLogIngestionSink
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

/** A service that feeds an audit log ingestion sink from a Ledger API transaction stream
  *
  * The transaction filter is based on the parameters provided by the audit log ingestion sink.
  */
class AuditLogIngestionService(
    name: String,
    ingestionSink: AuditLogIngestionSink,
    connection: CoinLedgerConnection,
    override protected val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    tracer: Tracer
) extends FlagCloseable
    with NamedLogging
    with Spanning {

  private val txFilter = CoinLedgerConnection.transactionFilterByParty(
    Map(ingestionSink.filterParty -> ingestionSink.templateIds)
  )

  private val serviceDescriptor = s"AuditLogIngestionService($name, ${ingestionSink.filterParty})"

  // TODO(M3-83): this ingestion is not protected from disconnects; we don't want to invest in fixing this until we have merged AuditLog and AcsStore, which we plan to do as part of the persistent stores work.
  private val subscription = {
    val offset = LedgerOffset.LedgerBegin.getInstance()
    withNewTrace(serviceDescriptor)(implicit traceContext =>
      _ => {
        logger.debug(s"Starting $serviceDescriptor")
        connection.subscribeAsync(serviceDescriptor, offset, txFilter)(
          ingestionSink.processTransaction(_)
        )
      }
    )
  }

  override def onClosed(): Unit =
    subscription.close()
}
