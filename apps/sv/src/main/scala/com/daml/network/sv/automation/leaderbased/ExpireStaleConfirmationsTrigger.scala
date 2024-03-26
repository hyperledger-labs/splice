package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.dsorules.Confirmation
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import ExpireStaleConfirmationsTrigger.*

class ExpireStaleConfirmationsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      Confirmation.ContractId,
      Confirmation,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listStaleConfirmations,
      Confirmation.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsLeader(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ExpireStaleConfirmation(
          task.work.contractId
        )
      )
      _ <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"successfully expired the confirmation with cid ${task.work.contractId}"
    )
  }
}

private[leaderbased] object ExpireStaleConfirmationsTrigger {
  type Task = ScheduledTaskTrigger.ReadyTask[AssignedContract[
    Confirmation.ContractId,
    Confirmation,
  ]]
}
