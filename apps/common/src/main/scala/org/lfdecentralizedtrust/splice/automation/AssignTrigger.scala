// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{LedgerClient, ReassignmentEvent}
import org.lfdecentralizedtrust.splice.store.AppStore
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class AssignTrigger(
    override protected val context: TriggerContext,
    store: AppStore,
    connection: SpliceLedgerConnection,
    partyId: PartyId,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyForAssignTrigger(
      store
    ) {

  override protected def extraMetricLabels = Seq("party" -> partyId.toString)

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

  private[automation] override final def additionalRetryableConditions
      : Map[Status.Code, RetryProvider.Condition.Category] = {
    import io.grpc.Status
    import com.digitalasset.base.error.ErrorCategory.InvalidIndependentOfSystemState
    import org.lfdecentralizedtrust.splice.environment.RetryProvider.Condition
    /*
    targeting this error, for which we want to retry (see #8267):
    category=Some(InvalidIndependentOfSystemState)
    statusCode=INVALID_ARGUMENT
    description=INVALID_ARGUMENT(8,dd8e5b92): The submitted command has invalid arguments: Cannot find transfer data for transfer `TransferId(ts = 1970-01-01T00:05:21.000023Z, source = global-domain::1220f64a394b...)`: transfer already completed
    ErrorInfoDetail(INVALID_ARGUMENT,Map(participant -> sv1Participant, tid -> dd8e5b92b8bd27f79ba97e75c3e8ceb6, category -> 8, definite_answer -> false))
    RequestInfoDetail(dd8e5b92b8bd27f79ba97e75c3e8ceb6) io.grpc.StatusRuntimeException: INVALID_ARGUMENT: INVALID_ARGUMENT(8,dd8e5b92): The submitted command has invalid arguments: Cannot find transfer data for transfer `TransferId(ts = 1970-01-01T00:05:21.000023Z, source = global-domain::1220f64a394b...)`: transfer already completed
     */
    Map(Status.Code.INVALID_ARGUMENT -> Condition.Category(InvalidIndependentOfSystemState))
  }
}
