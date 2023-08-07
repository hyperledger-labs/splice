package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.LedgerClient.ReassignmentCommand
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.CoinConfigSchedule
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import SvcRulesTransferTrigger.*
import com.daml.network.environment.ledger.api.LedgerClient.ReassignmentCommand.Out.pretty

private[automation] final class SvcRulesTransferTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[Task] {
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    val run = for {
      svcRules <- OptionT(store.lookupSvcRules())
      coinRules <- OptionT(store.lookupCoinRules())
      config = CoinConfigSchedule(coinRules.payload.configSchedule) getConfigAsOf now
      activeDomain <- OptionT.fromOption[Future](
        DomainId.fromString(config.globalDomain.activeDomain).toOption
      )
      _ <-
        if (activeDomain == svcRules.domain) OptionT.none[Future, Unit]
        else OptionT.pure[Future](())
    } yield ReassignmentCommand.Unassign(
      contractId = svcRules.contractId,
      source = svcRules.domain,
      target = activeDomain,
    )
    run.value.map(_.toList)
  }

  override protected def completeTask(task: ReadyTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = for {
    _ <- connection.submitReassignmentAndWaitNoDedup(
      submitter = store.key.svcParty,
      command = task.work,
    )
  } yield TaskSuccess(show"Submitted transfer ${task.work}")

  override protected def isStaleTask(task: ReadyTask)(implicit tc: TraceContext): Future[Boolean] =
    for {
      svcRules <- store.lookupSvcRules()
    } yield svcRules.forall { rc =>
      rc.contractId != task.work.contractId || rc.domain != task.work.source
    }
}

object SvcRulesTransferTrigger {
  private type Task = ReassignmentCommand.Unassign

  private type ReadyTask = ScheduledTaskTrigger.ReadyTask[Task]
}
