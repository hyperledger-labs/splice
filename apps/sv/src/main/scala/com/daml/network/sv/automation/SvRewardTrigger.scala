package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
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
) extends OnReadyContractTrigger.Template[
      cn.svcrules.SvReward.ContractId,
      cn.svcrules.SvReward,
    ](
      store,
      cn.svcrules.SvReward.COMPANION,
    ) {
  type SvRewardContract = ReadyContract[
    cn.svcrules.SvReward.ContractId,
    cn.svcrules.SvReward,
  ]

  override def completeTask(
      svReward: SvRewardContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      openMiningRound <- store.getLatestActiveOpenMiningRound()
      cmd = svcRules.contractId
        .exerciseSvcRules_CollectSvReward(
          store.key.svParty.toProtoPrimitive,
          svReward.contract.contractId,
          coinRules.contractId,
          openMiningRound.contractId,
        )
      _ <-
        connection.submitWithResultNoDedup(
          Seq(store.key.svParty),
          Seq(store.key.svcParty),
          cmd,
          svReward.domain,
        )
    } yield TaskSuccess(
      s"collected `SvReward` of round ${svReward.contract.payload.round.number} and create Coin for SV ${svReward.contract.payload.sv}"
    )
  }
}
