// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpiredAmuletTrigger.*

class ExpiredAmuletTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      splice.amulet.Amulet.ContractId,
      splice.amulet.Amulet,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredAmulets,
      splice.amulet.Amulet.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(co: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
      dsoRules <- store.getDsoRules()
      (controllerArgument, preferredPackageIds) <- getDelegateLessFeatureSupportArguments(
        controller,
        context.clock.now,
      )
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_Amulet_Expire(
          co.work.contractId,
          new splice.amulet.Amulet_Expire(
            latestOpenMiningRound.contractId
          ),
          controllerArgument,
        )
      )
      _ <- svTaskContext.connection
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          update = cmd,
        )
        .noDedup
        .withPreferredPackage(preferredPackageIds)
        .yieldUnit()
    } yield TaskSuccess("archived expired amulet")
}

object ExpiredAmuletTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      AssignedContract[splice.amulet.Amulet.ContractId, splice.amulet.Amulet]
    ]
}
