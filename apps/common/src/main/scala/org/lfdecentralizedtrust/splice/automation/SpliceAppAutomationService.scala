// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{
  SpliceLedgerClient,
  SpliceLedgerConnection,
  PackageIdResolver,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.store.{
  AppStore,
  AppStoreWithIngestion,
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A class that wires up a single store with an ingestion service, and provides a suitable
  * context for registering triggers against that store.
  */
abstract class SpliceAppAutomationService[Store <: AppStore](
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    override val store: Store,
    packageIdResolver: PackageIdResolver,
    ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(
      automationConfig,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      retryProvider,
    )
    with AppStoreWithIngestion[Store] {

  override val connection: SpliceLedgerConnection =
    ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory,
      packageIdResolver,
      completionOffsetCallback,
    )

  private def completionOffsetCallback(offset: Long): Future[Unit] =
    store.multiDomainAcsStore.signalWhenIngestedOrShutdown(offset)(TraceContext.empty)

  registerService(
    new UpdateIngestionService(
      store.getClass.getSimpleName,
      store.multiDomainAcsStore.ingestionSink,
      connection,
      automationConfig,
      backoffClock = triggerContext.pollingClock,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
      ingestFromParticipantBegin,
    )
  )

  registerService(
    new UpdateIngestionService(
      store.updateHistory.getClass.getSimpleName,
      store.updateHistory.ingestionSink,
      connection,
      automationConfig,
      backoffClock = triggerContext.pollingClock,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
      ingestUpdateHistoryFromParticipantBegin,
    )
  )

  registerTrigger(
    new DomainIngestionService(
      store.domains.ingestionSink,
      connection,
      // We want to always poll periodically and quickly even in simtime mode so we overwrite the clock.
      // The polling interval is governed by the domainIngestionPollingInterval config.
      triggerContext.copy(
        clock = new WallClock(
          triggerContext.timeouts,
          triggerContext.loggerFactory,
        )
      ),
    )
  )
}

object SpliceAppAutomationService {
  import AutomationServiceCompanion.*
  def expectedTriggerClasses: Seq[TriggerClass] =
    Seq(aTrigger[DomainIngestionService])
}
