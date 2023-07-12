package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredSvOnboardingRequestTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cn.svonboarding.SvOnboardingRequest.ContractId,
      cn.svonboarding.SvOnboardingRequest,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listExpiredSvOnboardingRequests,
      cn.svonboarding.SvOnboardingRequest.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[ReadyContract[
      cn.svonboarding.SvOnboardingRequest.ContractId,
      cn.svonboarding.SvOnboardingRequest,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    ReadyContract[
      cn.svonboarding.SvOnboardingRequest.ContractId,
      cn.svonboarding.SvOnboardingRequest,
    ]
  ]

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId.exerciseSvcRules_ExpireSvOnboardingRequest(
        co.work.contract.contractId
      )
      _ <- svTaskContext.connection.submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = co.work.domain,
      )
    } yield TaskSuccess("archived expired SV onboarding contract")
}
