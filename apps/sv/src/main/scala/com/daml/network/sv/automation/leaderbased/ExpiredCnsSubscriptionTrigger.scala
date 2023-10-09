package com.daml.network.sv.automation.leaderbased

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionIdleState_ExpireSubscription
import com.daml.network.sv.store.SvSvcStore

import scala.concurrent.{ExecutionContext, Future}

class ExpiredCnsSubscriptionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[SvSvcStore.IdleCnsSubscription]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[SvSvcStore.IdleCnsSubscription]] {
  override protected def enableLeaderVoting: Boolean = true
  private val store = svTaskContext.svcStore

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[SvSvcStore.IdleCnsSubscription]] =
    store.listExpiredCnsSubscriptions(now, limit)

  override protected def completeTaskAsLeader(
      task: ScheduledTaskTrigger.ReadyTask[SvSvcStore.IdleCnsSubscription]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    svcRules <- store.getSvcRules()
    cmd = svcRules.exercise(
      _.exerciseSvcRules_ExpireSubscription(
        task.work.context.contractId,
        task.work.state.contractId,
        new SubscriptionIdleState_ExpireSubscription(store.key.svcParty.toProtoPrimitive),
      )
    )
    result <- svTaskContext.connection
      .submit(
        actAs = Seq(store.key.svParty),
        readAs = Seq(store.key.svcParty),
        cmd,
      )
      .noDedup
      .yieldUnit()
      .map(_ => TaskSuccess(s"archived expired cns subscription"))

  } yield result

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[SvSvcStore.IdleCnsSubscription]
  )(implicit tc: TraceContext): Future[Boolean] =
    (for {
      _ <- OptionT(
        store.multiDomainAcsStore.lookupContractById(
          subsCodegen.SubscriptionIdleState.COMPANION
        )(
          task.work.state.contractId
        )
      )
      _ <- OptionT(
        store.multiDomainAcsStore.lookupContractById(
          cnsCodegen.CnsEntryContext.COMPANION
        )(
          task.work.context.contractId
        )
      )
    } yield ()).isEmpty
}
