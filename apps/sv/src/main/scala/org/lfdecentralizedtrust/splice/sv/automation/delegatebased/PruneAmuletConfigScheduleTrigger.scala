// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyInstances}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.PruneAmuletConfigScheduleTrigger.implicitPrettyString

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class PruneAmuletConfigScheduleTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[PruneAmuletConfigScheduleTrigger.Task]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[PruneAmuletConfigScheduleTrigger.Task]] {

  private val store = svTaskContext.dsoStore

  override def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[PruneAmuletConfigScheduleTrigger.Task]] = for {
    amuletRules <- store.getAssignedAmuletRules()
    pruneAmuletConfigScheduleFeatureSupport <- svTaskContext.packageVersionSupport
      .supportsPruneAmuletConfigSchedule(
        Seq(
          store.key.svParty,
          store.key.dsoParty,
        ),
        now,
      )
  } yield {
    if (
      pruneAmuletConfigScheduleFeatureSupport.supported && amuletRules.payload.configSchedule.futureValues.asScala
        .exists(futureValue => CantonTimestamp.assertFromInstant(futureValue._1) <= now)
    ) {
      Seq(amuletRules -> pruneAmuletConfigScheduleFeatureSupport.packageIds)
    } else {
      Seq.empty
    }
  }

  override def completeTaskAsDsoDelegate(
      rulesWithPreferredPackages: (
        ScheduledTaskTrigger.ReadyTask[
          PruneAmuletConfigScheduleTrigger.Task
        ],
      ),
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val (amuletRules, _) = rulesWithPreferredPackages.work
    for {
      dsoRules <- store.getDsoRules()
      (controllerArgument, preferredPackageIds) <- getDelegateLessFeatureSupportArguments(
        controller,
        context.clock.now,
      )
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_PruneAmuletConfigSchedule(
          amuletRules.contractId,
          controllerArgument,
        )
      )
      _ <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .withSynchronizerId(amuletRules.domain)
        .noDedup
        .withPreferredPackage(preferredPackageIds)
        .yieldResult()
    } yield TaskSuccess(s"Pruned AmuletRules config")
  }

  override def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[
        PruneAmuletConfigScheduleTrigger.Task
      ]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(AmuletRules.COMPANION)(
        task.work._1.domain,
        task.work._1.contractId,
      )
      .map(_.isEmpty)

}

object PruneAmuletConfigScheduleTrigger {

  private type Task = (AssignedContract[AmuletRules.ContractId, AmuletRules], Seq[String])
  implicit val implicitPrettyString: Pretty[String] = PrettyInstances.prettyString
}
