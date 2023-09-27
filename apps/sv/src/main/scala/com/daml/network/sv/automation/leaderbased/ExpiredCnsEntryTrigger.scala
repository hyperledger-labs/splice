package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.cns.CnsEntry_Expire
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpiredCnsEntryTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cn.cns.CnsEntry.ContractId,
      cn.cns.CnsEntry,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listExpiredCnsEntries,
      cn.cns.CnsEntry.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      cn.cns.CnsEntry.ContractId,
      cn.cns.CnsEntry,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    AssignedContract[
      cn.cns.CnsEntry.ContractId,
      cn.cns.CnsEntry,
    ]
  ]

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      svcRules <- store.getSvcRules()
      cmd = svcRules.exercise(
        _.exerciseSvcRules_ExpireCnsEntry(
          co.work.contractId,
          new CnsEntry_Expire(store.key.svcParty.toProtoPrimitive),
        )
      )
      _ <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.svcParty), cmd)
        .noDedup
        .yieldUnit()
    } yield TaskSuccess("archived expired CNS entry")
}
