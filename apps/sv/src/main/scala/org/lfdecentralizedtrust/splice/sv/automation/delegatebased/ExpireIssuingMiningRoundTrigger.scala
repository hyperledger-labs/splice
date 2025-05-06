// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpireIssuingMiningRoundTrigger.*
import org.lfdecentralizedtrust.splice.util.AssignedContract

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOption

class ExpireIssuingMiningRoundTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractDsoDelegateTrigger.Template[
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
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      supportsSvController <- supportsSvController()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_MiningRound_Close(
          amuletRules.contractId,
          round.contractId,
          Option.when(supportsSvController)(controller).toJava,
        )
      )
      cid <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(s"successfully created the closed mining round with cid $cid")
  }
}

private[delegatebased] object ExpireIssuingMiningRoundTrigger {
  type Task = ScheduledTaskTrigger.ReadyTask[AssignedContract[
    splice.round.IssuingMiningRound.ContractId,
    splice.round.IssuingMiningRound,
  ]]
}
