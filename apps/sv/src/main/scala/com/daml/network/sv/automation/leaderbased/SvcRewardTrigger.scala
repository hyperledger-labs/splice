package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvcRewardTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      cc.coin.SvcReward.ContractId,
      cc.coin.SvcReward,
    ](
      svTaskContext.svcStore,
      cc.coin.SvcReward.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[
      cc.coin.SvcReward.ContractId,
      cc.coin.SvcReward,
    ]] {
  type SvcRewardContract = AssignedContract[
    cc.coin.SvcReward.ContractId,
    cc.coin.SvcReward,
  ]

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      svcReward: SvcRewardContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId
        .exerciseSvcRules_CollectSvcReward(
          svcReward.contractId
        )
      _ <-
        svTaskContext.connection.submitWithResultNoDedup(
          Seq(store.key.svParty),
          Seq(store.key.svcParty),
          cmd,
          svcReward.domain,
        )
    } yield TaskSuccess(
      s"collected `SvcReward` of round ${svcReward.payload.round.number} and created `SvReward` for each SV"
    )
  }
}
