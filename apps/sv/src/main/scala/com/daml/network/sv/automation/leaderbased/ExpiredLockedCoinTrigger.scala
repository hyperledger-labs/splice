package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import ExpiredLockedCoinTrigger.*

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
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.svcStore

  override protected def completeTaskAsLeader(
      co: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    svcRules <- store.getSvcRules()
    cmd = svcRules.exercise(
      _.exerciseSvcRules_LockedCoin_ExpireCoin(
        co.work.contractId,
        new cc.coin.LockedCoin_ExpireCoin(
          latestOpenMiningRound.contractId
        ),
      )
    )
    _ <- svTaskContext.connection
      .submit(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        update = cmd,
      )
      .noDedup
      .yieldUnit()
  } yield TaskSuccess(s"archived expired locked coin")
}

object ExpiredLockedCoinTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      AssignedContract[cc.coin.LockedCoin.ContractId, cc.coin.LockedCoin]
    ]
}
