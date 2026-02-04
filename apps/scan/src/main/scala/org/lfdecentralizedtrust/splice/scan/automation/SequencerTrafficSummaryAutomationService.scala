// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{AutomationService, AutomationServiceCompanion}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.{
  TriggerClass,
  aTrigger,
}
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.store.db.DbSequencerTrafficSummaryStore
import org.lfdecentralizedtrust.splice.scan.metrics.ScanSequencerTrafficIngestionMetrics
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId

import scala.concurrent.ExecutionContextExecutor
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.scan.automation.SequencerTrafficSummaryStoreIngestion.prettyTrafficBatch

class SequencerTrafficSummaryAutomationService(
    config: ScanAppBackendConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    store: DbSequencerTrafficSummaryStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    ingestionMetrics: ScanSequencerTrafficIngestionMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: io.opentelemetry.api.trace.Tracer,
    esf: ExecutionSequencerFactory,
) extends AutomationService(
      config.automation,
      clock,
      DomainTimeSynchronization.Noop,
      DomainUnpausedSynchronization.Noop,
      retryProvider,
    ) {

  override def companion: AutomationServiceCompanion =
    if (config.sequencerTrafficIngestion.enabled)
      SequencerTrafficSummaryAutomationService.Enabled
    else
      SequencerTrafficSummaryAutomationService.Disabled

  if (config.sequencerTrafficIngestion.enabled) {
    registerTrigger(
      new SequencerTrafficSummaryStoreIngestion(
        triggerContext,
        config,
        grpcClientMetrics,
        store,
        migrationId,
        synchronizerId,
        ingestionMetrics,
      )
    )
  }
}

object SequencerTrafficSummaryAutomationService {

  object Enabled extends AutomationServiceCompanion {
    override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
      Seq(aTrigger[SequencerTrafficSummaryStoreIngestion])
  }

  object Disabled extends AutomationServiceCompanion {
    override protected[this] def expectedTriggerClasses: Seq[TriggerClass] = Seq.empty
  }
}
