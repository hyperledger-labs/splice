package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import ExpiredLockedAmuletTrigger.*

class ExpiredLockedAmuletTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.amulet.LockedAmulet.ContractId,
      cc.amulet.LockedAmulet,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listLockedExpiredAmulets,
      cc.amulet.LockedAmulet.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override protected def completeTaskAsLeader(
      co: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    dsoRules <- store.getDsoRules()
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_LockedAmulet_ExpireAmulet(
        co.work.contractId,
        new cc.amulet.LockedAmulet_ExpireAmulet(
          latestOpenMiningRound.contractId
        ),
      )
    )
    _ <- svTaskContext.connection
      .submit(
        Seq(store.key.svParty),
        Seq(store.key.dsoParty),
        update = cmd,
      )
      .noDedup
      .yieldUnit()
  } yield TaskSuccess(s"archived expired locked amulet")
}

object ExpiredLockedAmuletTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      AssignedContract[cc.amulet.LockedAmulet.ContractId, cc.amulet.LockedAmulet]
    ]
}
