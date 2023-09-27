package com.daml.network.sv.automation.singlesv

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.{Contract, AssignedContract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvRewardTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      cn.svcrules.SvReward.ContractId,
      cn.svcrules.SvReward,
    ](
      store,
      cn.svcrules.SvReward.COMPANION,
    ) {
  type SvRewardContract = AssignedContract[
    cn.svcrules.SvReward.ContractId,
    cn.svcrules.SvReward,
  ]

  override def completeTask(
      svReward: SvRewardContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      openMiningRounds <- store.getOpenMiningRoundTriple()
      // find the newest mining round that we can still collect in, and collect if possible
      bestOpenMiningRound = openMiningRounds.toSeq
        .filter(_.payload.round.number <= svReward.payload.round.number + 10)
        .maxByOption(_.payload.round.number)
      outcome <- bestOpenMiningRound
        .map(collectSvReward(svReward, _))
        .getOrElse(ignoreSvReward(svReward))
    } yield outcome
  }

  private def collectSvReward(
      svReward: SvRewardContract,
      openMiningRound: Contract[cc.round.OpenMiningRound.ContractId, cc.round.OpenMiningRound],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      cmd = svcRules.exercise(
        _.exerciseSvcRules_CollectSvReward(
          store.key.svParty.toProtoPrimitive,
          svReward.contractId,
          coinRules.contractId,
          openMiningRound.contractId,
        )
      )
      _ <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.svcParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"collected `SvReward` of round ${svReward.payload.round.number} and create Coin for SV ${svReward.payload.sv}"
    )
  }

  private def ignoreSvReward(
      svReward: SvRewardContract
  ): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(
        s"ignored `SvReward` of round ${svReward.payload.round.number} becase it's too old to be collected"
      )
    )
  }
}
