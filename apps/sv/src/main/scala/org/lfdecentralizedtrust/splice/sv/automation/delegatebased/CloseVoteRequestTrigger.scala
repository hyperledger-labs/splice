// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequest,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.util.AssignedContract

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class CloseVoteRequestTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    materializer: Materializer,
) extends MultiDomainExpiredContractTrigger.Template[
      VoteRequest.ContractId,
      VoteRequest,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listVoteRequestsReadyToBeClosed,
      VoteRequest.COMPANION,
    )
    with SvTaskBasedTrigger[CloseVoteRequestTrigger.Task] {

  override def completeTaskAsDsoDelegate(
      task: CloseVoteRequestTrigger.Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val store = svTaskContext.dsoStore
    val request = task.work
    val voteRequestCid = task.work.contractId
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      amuletRulesId = amuletRules.contractId
      res <- for {
        outcome <- svTaskContext
          .connection(SpliceLedgerConnectionPriority.High)
          .submit(
            Seq(store.key.svParty),
            Seq(store.key.dsoParty),
            dsoRules.exercise(
              _.exerciseDsoRules_CloseVoteRequest(
                new DsoRules_CloseVoteRequest(
                  voteRequestCid,
                  java.util.Optional.of(amuletRulesId),
                  Optional.of(controller),
                )
              )
            ),
          )
          .noDedup
          .yieldResult()
      } yield Some(outcome)
    } yield {
      res
        .map(result => {
          if (result.exerciseResult.outcome.toJson.contains("VRO_AcceptedButActionFailed")) {
            TaskFailed(
              s"request ${request.contractId} was accepted but failed with outcome: ${result.exerciseResult.outcome.toJson}."
            )
          } else {
            TaskSuccess(
              s"closing VoteRequest (voteRequestCid: ${request.contractId}) and outcome: ${result.exerciseResult.outcome.toJson}."
            )
          }
        })
        .getOrElse(
          TaskFailed(
            s"failed to close VoteRequest. (voteRequestCid: ${request.contractId})"
          )
        )
    }
  }

}

object CloseVoteRequestTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      AssignedContract[VoteRequest.ContractId, VoteRequest]
    ]
}
