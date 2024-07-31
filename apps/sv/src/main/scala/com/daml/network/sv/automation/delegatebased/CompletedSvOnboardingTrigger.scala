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
import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.util.AssignedContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import CompletedSvOnboardingTrigger.*

//TODO(#3756) reconsider this trigger
class CompletedSvOnboardingTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      DsoRules.ContractId,
      DsoRules,
    ](
      svTaskContext.dsoStore,
      DsoRules.COMPANION,
    )
    with SvTaskBasedTrigger[DsoRulesContract] {
  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      dsoRules: DsoRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svOnboardings <- store.listSvOnboardingRequestsBySvs(dsoRules)
      cmds = svOnboardings.map(co =>
        dsoRules.exercise(_.exerciseDsoRules_ArchiveSvOnboardingRequest(co.contractId))
      )
      _ <- Future.sequence(
        cmds.map(cmd =>
          svTaskContext.connection
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldUnit()
        )
      )
    } yield TaskSuccess(
      show"Archived ${cmds.size} `SvOnboardingRequest` contract(s) as the SV(s) are added to DSO."
    )
  }
}

private[delegatebased] object CompletedSvOnboardingTrigger {
  type DsoRulesContract = AssignedContract[
    DsoRules.ContractId,
    DsoRules,
  ]
}
