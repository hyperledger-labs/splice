package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredLockedCoinTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      cc.coin.LockedCoin.ContractId,
      cc.coin.LockedCoin,
    ](
      store.defaultAcs,
      store.listLockedExpiredCoins,
      cc.coin.LockedCoin.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[Contract[
      cc.coin.LockedCoin.ContractId,
      cc.coin.LockedCoin,
    ]]] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[Contract[cc.coin.LockedCoin.ContractId, cc.coin.LockedCoin]]

  override protected def completeTaskAsLeader(
      co: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
    coinRules <- store.getCoinRules()
    latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    svcRules <- store.getSvcRules()
    cmd = svcRules.contractId
      .exerciseSvcRules_LockedCoin_ExpireCoin(
        co.work.contractId,
        new cc.coin.LockedCoin_ExpireCoin(
          latestOpenMiningRound.contractId,
          coinRules.contractId.toInterface(cc.api.v1.coin.CoinRules.INTERFACE),
        ),
      )
    _ <- connection
      .submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = domainId,
      )
  } yield TaskSuccess(s"archived expired locked coin")

  override def completeTaskAsFollower(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(co.work)}, as we're not the leader")
    )
  }

  override def isLeader(): Future[Boolean] = store.svIsLeader()
}
