// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dso.svstate.SvRewardState
import com.daml.network.codegen.java.splice.dsorules.DsoRules_MergeSvRewardState
import com.daml.network.store.PageLimit
import com.daml.network.util.{AssignedContract, Contract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Trigger to merge multiple SvRewardStateContracts for the same SV name.
  * This only exists to cleanup after #12495.
  */
class MergeSvRewardStateContractsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[SvRewardState.ContractId, SvRewardState](
      svTaskContext.dsoStore,
      SvRewardState.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[SvRewardState.ContractId, SvRewardState]] {

  private val store = svTaskContext.dsoStore

  private val MAX_SV_REWARD_CONTRACTS = PageLimit.tryCreate(10)

  override def completeTaskAsDsoDelegate(
      svRewardState: AssignedContract[SvRewardState.ContractId, SvRewardState]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val svName = svRewardState.payload.svName
    for {
      svRewardStates <- store.listSvRewardStates(
        svName,
        MAX_SV_REWARD_CONTRACTS,
      )
      outcome <-
        if (svRewardStates.length > 1) {
          logger.warn(
            s"SV $svName has ${svRewardStates.length} SvRewardState contracts, this likely indicates a bug"
          )
          mergeSvRewardStateContracts(svName, svRewardStates)
        } else
          Future.successful(
            TaskSuccess(s"Only one SvRewardState contract for $svName, nothing to merge")
          )
    } yield outcome
  }

  def mergeSvRewardStateContracts(
      svName: String,
      svRewardStates: Seq[Contract[SvRewardState.ContractId, SvRewardState]],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      arg = new DsoRules_MergeSvRewardState(
        svName,
        svRewardStates.map(_.contractId).asJava,
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeSvRewardState(arg))
      _ <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(s"Merged ${svRewardStates.length} member traffic contracts for $svName")
  }

}
