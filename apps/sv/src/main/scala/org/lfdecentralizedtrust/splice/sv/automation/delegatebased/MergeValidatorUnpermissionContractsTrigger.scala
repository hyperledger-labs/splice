// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_MergeValidatorUnpermission
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorunpermission.ValidatorUnpermission
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Trigger to merge multiple ValidatorUnpermission contracts for the same validator */

class MergeValidatorUnpermissionContractsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ValidatorUnpermission.ContractId,
      ValidatorUnpermission,
    ](
      svTaskContext.dsoStore,
      ValidatorUnpermission.COMPANION,
    )
    with SvTaskBasedTrigger[
      AssignedContract[ValidatorUnpermission.ContractId, ValidatorUnpermission]
    ] {

  private val store = svTaskContext.dsoStore

  private val MAX_VALIDATOR_UNPERMISSION_CONTRACTS = PageLimit.tryCreate(10)

  override def completeTaskAsDsoDelegate(
      unpermission: AssignedContract[ValidatorUnpermission.ContractId, ValidatorUnpermission],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val participantId = unpermission.payload.participantId

    for {
      supportsPermissionedSynchronizer <- svTaskContext.packageVersionSupport
        .supportsPermissionedSynchronizer(
          Seq(
            store.key.svParty,
            store.key.dsoParty,
          ),
          context.clock.now.minus(context.config.clockSkewAutomationDelay.asJava),
        )

      validatorUnpermissions <-
        if (supportsPermissionedSynchronizer.supported) {
          store.listValidatorUnpermissions(
            participantId,
            MAX_VALIDATOR_UNPERMISSION_CONTRACTS,
          )
        } else {
          Future.successful(Seq.empty)
        }

      outcome <-
        if (validatorUnpermissions.length > 1) {
          logger.info(
            s"Participant $participantId has ${validatorUnpermissions.length} ValidatorUnpermission contracts, hence merging them"
          )
          mergeValidatorUnpermissionContracts(
            participantId,
            validatorUnpermissions,
            controller,
          )
        } else if (supportsPermissionedSynchronizer.supported) {
          Future.successful(
            TaskSuccess(
              s"Only one ValidatorUnpermission contract for $participantId, nothing to merge."
            )
          )
        } else {
          Future.successful(
            TaskSuccess(
              s"Skipping merging ValidatorUnpermission contracts for $participantId as the package does not support it."
            )
          )
        }
    } yield outcome
  }

  private def mergeValidatorUnpermissionContracts(
      participantId: String,
      validatorUnpermissions: Seq[
        Contract[ValidatorUnpermission.ContractId, ValidatorUnpermission]
      ],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      arg = new DsoRules_MergeValidatorUnpermission(
        validatorUnpermissions.map(_.contractId).asJava,
        Optional.of(controller),
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeValidatorUnpermission(arg))
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"Merged ${validatorUnpermissions.length} ValidatorUnpermission contracts for participant $participantId"
    )
  }

}
