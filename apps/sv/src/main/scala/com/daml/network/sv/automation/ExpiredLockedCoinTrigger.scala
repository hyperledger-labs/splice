package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredLockedCoinTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.coin.LockedCoin.ContractId,
      cc.coin.LockedCoin,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listLockedExpiredCoins,
      cc.coin.LockedCoin.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[ReadyContract[
      cc.coin.LockedCoin.ContractId,
      cc.coin.LockedCoin,
    ]]] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[ReadyContract[cc.coin.LockedCoin.ContractId, cc.coin.LockedCoin]]

  private val store = svTaskContext.svcStore

  override protected def completeTaskAsLeader(
      co: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    coinRules <- store.getCoinRules()
    latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    svcRules <- store.getSvcRules()
    cmd = svcRules.contractId
      .exerciseSvcRules_LockedCoin_ExpireCoin(
        co.work.contract.contractId,
        new cc.coin.LockedCoin_ExpireCoin(
          latestOpenMiningRound.contractId,
          coinRules.contractId.toInterface(cc.api.v1.coin.CoinRules.INTERFACE),
        ),
      )
    _ <- svTaskContext.connection
      .submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = co.work.domain,
      )
  } yield TaskSuccess(s"archived expired locked coin")

  override def completeTaskAsFollower(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(co.work.contract)}, as we're not the leader")
    )
  }

}
