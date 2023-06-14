package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future, blocking}
import com.digitalasset.canton.lifecycle.RunOnShutdown
import com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable
import com.digitalasset.canton.lifecycle.SyncCloseable
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.lifecycle.UnlessShutdown

class RestartLeaderBasedAutomationTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
    clock: Clock,
    config: SvAppBackendConfig,
    appLevelRetryProvider: RetryProvider,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      cn.svcrules.SvcRules.ContractId,
      cn.svcrules.SvcRules,
    ](
      store,
      cn.svcrules.SvcRules.COMPANION,
    ) {
  type SvcRulesContract = ReadyContract[
    cn.svcrules.SvcRules.ContractId,
    cn.svcrules.SvcRules,
  ]

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var epochStateVar: Option[EpochState] = None

  private def closeRetryProvider(): Unit =
    epochStateVar.foreach(epochState => Lifecycle.close(epochState.retryProvider)(logger))

  private def closeService(): Unit =
    epochStateVar.foreach(epochState => Lifecycle.close(epochState.leaderBasedAutomation)(logger))

  appLevelRetryProvider.runOnShutdown(new RunOnShutdown {
    override def name = s"shutdown per-epoch retry provider"
    override def done = false
    override def run() = closeRetryProvider()
  })(TraceContext.empty)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    SyncCloseable("Per-epoch LeaderBasedAutomationService", closeService()) +: super.closeAsync()

  override def completeTask(
      svcRules: SvcRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = Future {
    blocking {

      synchronized {
        val currentEpoch = svcRules.contract.payload.epoch
        val lastKnownEpoch = epochStateVar.map(_.epoch)

        epochStateVar match {
          case None =>
            logger.debug(s"Learned first epoch $currentEpoch")
            restartAutomation(currentEpoch, svcRules)
          case Some(state) =>
            if (state.epoch != currentEpoch) {
              logger.debug(s"Epoch changed from ${state.epoch} to $currentEpoch")
              restartAutomation(currentEpoch, svcRules)
            } else {
              logger.debug(
                s"SvcRules changed, but the epoch stayed the same (epoch $lastKnownEpoch)"
              )
              TaskSuccess(
                s"SvcRules changed, but the epoch stayed the same (epoch $lastKnownEpoch)"
              )
            }
        }
      }
    }
  }

  private def restartAutomation(epoch: Long, svcRules: SvcRulesContract)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): TaskOutcome = {
    logger.debug(s"Restarting triggers for new epoch: ${epoch}")

    val svTaskContext =
      new SvTaskBasedTrigger.Context(
        store,
        connection,
        PartyId.tryFromProtoPrimitive(svcRules.contract.payload.leader),
        epoch,
      )

    (if (appLevelRetryProvider.isClosing) {
       // Avoid updating state when we are shutting down.
       UnlessShutdown.AbortedDueToShutdown
     } else {
       closeRetryProvider()
       closeService()

       val retryProvider =
         RetryProvider(loggerFactory, timeouts, appLevelRetryProvider.futureSupervisor)
       val leaderBasedAutomation = new LeaderBasedAutomationService(
         clock,
         config,
         svTaskContext,
         retryProvider,
         loggerFactory,
       )

       epochStateVar = Some(
         EpochState(
           epoch,
           leaderBasedAutomation,
           retryProvider,
         )
       )

       // Shutdown might have been initiated concurrently with our change to the epochStateVar
       if (appLevelRetryProvider.isClosing) {
         logger.debug(
           "Detected race between update of state and shutdown: closing down leader-based automation again to be on the safe side."
         )(TraceContext.empty)
         closeRetryProvider()
         closeService()
         UnlessShutdown.AbortedDueToShutdown
       } else {
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
    val epoch: Long,
    val leaderBasedAutomation: LeaderBasedAutomationService,
    val retryProvider: RetryProvider,
) {}
