package com.daml.network.sv.automation.leaderbased

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.daml.network.codegen.java.splice.ans as ansCodegen
import com.daml.network.codegen.java.splice.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.splice.wallet.subscriptions.SubscriptionIdleState_ExpireSubscription
import com.daml.network.store.PageLimit
import com.daml.network.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}

class ExpiredAnsSubscriptionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[SvDsoStore.IdleAnsSubscription]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription]] {
  private val store = svTaskContext.dsoStore

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[SvDsoStore.IdleAnsSubscription]] =
    store.listExpiredAnsSubscriptions(now, PageLimit.tryCreate(limit))

  override protected def completeTaskAsLeader(
      task: ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- store.getDsoRules()
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_ExpireSubscription(
        task.work.context.contractId,
        task.work.state.contractId,
        new SubscriptionIdleState_ExpireSubscription(store.key.dsoParty.toProtoPrimitive),
      )
    )
    result <- svTaskContext.connection
      .submit(
        actAs = Seq(store.key.svParty),
        readAs = Seq(store.key.dsoParty),
        cmd,
      )
      .noDedup
      .yieldUnit()
      .map(_ => TaskSuccess(s"archived expired ans subscription"))

  } yield result

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription]
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
          ansCodegen.AnsEntryContext.COMPANION
        )(
          task.work.context.contractId
        )
      )
    } yield ()).isEmpty
}
