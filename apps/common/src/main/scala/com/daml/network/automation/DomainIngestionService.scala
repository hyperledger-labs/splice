package com.daml.network.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.DomainStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class DomainIngestionService(
    sink: DomainStore.IngestionSink,
    connection: CNLedgerConnection,
    override protected val context: TriggerContext,
)(implicit val ec: ExecutionContext, val tracer: Tracer)
    extends PollingTrigger {

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      domainResults <- context.retryProvider.retryForClientCalls(
        "getConnectedDomains",
        connection.getConnectedDomains(sink.ingestionFilter),
        logger,
      )
      _ <- sink.ingestConnectedDomains(domainResults)
    } yield false
}
