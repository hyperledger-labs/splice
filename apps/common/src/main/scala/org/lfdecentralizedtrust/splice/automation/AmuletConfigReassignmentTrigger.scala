// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.ReassignmentCommand
import org.lfdecentralizedtrust.splice.store.AppStore
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{
  ConstrainedTemplate,
  ContractState,
}
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, AssignedContract}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import AmuletConfigReassignmentTrigger.*
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.ReassignmentCommand.Out.pretty

/** Trigger that reassigns contracts of the given templates based on the amulet config.
  * We aim to keep usage of this to a minimum and rely on Canton's auto-reassignments instead.
  */
final class AmuletConfigReassignmentTrigger(
    override protected val context: TriggerContext,
    store: AppStore,
    connection: SpliceLedgerConnection,
    submitter: PartyId,
    templates: Seq[ConstrainedTemplate],
    lookupAmuletRules: TraceContext => Future[Option[
      AssignedContract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]
    ]],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[Task] {
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    val run = for {
      amuletRules <- OptionT(lookupAmuletRules(tc))
      config = AmuletConfigSchedule(amuletRules.payload.configSchedule).getConfigAsOf(now)
      activeSynchronizer <- OptionT.fromOption[Future](
        DomainId.fromString(config.decentralizedSynchronizer.activeSynchronizer).toOption
      )
      contracts <- OptionT.liftF(
        store.multiDomainAcsStore.listAssignedContractsNotOnDomainN(activeSynchronizer, templates)
      )
    } yield contracts.map(c =>
      ReassignmentCommand.Unassign(
        contractId = c.contractId,
        source = c.domain,
        target = activeSynchronizer,
      )
    )
    run.value.map(_.toList.flatten)
  }

  override protected def completeTask(task: ReadyTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = for {
    _ <- connection.submitReassignmentAndWaitNoDedup(
      submitter = submitter,
      command = task.work,
    )
  } yield TaskSuccess(show"Submitted transfer ${task.work}")

  override protected def isStaleTask(task: ReadyTask)(implicit tc: TraceContext): Future[Boolean] =
    for {
      contractStateO <- store.multiDomainAcsStore.lookupContractStateById(task.work.contractId)
      amuletRulesO <- lookupAmuletRules(tc)
    } yield contractStateO.forall { contractState =>
      amuletRulesO.forall { amuletRules =>
        val config =
          AmuletConfigSchedule(amuletRules.payload.configSchedule).getConfigAsOf(task.readyAt)
        val activeSynchronizer =
          DomainId.tryFromString(config.decentralizedSynchronizer.activeSynchronizer)
        task.work.source != activeSynchronizer ||
        ContractState.Assigned(task.work.source) != contractState
      }
    }
}

object AmuletConfigReassignmentTrigger {
  private type Task = ReassignmentCommand.Unassign

  private type ReadyTask = ScheduledTaskTrigger.ReadyTask[Task]
}
