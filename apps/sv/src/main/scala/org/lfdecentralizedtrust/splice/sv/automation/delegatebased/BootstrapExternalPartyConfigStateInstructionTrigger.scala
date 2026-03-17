// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.OpenMiningRoundTriple
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.BootstrapExternalPartyConfigStateInstruction
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}

class BootstrapExternalPartyConfigStateInstructionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      BootstrapExternalPartyConfigStateInstruction.ContractId,
      BootstrapExternalPartyConfigStateInstruction,
    ](svTaskContext.dsoStore, BootstrapExternalPartyConfigStateInstruction.COMPANION)
    with SvTaskBasedTrigger[
      AssignedContract[
        BootstrapExternalPartyConfigStateInstruction.ContractId,
        BootstrapExternalPartyConfigStateInstruction,
      ]
    ] {
  private val store = svTaskContext.dsoStore

  override protected def completeTaskAsDsoDelegate(
      instruction: AssignedContract[
        BootstrapExternalPartyConfigStateInstruction.ContractId,
        BootstrapExternalPartyConfigStateInstruction,
      ],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      triple <- store.getOpenMiningRoundTriple()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_BootstrapExternalPartyConfigState(
          amuletRules.contractId,
          instruction.contractId,
          new OpenMiningRoundTriple(
            triple.oldest.contractId,
            triple.middle.contractId,
            triple.newest.contractId,
          ),
          controller,
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.High)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          cmd,
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      "Bootstrapped external party config states"
    )
  }
}
