package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvcRewardTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CoinLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[
      cc.coin.SvcReward.ContractId,
      cc.coin.SvcReward,
    ](
      store,
      () => store.domains.signalWhenConnected(store.defaultAcsDomain),
      cc.coin.SvcReward.COMPANION,
    )
    with SvTaskBasedTrigger[Contract[
      cc.coin.SvcReward.ContractId,
      cc.coin.SvcReward,
    ]] {
  type SvcRewardContract = Contract[
    cc.coin.SvcReward.ContractId,
    cc.coin.SvcReward,
  ]

  override def completeTaskAsLeader(
      svcReward: SvcRewardContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId
        .exerciseSvcRules_CollectSvcReward(
          svcReward.contractId
        )
      _ <-
        connection.submitWithResultNoDedup(
          Seq(store.key.svParty),
          Seq(store.key.svcParty),
          cmd,
          domainId,
        )
    } yield TaskSuccess(
      s"collected `SvcReward` of round ${svcReward.payload.round.number} and created `SvReward` for each SV"
    )
  }

  override def completeTaskAsFollower(
      svcReward: SvcRewardContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(svcReward)}, as we're not the leader")
    )
  }

  override protected def isLeader(): Future[Boolean] = store.svIsLeader()
}
