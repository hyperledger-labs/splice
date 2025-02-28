// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.sv.automation.DsoDelegateBasedAutomationService
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.SvTaskBasedTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future, blocking}
import com.digitalasset.canton.lifecycle.RunOnShutdown
import com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable
import com.digitalasset.canton.lifecycle.SyncCloseable
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.util.ShowUtil.*

class RestartDsoDelegateBasedAutomationTrigger(
    override protected val context: TriggerContext,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    clock: Clock,
    config: SvAppBackendConfig,
    appLevelRetryProvider: RetryProvider,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      splice.dsorules.DsoRules.ContractId,
      splice.dsorules.DsoRules,
    ](
      store,
      splice.dsorules.DsoRules.COMPANION,
    ) {
  type DsoRulesContract = AssignedContract[
    splice.dsorules.DsoRules.ContractId,
    splice.dsorules.DsoRules,
  ]

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var epochStateVar: Option[EpochState] = None

  private def closeRetryProvider(): Unit =
    epochStateVar.foreach(epochState => LifeCycle.close(epochState.retryProvider)(logger))

  private def closeService(): Unit =
    epochStateVar.foreach(epochState =>
      LifeCycle.close(epochState.dsoDelegateBasedAutomation)(logger)
    )

  def epochState: Option[EpochState] = epochStateVar

  appLevelRetryProvider.runOnShutdownWithPriority_(new RunOnShutdown {
    override def name = s"set per-epoch retry provider as closing"
    override def done = false
    override def run() = epochStateVar.foreach(_.retryProvider.setAsClosing())
  })(TraceContext.empty)

  appLevelRetryProvider.runOnShutdown_(new RunOnShutdown {
    override def name = s"shutdown per-epoch retry provider"
    override def done = false
    override def run() = closeRetryProvider()
  })(TraceContext.empty)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    SyncCloseable("Per-epoch DsoDelegateBasedAutomationService", closeService()) +: super
      .closeAsync()

  override def completeTask(
      dsoRules: DsoRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = Future {
    blocking {

      synchronized {
        val currentEpoch = dsoRules.payload.epoch
        val currentDsoDelegate = PartyId.tryFromProtoPrimitive(dsoRules.payload.dsoDelegate)
        val lastKnownEpoch = epochStateVar.map(_.epoch)

        epochStateVar match {
          case None =>
            logger.debug(s"Learned first epoch $currentEpoch")
            restartAutomation(currentEpoch, dsoRules)
          case Some(state) =>
            if (state.epoch != currentEpoch) {
              logger.info(
                show"Noticed an DsoRules epoch change (from ${state.epoch} with delegate ${state.dsoDelegate} to $currentEpoch with delegate ${currentDsoDelegate})."
              )
              logger.debug(
                s"Restarting automation, as the epoch changed from ${state.epoch} to $currentEpoch"
              )
              restartAutomation(currentEpoch, dsoRules)
            } else {
              TaskSuccess(
                s"DsoRules changed, but the epoch stayed the same (epoch $lastKnownEpoch)"
              )
            }
        }
      }
    }
  }

  private def restartAutomation(epoch: Long, dsoRules: DsoRulesContract)(implicit
      ec: ExecutionContext
  ): TaskOutcome = {
    val svTaskContext =
      new SvTaskBasedTrigger.Context(
        store,
        connection,
        PartyId.tryFromProtoPrimitive(dsoRules.payload.dsoDelegate),
        epoch,
      )

    (if (appLevelRetryProvider.isClosing) {
       // Avoid updating state when we are shutting down.
       UnlessShutdown.AbortedDueToShutdown
     } else {
       closeRetryProvider()
       closeService()

       val leaderLoggerFactory = loggerFactory.appendUnnamedKey(
         "isLeader",
         (dsoRules.contract.payload.dsoDelegate == store.key.svParty.toProtoPrimitive).toString,
       )
       val retryProvider =
         RetryProvider(
           leaderLoggerFactory,
           timeouts,
           appLevelRetryProvider.futureSupervisor,
           context.metricsFactory,
         )
       val dsoDelegateBasedAutomation = new DsoDelegateBasedAutomationService(
         clock,
         domainTimeSync,
         domainUnpausedSync,
         config,
         svTaskContext,
         retryProvider,
         leaderLoggerFactory,
       )

       epochStateVar = Some(
         EpochState(
           epoch,
           PartyId.tryFromProtoPrimitive(dsoRules.payload.dsoDelegate),
           dsoDelegateBasedAutomation,
           retryProvider,
         )
       )

       // Shutdown might have been initiated concurrently with our change to the epochStateVar
       if (appLevelRetryProvider.isClosing) {
         logger.debug(
           "Detected race between update of state and shutdown: closing down delegate-based automation again to be on the safe side."
         )(TraceContext.empty)
         closeRetryProvider()
         closeService()
         UnlessShutdown.AbortedDueToShutdown
       } else {
         // Delay startup of tasks until here.
         // Even if right after the else, but before starting, it starts shutdown, that's okay,
         // because the child RetryProvider is already scheduled for shutdown.
         dsoDelegateBasedAutomation.start()
         UnlessShutdown.Outcome(TaskSuccess(s"Started automation for epoch $epoch"))
       }
     }).onShutdown(
      TaskSuccess(
        s"Skipped or aborted restarting triggers for new epoch: $epoch, as we are shutting down."
      )
    )
  }
}

case class EpochState(
    epoch: Long,
    dsoDelegate: PartyId,
    dsoDelegateBasedAutomation: DsoDelegateBasedAutomationService,
    retryProvider: RetryProvider,
) {}
