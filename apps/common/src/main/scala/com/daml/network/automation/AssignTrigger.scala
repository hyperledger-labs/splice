package com.daml.network.automation

import akka.stream.Materializer
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.{LedgerClient, ReassignmentEvent}
import com.daml.network.store.CNNodeAppStore
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class AssignTrigger(
    override protected val context: TriggerContext,
    store: CNNodeAppStore[_, _],
    connection: CNLedgerConnection,
    partyId: PartyId,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyForAssignTrigger(
      store
    ) {

  override protected def completeTask(
      unassign: ReassignmentEvent.Unassign
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      outcome <-
        if (partyId == unassign.submitter) {
          for {
            msg <- connection
              .submitReassignmentAndWaitNoDedup(
                submitter = partyId,
                command = LedgerClient.ReassignmentCommand.Assign(
                  unassignId = unassign.unassignId,
                  source = unassign.source,
                  target = unassign.target,
                ),
              )
              .map(_ =>
                show"Initiated assign of ${unassign.contractId.contractId} from ${unassign.source} to ${unassign.target}"
              )
          } yield msg
        } else {
          Future.successful(
            show"Ignoring unassign of ${unassign.contractId.contractId}, not initiated by our party"
          )
        }
    } yield TaskSuccess(outcome)
}
