// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{AutomationService, AutomationServiceCompanion}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.TriggerClass
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId

import scala.concurrent.ExecutionContextExecutor
import org.lfdecentralizedtrust.splice.scan.rewards.AppActivityComputation
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
    rewardsReferenceStoreO: Option[ScanRewardsReferenceStore],
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

  private val appActivityComputationO: Option[AppActivityComputation] =
    rewardsReferenceStoreO.map { store =>
      new AppActivityComputation(store, loggerFactory)
    }

  registerService(
    new ScanVerdictIngestionService(
      config = config,
      grpcClientMetrics = grpcClientMetrics,
      store = store,
      migrationId = migrationId,
      synchronizerId = synchronizerId,
      ingestionMetrics = ingestionMetrics,
      sequencerTrafficClientO = sequencerTrafficClientO,
      appActivityComputationO = appActivityComputationO,
      backoffClock = triggerContext.pollingClock,
      retryProvider = triggerContext.retryProvider,
      loggerFactory = triggerContext.loggerFactory,
    )
  )
}

object ScanVerdictAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    Seq.empty
}
