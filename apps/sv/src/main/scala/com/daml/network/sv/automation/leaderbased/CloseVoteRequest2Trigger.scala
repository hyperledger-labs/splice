package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn.svcrules.VoteRequest2
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class CloseVoteRequest2Trigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      VoteRequest2.ContractId,
      VoteRequest2,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listExpiredVoteRequests2(),
      VoteRequest2.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      VoteRequest2.ContractId,
      VoteRequest2,
    ]]] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[AssignedContract[VoteRequest2.ContractId, VoteRequest2]]

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(task: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    val voteRequestCid = task.work.contractId
    for {
      svcRules <- svTaskContext.svcStore.getSvcRules()
      coinRules <- store.getCoinRules()
      coinRulesId = coinRules.contractId
      _ <- svTaskContext.connection
        .submit(
          Seq(svTaskContext.svcStore.key.svParty),
          Seq(svTaskContext.svcStore.key.svcParty),
          svcRules.exercise(
            _.exerciseSvcRules_CloseVoteRequest2(
              voteRequestCid,
              java.util.Optional.of(coinRulesId),
            )
          ),
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      s"Closing VoteRequest2 with action ${task.work.contract.payload.action.toValue} as it expired."
    )
  }
}
