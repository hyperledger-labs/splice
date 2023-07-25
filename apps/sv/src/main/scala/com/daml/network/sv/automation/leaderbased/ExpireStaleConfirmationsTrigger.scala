package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.Confirmation
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

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
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listStaleConfirmations,
      Confirmation.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      Confirmation.ContractId,
      Confirmation,
    ]]] {

  type Task = ScheduledTaskTrigger.ReadyTask[AssignedContract[
    Confirmation.ContractId,
    Confirmation,
  ]]

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
      cmd = svcRules.contractId.exerciseSvcRules_ExpireStaleConfirmation(
        task.work.contractId
      )
      _ <- svTaskContext.connection
        .submitWithResultNoDedup(Seq(store.key.svParty), Seq(store.key.svcParty), cmd, domainId)
    } yield TaskSuccess(
      s"successfully expired the confirmation with cid ${task.work.contractId}"
    )
  }
}
