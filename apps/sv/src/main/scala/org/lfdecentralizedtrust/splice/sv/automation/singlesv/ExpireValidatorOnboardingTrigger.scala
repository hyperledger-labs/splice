// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import org.lfdecentralizedtrust.splice.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding.ValidatorOnboarding
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.sv.store.SvSvStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ExpireValidatorOnboardingTrigger(
    override protected val context: TriggerContext,
    store: SvSvStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
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
