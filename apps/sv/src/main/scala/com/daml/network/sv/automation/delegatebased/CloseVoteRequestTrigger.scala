// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import com.daml.network.automation.*
import com.daml.network.codegen.java.splice.dsorules.VoteRequest
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class CloseVoteRequestTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      VoteRequest.ContractId,
      VoteRequest,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredVoteRequests(),
      VoteRequest.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      VoteRequest.ContractId,
      VoteRequest,
    ]]] {
  type Task =
    ScheduledTaskTrigger.ReadyTask[AssignedContract[VoteRequest.ContractId, VoteRequest]]

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val voteRequestCid = task.work.contractId
    for {
      dsoRules <- svTaskContext.dsoStore.getDsoRules()
      amuletRules <- store.getAmuletRules()
      amuletRulesId = amuletRules.contractId
      _ <- svTaskContext.connection
        .submit(
          Seq(svTaskContext.dsoStore.key.svParty),
          Seq(svTaskContext.dsoStore.key.dsoParty),
          dsoRules.exercise(
            _.exerciseDsoRules_CloseVoteRequest(
              voteRequestCid,
              java.util.Optional.of(amuletRulesId),
            )
          ),
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      s"Closing VoteRequest with action ${task.work.contract.payload.action.toValue} as it expired."
    )
  }
}
