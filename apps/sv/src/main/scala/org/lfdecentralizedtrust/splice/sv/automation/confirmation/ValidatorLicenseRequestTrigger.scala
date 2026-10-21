// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_GrantValidatorLicense,
  SRARC_RejectValidatorLicense,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_GrantValidatorLicense,
  DsoRules_RejectValidatorLicense,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicenseRequest
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{AssignedContract, SwitchOverTimes}

import scala.concurrent.{ExecutionContext, Future}

class ValidatorLicenseRequestTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ValidatorLicenseRequest.ContractId,
      ValidatorLicenseRequest,
    ](
      dsoStore,
      ValidatorLicenseRequest.COMPANION,
    ) {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  override def completeTask(
      request: AssignedContract[
        ValidatorLicenseRequest.ContractId,
        ValidatorLicenseRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val reqCid = request.contractId
    val validatorParty = PartyId.tryFromProtoPrimitive(request.payload.validator)

    for {
      dsoRules <- dsoStore.getDsoRules()

      // Note: Receiving a ValidatorLicenseRequest implies that the corresponding PartyToParticipant mapping and the ParticipantSynchronizerPermission is already available in the Participant, so we avoid checking them again here.
      outcome <-
        if (!SwitchOverTimes.permissionedSynchronizerScheduled(dsoRules.payload)) {
          Future.successful(
            TaskSuccess("Skipped because permissionedSynchronizer switchover has not occurred")
          )
        } else {
          confirm(reqCid, validatorParty, dsoRules)
        }

    } yield outcome
  }

  private def confirm(
      reqCid: ValidatorLicenseRequest.ContractId,
      validatorParty: PartyId,
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {

    for {
      licenseResult <- dsoStore.lookupValidatorLicenseWithOffset(validatorParty)

      action = licenseResult.value match {
        case Some(_) =>
          new ARC_DsoRules(
            new SRARC_RejectValidatorLicense(
              new DsoRules_RejectValidatorLicense(reqCid)
            )
          )
        case None =>
          new ARC_DsoRules(
            new SRARC_GrantValidatorLicense(
              new DsoRules_GrantValidatorLicense(reqCid)
            )
          )
      }

      confirmationResult <- dsoStore.lookupConfirmationByActionWithOffset(svParty, action)

      outcome <- confirmationResult.value match {
        case Some(_) =>
          Future.successful(
            TaskSuccess(
              s"Skipping as confirmation from $svParty is already created for action on $validatorParty"
            )
          )
        case None =>
          val minOffset = math.min(licenseResult.offset, confirmationResult.offset)
          val cmd = dsoRules.exercise(
            _.exerciseDsoRules_ConfirmAction(
              svParty.toProtoPrimitive,
              action,
            )
          )

          connection
            .submit(
              actAs = Seq(svParty),
              readAs = Seq(dsoParty),
              update = cmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "org.lfdecentralizedtrust.splice.sv.confirmValidatorLicenseRequest",
                Seq(svParty, dsoParty),
                reqCid.contractId,
              ),
              deduplicationConfig = DedupOffset(minOffset),
            )
            .yieldResult()
            .map { _ =>
              TaskSuccess(
                s"Created confirmation for action on ValidatorLicenseRequest for $validatorParty"
              )
            }
      }
    } yield outcome
  }
}
