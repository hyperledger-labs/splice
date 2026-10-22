// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_MergeValidatorBlocked
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorblocked.ValidatorBlocked
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Trigger to merge multiple ValidatorBlocked contracts for the same validator */

class MergeValidatorBlockedContractsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ValidatorBlocked.ContractId,
      ValidatorBlocked,
    ](
      svTaskContext.dsoStore,
      ValidatorBlocked.COMPANION,
    )
    with SvTaskBasedTrigger[
      AssignedContract[ValidatorBlocked.ContractId, ValidatorBlocked]
    ] {

  private val store = svTaskContext.dsoStore

  private val MAX_VALIDATOR_UNPERMISSION_CONTRACTS = PageLimit.tryCreate(10)

  override def completeTaskAsDsoDelegate(
      blocked: AssignedContract[ValidatorBlocked.ContractId, ValidatorBlocked],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val participantId = blocked.payload.participantId

    for {
      supportsTrafficBasedOnboarding <- svTaskContext.packageVersionSupport
        .supportsTrafficBasedOnboarding(
          Seq(
            store.key.svParty,
            store.key.dsoParty,
          ),
          context.clock.now.minus(context.config.clockSkewAutomationDelay.asJava),
        )

      validatorBlockeds <-
        if (supportsTrafficBasedOnboarding.supported) {
          store.listValidatorBlockeds(
            participantId,
            MAX_VALIDATOR_UNPERMISSION_CONTRACTS,
          )
        } else {
          Future.successful(Seq.empty)
        }

      outcome <-
        if (validatorBlockeds.length > 1) {
          logger.info(
            s"Participant $participantId has ${validatorBlockeds.length} ValidatorBlocked contracts, hence merging them"
          )
          mergeValidatorBlockedContracts(
            participantId,
            validatorBlockeds,
            controller,
          )
        } else if (supportsTrafficBasedOnboarding.supported) {
          Future.successful(
            TaskSuccess(
              s"Only one ValidatorBlocked contract for $participantId, nothing to merge."
            )
          )
        } else {
          Future.successful(
            TaskSuccess(
              s"Skipping merging ValidatorBlocked contracts for $participantId as the package does not support it."
            )
          )
        }
    } yield outcome
  }

  private def mergeValidatorBlockedContracts(
      participantId: String,
      validatorBlockeds: Seq[
        Contract[ValidatorBlocked.ContractId, ValidatorBlocked]
      ],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      arg = new DsoRules_MergeValidatorBlocked(
        validatorBlockeds.map(_.contractId).asJava,
        Optional.of(controller),
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeValidatorBlocked(arg))
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"Merged ${validatorBlockeds.length} ValidatorBlocked contracts for participant $participantId"
    )
  }

}
