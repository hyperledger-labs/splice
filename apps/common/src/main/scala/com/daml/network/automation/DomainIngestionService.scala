package com.daml.network.automation

import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.store.DomainStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class DomainIngestionService(
    sink: DomainStore.IngestionSink,
    adminConnection: ParticipantAdminConnection,
    override protected val context: TriggerContext,
)(implicit val ec: ExecutionContext, val tracer: Tracer)
    extends PollingTrigger {

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      domainResults <- context.retryProvider.retryForClientCalls(
        "listConnectedDomains",
        adminConnection.listConnectedDomains(),
        this,
      )
      domainMap = domainResults.view.map(result => result.domainAlias -> result.domainId).toMap
      _ <- sink.ingestConnectedDomains(domainMap)
    } yield false
}
