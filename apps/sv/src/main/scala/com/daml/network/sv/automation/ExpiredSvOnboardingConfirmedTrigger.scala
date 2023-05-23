package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredSvOnboardingConfirmedTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cn.svonboarding.SvOnboardingConfirmed.ContractId,
      cn.svonboarding.SvOnboardingConfirmed,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listExpiredSvOnboardingConfirmed,
      cn.svonboarding.SvOnboardingConfirmed.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[ReadyContract[
      cn.svonboarding.SvOnboardingConfirmed.ContractId,
      cn.svonboarding.SvOnboardingConfirmed,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    ReadyContract[
      cn.svonboarding.SvOnboardingConfirmed.ContractId,
      cn.svonboarding.SvOnboardingConfirmed,
    ]
  ]

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId.exerciseSvcRules_ExpireSvOnboardingConfirmed(
        co.work.contract.contractId
      )
      _ <- svTaskContext.connection.submitCommandsNoDedup(
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

}
