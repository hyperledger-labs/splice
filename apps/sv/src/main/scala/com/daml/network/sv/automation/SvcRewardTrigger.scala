package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvcRewardTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      cc.coin.SvcReward.ContractId,
      cc.coin.SvcReward,
    ](
      store,
      cc.coin.SvcReward.COMPANION,
    )
    with SvTaskBasedTrigger[ReadyContract[
      cc.coin.SvcReward.ContractId,
      cc.coin.SvcReward,
    ]] {
  type SvcRewardContract = ReadyContract[
    cc.coin.SvcReward.ContractId,
    cc.coin.SvcReward,
  ]

  override def completeTaskAsLeader(
      svcReward: SvcRewardContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId
        .exerciseSvcRules_CollectSvcReward(
          svcReward.contract.contractId
        )
      _ <-
        connection.submitWithResultNoDedup(
          Seq(store.key.svParty),
          Seq(store.key.svcParty),
          cmd,
          svcReward.domain,
        )
    } yield TaskSuccess(
      s"collected `SvcReward` of round ${svcReward.contract.payload.round.number} and created `SvReward` for each SV"
    )
  }

  override def completeTaskAsFollower(
      svcReward: SvcRewardContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(svcReward.contract)}, as we're not the leader")
    )
  }

  override protected def isLeader()(implicit tc: TraceContext): Future[Boolean] = store.svIsLeader()
}
