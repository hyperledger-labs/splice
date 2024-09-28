// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.automation

import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.store.DomainStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}

class DomainIngestionService(
    sink: DomainStore.IngestionSink,
    connection: SpliceLedgerConnection,
    context: TriggerContext,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends PeriodicTaskTrigger(
      context.config.domainIngestionPollingInterval,
      context.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop),
      quiet = true,
    ) {

  override protected def extraMetricLabels = Seq("party" -> sink.ingestionFilter.toString)

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      domainResults <-
        connection.getConnectedDomains(sink.ingestionFilter)
      optChangeSummary <- sink.ingestConnectedDomains(domainResults)
    } yield optChangeSummary match {
      case Some(changeSummary) => TaskSuccess(show"Ingested domain store changes $changeSummary")
      case None => TaskNoop
    }
}
