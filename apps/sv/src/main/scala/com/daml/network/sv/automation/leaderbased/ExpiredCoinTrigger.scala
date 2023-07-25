package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredCoinTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.coin.Coin.ContractId,
      cc.coin.Coin,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listExpiredCoins,
      cc.coin.Coin.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      cc.coin.Coin.ContractId,
      cc.coin.Coin,
    ]]] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[AssignedContract[cc.coin.Coin.ContractId, cc.coin.Coin]]

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      coinRules <- store.getCoinRules()
      latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId
        .exerciseSvcRules_Coin_Expire(
          co.work.contractId,
          new cc.coin.Coin_Expire(
            latestOpenMiningRound.contractId,
            coinRules.contractId.toInterface(cc.api.v1.coin.CoinRules.INTERFACE),
          ),
        )
      _ <- svTaskContext.connection.submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = co.work.domain,
      )
    } yield TaskSuccess("archived expired coin")
}
