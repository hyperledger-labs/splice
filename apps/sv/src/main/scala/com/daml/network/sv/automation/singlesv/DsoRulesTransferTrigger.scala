package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.LedgerClient.ReassignmentCommand
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.AmuletConfigSchedule
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import DsoRulesTransferTrigger.*
import com.daml.network.environment.ledger.api.LedgerClient.ReassignmentCommand.Out.pretty

final class DsoRulesTransferTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[Task] {
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    val run = for {
      dsoRules <- OptionT(store.lookupDsoRules())
      amuletRules <- OptionT(store.lookupAmuletRules())
      config = AmuletConfigSchedule(amuletRules.payload.configSchedule) getConfigAsOf now
      activeDomain <- OptionT.fromOption[Future](
        DomainId.fromString(config.globalDomain.activeDomain).toOption
      )
      _ <-
        if (activeDomain == dsoRules.domain) OptionT.none[Future, Unit]
        else OptionT.pure[Future](())
    } yield ReassignmentCommand.Unassign(
      contractId = dsoRules.contractId,
      source = dsoRules.domain,
      target = activeDomain,
    )
    run.value.map(_.toList)
  }

  override protected def completeTask(task: ReadyTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = for {
    _ <- connection.submitReassignmentAndWaitNoDedup(
      submitter = store.key.dsoParty,
      command = task.work,
    )
  } yield TaskSuccess(show"Submitted transfer ${task.work}")

  override protected def isStaleTask(task: ReadyTask)(implicit tc: TraceContext): Future[Boolean] =
    for {
      dsoRules <- store.lookupDsoRules()
    } yield dsoRules.forall { rc =>
      rc.contractId != task.work.contractId || rc.domain != task.work.source
    }
}

object DsoRulesTransferTrigger {
  private type Task = ReassignmentCommand.Unassign

  private type ReadyTask = ScheduledTaskTrigger.ReadyTask[Task]
}
