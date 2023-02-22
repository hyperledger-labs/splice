package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredCoinTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      cc.coin.Coin.ContractId,
      cc.coin.Coin,
    ](
      store.defaultAcs,
      store.listExpiredCoins,
      cc.coin.Coin.COMPANION,
    ) {

  override protected def completeTask(
      co: ScheduledTaskTrigger.ReadyTask[Contract[
        cc.coin.Coin.ContractId,
        cc.coin.Coin,
      ]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    // TODO(#2648) consider extracting this pattern into a reusable abstract class (`SvTrigger`?)
    store
      .svIsLeader()
      .flatMap(if (_) {
        completeTaskAsLeader(co)
      } else {
        completeTaskAsFollower(co)
      })
  }

  def completeTaskAsLeader(
      co: ScheduledTaskTrigger.ReadyTask[Contract[
        cc.coin.Coin.ContractId,
        cc.coin.Coin,
      ]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
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
    _ <- connection.submitCommandsNoDedup(
      Seq(store.key.svParty),
      Seq(store.key.svcParty),
      commands = cmd.commands.asScala.toSeq,
      domainId = domainId,
    )
  } yield TaskSuccess("archived expired coin")

  @nowarn("cat=unused")
  def completeTaskAsFollower(
      co: ScheduledTaskTrigger.ReadyTask[Contract[
        cc.coin.Coin.ContractId,
        cc.coin.Coin,
      ]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(s"ignoring expired coin ${co.work.contractId} as we're not the leader")
    )
  }
}
