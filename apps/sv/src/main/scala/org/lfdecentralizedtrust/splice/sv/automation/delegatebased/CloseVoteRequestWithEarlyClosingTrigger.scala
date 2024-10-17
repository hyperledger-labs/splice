// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequest,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional

class CloseVoteRequestWithEarlyClosingTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[VoteRequest.ContractId, VoteRequest](
      svTaskContext.dsoStore,
      VoteRequest.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[VoteRequest.ContractId, VoteRequest]] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      voteRequestContract: AssignedContract[VoteRequest.ContractId, VoteRequest]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val trackingCid =
      voteRequestContract.payload.trackingCid.toScala.getOrElse(voteRequestContract.contractId)
    for {
      currentRequest <- store.lookupVoteRequest(trackingCid)
      action = currentRequest.map(_.payload.action)
      currentRequestCid = currentRequest.map(_.contractId).getOrElse(voteRequestContract.contractId)
      isStale = currentRequest.isEmpty
      result <-
        if (isStale)
          Future.successful(
            TaskSuccess(
              s"Skipping as the $action has already been executed."
            )
          )
        else {
          for {
            dsoRules <- store.getDsoRules()
            votes = voteRequestContract.payload.votes.values().asScala
            requiredNumVotesForEarlyClosing = dsoRules.payload.svs.size()
            taskOutcome <-
              if (votes.size >= requiredNumVotesForEarlyClosing) {
                for {
                  amuletRules <- store.getAmuletRules()
                  amuletRulesId = amuletRules.contractId
                  cmd = dsoRules.exercise(
                    _.exerciseDsoRules_CloseVoteRequest(
                      new DsoRules_CloseVoteRequest(
                        currentRequestCid,
                        java.util.Optional.of(amuletRulesId),
                      )
                    )
                  )
                  res <- for {
                    outcome <- svTaskContext.connection
                      .submit(
                        Seq(store.key.svParty),
                        Seq(store.key.dsoParty),
                        cmd,
                      )
                      .noDedup
                      .yieldResult()
                  } yield Some(outcome)
                } yield {
                  res
                    .map(result => {
                      if (
                        result.exerciseResult.outcome.toJson.contains("VRO_AcceptedButActionFailed")
                      ) {
                        TaskFailed(
                          s"action $action was accepted but failed with outcome: ${result.exerciseResult.outcome.toJson}."
                        )
                      } else {
                        TaskSuccess(
                          s"early closing VoteRequest for action: $action and outcome: ${result.exerciseResult.outcome.toJson} (${votes.size} vote(s) is >=" +
                            s" the $requiredNumVotesForEarlyClosing votes required)."
                        )
                      }
                    })
                    .getOrElse(TaskFailed(s"failed to close early VoteRequest for $action."))
                }
              } else
                Future.successful(
                  TaskSuccess(
                    s"Not yet executing the action $action," +
                      s" as there are only ${votes.size} out of" +
                      s" the $requiredNumVotesForEarlyClosing votes required for early closing."
                  )
                )
          } yield taskOutcome
        }
    } yield result
  }
}
