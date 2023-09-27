package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireValidatorOnboardingTrigger(
    override protected val context: TriggerContext,
    store: SvSvStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      ValidatorOnboarding.ContractId,
      ValidatorOnboarding,
    ](
      store.multiDomainAcsStore,
      store.listExpiredValidatorOnboardings(),
      ValidatorOnboarding.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          ValidatorOnboarding.ContractId,
          ValidatorOnboarding,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    connection
      .submit(
        Seq(store.key.svParty),
        Seq.empty,
        task.work.exercise(_.exerciseValidatorOnboarding_Expire()),
      )
      .noDedup
      .yieldResult()
      .map(_ =>
        TaskSuccess(
          s"Archived expired ValidatorOnboarding ${task.work.payload.candidateSecret}"
        )
      )
  }
}
