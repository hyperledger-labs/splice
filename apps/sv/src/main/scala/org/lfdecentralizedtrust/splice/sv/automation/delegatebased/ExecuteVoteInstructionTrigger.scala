// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskFailed,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.voteexecution.VoteExecutionInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.voteexecution.executioninstruction.{
  EI_ValidatorLicenseChangeWeight,
  EI_ValidatorLicenseWithdraw,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.voteexecution.executioninstructioninputs.EII_ValidatorLicense
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_ExecuteVoteExecutionInstruction
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}

class ExecuteVoteInstructionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      VoteExecutionInstruction.ContractId,
      VoteExecutionInstruction,
    ](
      svTaskContext.dsoStore,
      VoteExecutionInstruction.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[
      VoteExecutionInstruction.ContractId,
      VoteExecutionInstruction,
    ]] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      instruction: AssignedContract[
        VoteExecutionInstruction.ContractId,
        VoteExecutionInstruction,
      ],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val instructionType = instruction.payload.instruction

    val validatorParty = instructionType match {
      case changeWeight: EI_ValidatorLicenseChangeWeight =>
        PartyId.tryFromProtoPrimitive(changeWeight.validator)
      case withdraw: EI_ValidatorLicenseWithdraw =>
        PartyId.tryFromProtoPrimitive(withdraw.validator)
      case _ =>
        throw new IllegalArgumentException(
          s"Unsupported instruction type: ${instructionType.getClass.getName}"
        )
    }

    for {
      dsoRules <- store.getDsoRules()
      validatorLicenseOpt <- store.lookupValidatorLicenseWithOffset(validatorParty)
      result <- validatorLicenseOpt.value match {
        case Some(validatorLicense) =>
          val inputs = new EII_ValidatorLicense(validatorLicense.contractId)
          val cmd = dsoRules.exercise(
            _.exerciseDsoRules_ExecuteVoteExecutionInstruction(
              new DsoRules_ExecuteVoteExecutionInstruction(
                store.key.svParty.toProtoPrimitive,
                inputs,
                instruction.contractId,
              )
            )
          )
          svTaskContext
            .connection(SpliceLedgerConnectionPriority.Low)
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldUnit()
            .map { _ =>
              TaskSuccess(
                show"Executed vote instruction affecting validator: $validatorParty"
              )
            }
        case None =>
          Future.successful(
            TaskFailed(
              show"ValidatorLicense not found for validator: $validatorParty"
            )
          )
      }
    } yield result
  }
}
