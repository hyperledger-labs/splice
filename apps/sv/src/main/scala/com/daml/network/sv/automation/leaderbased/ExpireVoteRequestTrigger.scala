package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn.svcrules.{VoteRequest, VoteRequest_Expire}
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireVoteRequestTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      VoteRequest.ContractId,
      VoteRequest,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listExpiredVoteRequests(),
      VoteRequest.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      VoteRequest.ContractId,
      VoteRequest,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[AssignedContract[VoteRequest.ContractId, VoteRequest]]

  override def completeTaskAsLeader(task: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      svcRules <- svTaskContext.svcStore.getSvcRules()
      _ <- svTaskContext.connection
        .submit(
          Seq(svTaskContext.svcStore.key.svParty),
          Seq(svTaskContext.svcStore.key.svcParty),
          svcRules.exercise(
            _.exerciseSvcRules_VoteRequest_Expire(
              task.work.contractId,
              new VoteRequest_Expire(),
            )
          ),
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      s"Archived expired VoteRequest ${task.work.contract.payload.action.toValue}"
    )
}
