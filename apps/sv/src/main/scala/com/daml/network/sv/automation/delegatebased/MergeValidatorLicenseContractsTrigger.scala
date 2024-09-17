// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dsorules.DsoRules_MergeValidatorLicense
import com.daml.network.codegen.java.splice.validatorlicense.ValidatorLicense
import com.daml.network.environment.PackageIdResolver
import com.daml.network.store.PageLimit
import com.daml.network.util.{AssignedContract, Contract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Trigger to merge multiple ValidatorLicenseContracts for the same validator.
  */
class MergeValidatorLicenseContractsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[ValidatorLicense.ContractId, ValidatorLicense](
      svTaskContext.dsoStore,
      ValidatorLicense.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[ValidatorLicense.ContractId, ValidatorLicense]] {

  private val store = svTaskContext.dsoStore

  private val MAX_VALIDATOR_LICENSE_CONTRACTS = PageLimit.tryCreate(10)

  override def completeTaskAsDsoDelegate(
      validatorLicense: AssignedContract[ValidatorLicense.ContractId, ValidatorLicense]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val validator = validatorLicense.payload.validator
    for {
      amuletRules <- store.getAssignedAmuletRules()
      supportsPruneAmuletConfigSchedule = PackageIdResolver.supportsMergeDuplicatedValidatorLicense(
        context.clock.now.minus(context.config.clockSkewAutomationDelay.asJava),
        amuletRules.payload,
      )
      validatorLicenses <-
        if (supportsPruneAmuletConfigSchedule) {
          store.listValidatorLicensePerValidator(
            validator,
            MAX_VALIDATOR_LICENSE_CONTRACTS,
          )
        } else {
          Future.successful(Seq.empty)
        }
      outcome <-
        if (validatorLicenses.length > 1) {
          logger.warn(
            s"Validator $validator has ${validatorLicenses.length} Validator License contracts."
          )
          mergeValidatorLicenseContracts(validator, validatorLicenses)
        } else if (supportsPruneAmuletConfigSchedule) {
          Future.successful(
            TaskSuccess(s"Only one Validator License contract for $validator, nothing to merge.")
          )
        } else {
          Future.successful(
            TaskSuccess(
              s"Skipping merging Validator License contracts for $validator as the package does not support it."
            )
          )
        }
    } yield outcome
  }

  private def mergeValidatorLicenseContracts(
      validator: String,
      validatorLicenses: Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      arg = new DsoRules_MergeValidatorLicense(
        validatorLicenses.map(_.contractId).asJava
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeValidatorLicense(arg))
      _ <- svTaskContext.connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"Merged ${validatorLicenses.length} ValidatorLicense contracts for $validator"
    )
  }

}
