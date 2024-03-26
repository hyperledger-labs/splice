package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import ExpiredSvOnboardingConfirmedTrigger.*

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
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredSvOnboardingConfirmed,
      cn.svonboarding.SvOnboardingConfirmed.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ExpireSvOnboardingConfirmed(
          co.work.contractId
        )
      )
      _ <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldUnit()
    } yield TaskSuccess("archived expired SV confirmed contract")
}

private[leaderbased] object ExpiredSvOnboardingConfirmedTrigger {
  type Task = ScheduledTaskTrigger.ReadyTask[
    AssignedContract[
      cn.svonboarding.SvOnboardingConfirmed.ContractId,
      cn.svonboarding.SvOnboardingConfirmed,
    ]
  ]
}
