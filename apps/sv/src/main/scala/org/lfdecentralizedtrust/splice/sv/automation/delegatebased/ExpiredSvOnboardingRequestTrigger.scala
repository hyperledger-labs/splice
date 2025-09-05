// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class ExpiredSvOnboardingRequestTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      splice.svonboarding.SvOnboardingRequest.ContractId,
      splice.svonboarding.SvOnboardingRequest,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredSvOnboardingRequests,
      splice.svonboarding.SvOnboardingRequest.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      splice.svonboarding.SvOnboardingRequest.ContractId,
      splice.svonboarding.SvOnboardingRequest,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    AssignedContract[
      splice.svonboarding.SvOnboardingRequest.ContractId,
      splice.svonboarding.SvOnboardingRequest,
    ]
  ]

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(co: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ExpireSvOnboardingRequest(
          co.work.contractId,
          Optional.of(controller),
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldUnit()
    } yield TaskSuccess("archived expired SV onboarding contract")
}
