// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ElectionRequest
import org.lfdecentralizedtrust.splice.util.Contract
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
      ],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- store.getDsoRules()
    (controllerArgument, preferredPackageIds) <- getDelegateLessFeatureSupportArguments(
      controller,
      context.clock.now,
    )
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_ArchiveOutdatedElectionRequest(
        task.contractId,
        controllerArgument,
      )
    )
    _ <- svTaskContext.connection
      .submit(
        Seq(store.key.svParty),
        Seq(store.key.dsoParty),
        cmd,
      )
      .noDedup
      .withPreferredPackage(preferredPackageIds)
      .yieldResult()
  } yield TaskSuccess(
    s"successfully expired the election request with cid ${task.contractId}"
  )
}
