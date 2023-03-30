package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredSvConfirmedTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cn.svonboarding.SvConfirmed.ContractId,
      cn.svonboarding.SvConfirmed,
    ](
      store.multiDomainAcsStore,
      store.listExpiredSvConfirmed,
      cn.svonboarding.SvConfirmed.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[ReadyContract[
      cn.svonboarding.SvConfirmed.ContractId,
      cn.svonboarding.SvConfirmed,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    ReadyContract[cn.svonboarding.SvConfirmed.ContractId, cn.svonboarding.SvConfirmed]
  ]

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId.exerciseSvcRules_ExpireSvConfirmed(co.work.contract.contractId)
      _ <- connection.submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = co.work.domain,
      )
    } yield TaskSuccess("archived expired SV confirmed contract")

  override def completeTaskAsFollower(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(
        show"ignoring expired ${PrettyContractId(co.work.contract)}, as we're not the leader"
      )
    )
  }

  override def isLeader(): Future[Boolean] = store.svIsLeader()
}
