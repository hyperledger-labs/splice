// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules
import com.daml.network.environment.PackageIdResolver
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class PruneAmuletConfigScheduleTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[AssignedContract[AmuletRules.ContractId, AmuletRules]]
    with SvTaskBasedTrigger[
      ScheduledTaskTrigger.ReadyTask[AssignedContract[AmuletRules.ContractId, AmuletRules]]
    ] {

  private val store = svTaskContext.dsoStore

  override def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AssignedContract[AmuletRules.ContractId, AmuletRules]]] = for {
    amuletRules <- store.getAssignedAmuletRules()
    supportsPruneAmuletConfigSchedule = PackageIdResolver.supportsPruneAmuletConfigSchedule(
      now,
      amuletRules.payload,
    )
  } yield {
    if (
      supportsPruneAmuletConfigSchedule && amuletRules.payload.configSchedule.futureValues.asScala
        .exists(futureValue => CantonTimestamp.assertFromInstant(futureValue._1) <= now)
    ) {
      Seq(amuletRules)
    } else {
      Seq.empty
    }
  }

  override def completeTaskAsDsoDelegate(
      amuletRules: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[AmuletRules.ContractId, AmuletRules]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_PruneAmuletConfigSchedule(amuletRules.work.contractId)
      )
      _ <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(s"Pruned AmuletRules config")

  override def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[AssignedContract[AmuletRules.ContractId, AmuletRules]]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(AmuletRules.COMPANION)(
        task.work.domain,
        task.work.contractId,
      )
      .map(_.isEmpty)

}
