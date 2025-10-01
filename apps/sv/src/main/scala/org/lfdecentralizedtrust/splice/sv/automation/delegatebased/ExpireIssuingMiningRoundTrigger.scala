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
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpireIssuingMiningRoundTrigger.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional

class ExpireIssuingMiningRoundTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      splice.round.IssuingMiningRound.ContractId,
      splice.round.IssuingMiningRound,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredIssuingMiningRounds,
      splice.round.IssuingMiningRound.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {

  val store = svTaskContext.dsoStore

  override protected def completeTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.work
    val roundNumber = round.payload.round.number
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_MiningRound_Close(
          amuletRules.contractId,
          round.contractId,
          Optional.of(controller),
        )
      )
      cid <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Medium)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(s"successfully created the closed mining round $roundNumber with cid $cid")
  }
}

private[delegatebased] object ExpireIssuingMiningRoundTrigger {
  type Task = ScheduledTaskTrigger.ReadyTask[AssignedContract[
    splice.round.IssuingMiningRound.ContractId,
    splice.round.IssuingMiningRound,
  ]]
}
