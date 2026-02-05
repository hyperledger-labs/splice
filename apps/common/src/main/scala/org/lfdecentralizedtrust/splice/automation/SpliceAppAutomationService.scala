// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.{
  RetryProvider,
  SpliceLedgerClient,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.store.{
  AppStore,
  AppStoreWithIngestion,
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
  UpdateHistory,
}
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.Scheduler
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.util.SpliceCircuitBreaker

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
    ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    parametersConfig: SpliceParametersConfig,
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

  private implicit val scheduler: Scheduler = mat.system.scheduler

  private val lowPriorityConnection =
    createConnectionWithPriority(SpliceLedgerConnectionPriority.Low)
  private val mediumPriorityConnection =
    createConnectionWithPriority(SpliceLedgerConnectionPriority.Medium)
  private val highPriorityConnection =
    createConnectionWithPriority(SpliceLedgerConnectionPriority.High)
  private val amuletExpiryConnection =
    createConnectionWithPriority(SpliceLedgerConnectionPriority.AmuletExpiry)

  private def createConnectionWithPriority(priority: SpliceLedgerConnectionPriority) = {
    val (name, config) = priority match {
      case SpliceLedgerConnectionPriority.High =>
        "high" -> parametersConfig.circuitBreakers.highPriority
      case SpliceLedgerConnectionPriority.Medium =>
        "medium" -> parametersConfig.circuitBreakers.mediumPriority
      case SpliceLedgerConnectionPriority.Low =>
        "low" -> parametersConfig.circuitBreakers.lowPriority
      case SpliceLedgerConnectionPriority.AmuletExpiry =>
        "amulet-expiry" -> parametersConfig.circuitBreakers.amuletExpiry
    }
    ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory.append("connectionPriority", name),
      SpliceCircuitBreaker(
        s"$name-priority-connection",
        config,
        clock,
        loggerFactory,
      ),
      completionOffsetCallback,
    )
  }

  override def connection(
      submissionPriority: SpliceLedgerConnectionPriority
  ): SpliceLedgerConnection =
    submissionPriority match {
      case SpliceLedgerConnectionPriority.High => highPriorityConnection
      case SpliceLedgerConnectionPriority.Medium => mediumPriorityConnection
      case SpliceLedgerConnectionPriority.Low => lowPriorityConnection
      case SpliceLedgerConnectionPriority.AmuletExpiry => amuletExpiryConnection
    }

  final protected def registerUpdateHistoryIngestion(
      updateHistory: UpdateHistory
  ): Unit = {
    registerService(
      new UpdateIngestionService(
        updateHistory.getClass.getSimpleName,
        updateHistory.ingestionSink,
        connection(SpliceLedgerConnectionPriority.High),
        automationConfig,
        backoffClock = triggerContext.pollingClock,
        triggerContext.retryProvider,
        triggerContext.loggerFactory,
      )
    )
  }

  private def completionOffsetCallback(offset: Long): Future[Unit] =
    store.multiDomainAcsStore.signalWhenIngestedOrShutdown(offset)(TraceContext.empty)

  registerService(
    new UpdateIngestionService(
      store.getClass.getSimpleName,
      store.multiDomainAcsStore.ingestionSink,
      connection(SpliceLedgerConnectionPriority.High),
      automationConfig,
      backoffClock = triggerContext.pollingClock,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
    )
  )

  registerTrigger(
    new DomainIngestionService(
      store.domains.ingestionSink,
      connection(SpliceLedgerConnectionPriority.High),
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
