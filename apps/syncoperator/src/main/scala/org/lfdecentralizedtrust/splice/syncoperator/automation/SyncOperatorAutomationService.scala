// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.PositiveLong
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  SqlIndexInitializationTrigger,
}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.TriggerClass
import org.lfdecentralizedtrust.splice.lsu.LsuTransferTrafficTrigger
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  RetryProvider,
  SpliceLedgerClient,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorLsuConfig
import org.lfdecentralizedtrust.splice.syncoperator.store.SyncOperatorStore

import java.nio.file.Path

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a sync operator app. */
class SyncOperatorAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    override val store: SyncOperatorStore,
    storage: DbStorage,
    ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    params: SpliceParametersConfig,
    synchronizerNodeService: SynchronizerNodeService[SyncOperatorSynchronizerNode],
    scanConnection: ScanConnection,
    lsuConfig: Option[SyncOperatorLsuConfig],
    lsuDumpPath: Option[Path],
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
    outageTrafficAllowance: Option[PositiveLong],
    protected val loggerFactory: NamedLoggerFactory,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      automationConfig,
      clock,
      // Nothing registered here depends on domain time.
      DomainTimeSynchronization.Noop,
      store,
      ledgerClient,
      retryProvider,
      params,
      packageVersionSupport,
    ) {

  override def companion: SyncOperatorAutomationService.type = SyncOperatorAutomationService

  registerTrigger(
    SqlIndexInitializationTrigger(
      storage,
      triggerContext,
    )
  )

  registerTrigger(
    new ReconcileDedicatedSynchronizerParametersTrigger(
      triggerContext,
      store,
      synchronizerNodeService,
      scanConnection,
    )
  )

  registerTrigger(
    new MediatorUnlimitedTrafficTrigger(
      triggerContext,
      store.key.synchronizerId,
      synchronizerNodeService,
      trafficBalanceReconciliationDelay,
    )
  )

  registerTrigger(
    new ReconcileDedicatedSequencerTrafficTrigger(
      triggerContext,
      store,
      synchronizerNodeService,
      trafficBalanceReconciliationDelay,
      outageTrafficAllowance,
    )
  )

  registerLsuTriggers()

  /** The upgrade path runs only once a successor node is configured. */
  private def registerLsuTriggers(): Unit =
    synchronizerNodeService.nodes.successor match {
      case Some(successorSynchronizerNode) =>
        lsuConfig.foreach { lsu =>
          registerTrigger(
            new DedicatedLsuAnnouncementTrigger(
              triggerContext,
              synchronizerNodeService.nodes.current.sequencerAdminConnection,
              lsu,
            )
          )
        }
        registerTrigger(
          new DedicatedLsuTrigger(
            triggerContext,
            synchronizerNodeService.nodes,
            successorSynchronizerNode,
            lsuDumpPath.getOrElse(
              throw new IllegalArgumentException(
                "lsu-dump-path must be set when a successor synchronizer node is configured"
              )
            ),
          )
        )
        registerTrigger(
          new LsuTransferTrafficTrigger(
            triggerContext,
            synchronizerNodeService.nodes.current,
            successorSynchronizerNode,
          )
        )
      case None =>
        lsuConfig.foreach(_ =>
          logger.warn(
            "An upgrade is scheduled but no successor synchronizer node is configured, so it will " +
              "not run. Configure synchronizer-nodes.successor."
          )(TraceContext.empty)
        )
    }
}

object SyncOperatorAutomationService extends AutomationServiceCompanion {
  import AutomationServiceCompanion.aTrigger

  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    SpliceAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[SqlIndexInitializationTrigger],
      aTrigger[ReconcileDedicatedSynchronizerParametersTrigger],
      aTrigger[MediatorUnlimitedTrafficTrigger],
      aTrigger[ReconcileDedicatedSequencerTrafficTrigger],
      aTrigger[DedicatedLsuAnnouncementTrigger],
      aTrigger[DedicatedLsuTrigger],
      aTrigger[LsuTransferTrafficTrigger],
    )
}
