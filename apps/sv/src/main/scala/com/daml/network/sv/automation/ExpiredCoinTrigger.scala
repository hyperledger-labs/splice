package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredCoinTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.coin.Coin.ContractId,
      cc.coin.Coin,
    ](
      store.multiDomainAcsStore,
      store.listExpiredCoins,
      cc.coin.Coin.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[ReadyContract[
      cc.coin.Coin.ContractId,
      cc.coin.Coin,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[ReadyContract[cc.coin.Coin.ContractId, cc.coin.Coin]]

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      coinRules <- store.getCoinRules()
      latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId
        .exerciseSvcRules_Coin_Expire(
          co.work.contract.contractId,
          new cc.coin.Coin_Expire(
            latestOpenMiningRound.contractId,
            coinRules.contractId.toInterface(cc.api.v1.coin.CoinRules.INTERFACE),
          ),
        )
      _ <- connection.submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = co.work.domain,
      )
    } yield TaskSuccess("archived expired coin")

  override def completeTaskAsFollower(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(co.work.contract)}, as we're not the leader")
    )
  }

  override def isLeader()(implicit tc: TraceContext): Future[Boolean] = store.svIsLeader()
}
