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
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbScanVerdictStore,
  DbSequencerTrafficSummaryStore,
}
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId

import scala.concurrent.ExecutionContextExecutor
import org.lfdecentralizedtrust.splice.scan.automation.ScanVerdictStoreIngestion.prettyVerdictBatch
import com.daml.grpc.adapter.ExecutionSequencerFactory

class ScanVerdictAutomationService(
    config: ScanAppBackendConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    store: DbScanVerdictStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    ingestionMetrics: ScanMediatorVerdictIngestionMetrics,
    sequencerTrafficClientO: Option[SequencerTrafficClient],
    trafficSummaryStoreO: Option[DbSequencerTrafficSummaryStore],
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

  override def companion: AutomationServiceCompanion = ScanVerdictAutomationService

  registerTrigger(
    new ScanVerdictStoreIngestion(
      triggerContext,
      config,
      grpcClientMetrics,
      store,
      migrationId,
      synchronizerId,
      ingestionMetrics,
      sequencerTrafficClientO,
      trafficSummaryStoreO,
    )
  )
}

object ScanVerdictAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    Seq(aTrigger[ScanVerdictStoreIngestion])
}
