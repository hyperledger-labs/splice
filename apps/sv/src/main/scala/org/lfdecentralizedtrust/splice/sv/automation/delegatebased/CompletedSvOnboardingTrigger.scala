// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import CompletedSvOnboardingTrigger.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional

//TODO(DACH-NY/canton-network-node#3756) reconsider this trigger
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
      dsoRules: DsoRulesContract,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svOnboardings <- store.listSvOnboardingRequestsBySvs(dsoRules)
      cmds = svOnboardings.map(co =>
        dsoRules.exercise(
          _.exerciseDsoRules_ArchiveSvOnboardingRequest(
            co.contractId,
            Optional.of(controller),
          )
        )
      )
      _ <- Future.sequence(
        cmds.map(cmd =>
          svTaskContext
            .connection(SpliceLedgerConnectionPriority.Low)
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
