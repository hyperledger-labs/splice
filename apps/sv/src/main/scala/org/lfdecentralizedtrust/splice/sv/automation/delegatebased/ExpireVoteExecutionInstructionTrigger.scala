// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.voteexecution.VoteExecutionInstruction
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpireVoteExecutionInstructionTrigger.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

class ExpireVoteExecutionInstructionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      VoteExecutionInstruction.ContractId,
      VoteExecutionInstruction,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listStaleVoteExecutionInstructions,
      VoteExecutionInstruction.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ExpireVoteExecutionInstruction(
          task.work.contractId,
          controller,
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"successfully expired VoteExecutionInstruction with cid ${task.work.contractId}"
    )
  }
}

private[delegatebased] object ExpireVoteExecutionInstructionTrigger {
  type Task = ScheduledTaskTrigger.ReadyTask[AssignedContract[
    VoteExecutionInstruction.ContractId,
    VoteExecutionInstruction,
  ]]
}
