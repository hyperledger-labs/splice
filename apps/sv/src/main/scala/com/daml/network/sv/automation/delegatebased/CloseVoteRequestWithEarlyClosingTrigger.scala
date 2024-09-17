// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dsorules.VoteRequest
import com.daml.network.util.AssignedContract
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
                      currentRequestCid,
                      java.util.Optional.of(amuletRulesId),
                    )
                  )
                  _ <- svTaskContext.connection
                    .submit(
                      Seq(store.key.svParty),
                      Seq(store.key.dsoParty),
                      cmd,
                    )
                    .noDedup
                    .yieldResult()
                } yield TaskSuccess(
                  s"executed the action $action as there are ${votes.size} vote(s) which is >=" +
                    s" the $requiredNumVotesForEarlyClosing votes required for early closing."
                )
              } else
                Future.successful(
                  TaskSuccess(
                    s"not yet executing the action $action," +
                      s" as there are only ${votes.size} out of" +
                      s" the $requiredNumVotesForEarlyClosing votes required for early closing."
                  )
                )
          } yield taskOutcome
        }
    } yield result
  }
}
