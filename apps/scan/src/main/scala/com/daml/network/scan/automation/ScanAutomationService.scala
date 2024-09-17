// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{AutomationServiceCompanion, SpliceAppAutomationService}
import com.daml.network.environment.{PackageIdResolver, RetryProvider, SpliceLedgerClient}
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.scan.store.{AcsSnapshotStore, ScanStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a CC Scan app. */
class ScanAutomationService(
    config: ScanAppBackendConfig,
    clock: Clock,
    ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    store: ScanStore,
    snapshotStore: AcsSnapshotStore,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      config.automation,
      clock,
      // scan only does reads so no need to block anything.
      DomainTimeSynchronization.Noop,
      DomainUnpausedSynchronization.Noop,
      store,
      PackageIdResolver.inferFromAmuletRules(clock, store, loggerFactory),
      ledgerClient,
      retryProvider,
      ingestFromParticipantBegin,
      ingestUpdateHistoryFromParticipantBegin,
    ) {
  override def companion = ScanAutomationService

  registerTrigger(new ScanAggregationTrigger(store, triggerContext))
  registerTrigger(new ScanBackfillAggregatesTrigger(store, triggerContext))
  registerTrigger(
    new AcsSnapshotTrigger(
      snapshotStore,
      store.updateHistory,
      config.acsSnapshotPeriodHours,
      triggerContext,
    )
  )
}

object ScanAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses = Seq.empty
}
