// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dsorules.ElectionRequest
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ExpireElectionRequestsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Contract[
      ElectionRequest.ContractId,
      ElectionRequest,
    ]]
    with SvTaskBasedTrigger[Contract[
      ElectionRequest.ContractId,
      ElectionRequest,
    ]] {
  private val store = svTaskContext.dsoStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[
    ElectionRequest.ContractId,
    ElectionRequest,
  ]]] =
    store.listExpiredElectionRequests(svTaskContext.epoch)

  override protected def isStaleTask(
      task: Contract[
        ElectionRequest.ContractId,
        ElectionRequest,
      ]
  )(implicit
      tc: TraceContext
  ): Future[Boolean] = store.multiDomainAcsStore.hasArchived(Seq(task.contractId))

  override def completeTaskAsDsoDelegate(
      task: Contract[
        ElectionRequest.ContractId,
        ElectionRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- store.getDsoRules()
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_ArchiveOutdatedElectionRequest(
        task.contractId
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
    s"successfully expired the election request with cid ${task.contractId}"
  )
}
