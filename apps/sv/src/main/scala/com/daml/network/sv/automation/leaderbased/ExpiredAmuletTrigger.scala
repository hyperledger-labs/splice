package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import ExpiredAmuletTrigger.*

class ExpiredAmuletTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.amulet.Amulet.ContractId,
      cc.amulet.Amulet,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredAmulets,
      cc.amulet.Amulet.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_Amulet_Expire(
          co.work.contractId,
          new cc.amulet.Amulet_Expire(
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
    } yield TaskSuccess("archived expired amulet")
}

object ExpiredAmuletTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[AssignedContract[cc.amulet.Amulet.ContractId, cc.amulet.Amulet]]
}
