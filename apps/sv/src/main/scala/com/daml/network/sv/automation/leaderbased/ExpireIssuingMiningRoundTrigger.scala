package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import ExpireIssuingMiningRoundTrigger.*

class ExpireIssuingMiningRoundTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.round.IssuingMiningRound.ContractId,
      cc.round.IssuingMiningRound,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredIssuingMiningRounds,
      cc.round.IssuingMiningRound.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {

  val store = svTaskContext.dsoStore

  override protected def completeTaskAsLeader(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.work
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_MiningRound_Close(
          amuletRules.contractId,
          round.contractId,
        )
      )
      cid <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(s"successfully created the closed mining round with cid $cid")
  }
}

private[leaderbased] object ExpireIssuingMiningRoundTrigger {
  type Task = ScheduledTaskTrigger.ReadyTask[AssignedContract[
    cc.round.IssuingMiningRound.ContractId,
    cc.round.IssuingMiningRound,
  ]]
}
