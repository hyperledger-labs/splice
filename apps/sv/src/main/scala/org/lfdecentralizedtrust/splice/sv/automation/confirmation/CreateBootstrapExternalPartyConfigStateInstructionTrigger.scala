// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateBootstrapExternalPartyConfigStateInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CreateBootstrapExternalPartyConfigStateInstruction
import org.lfdecentralizedtrust.splice.environment.{PackageVersionSupport, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class CreateBootstrapExternalPartyConfigStateInstructionTrigger(
    override protected val context: TriggerContext,
    packageVersionSupport: PackageVersionSupport,
    dsoStore: SvDsoStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Unit] {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Unit]] = {
    for {
      configStateExists <- dsoStore.existsExternalPartyConfigStateWithOffset()
      supports24hSubmissionDelay <- packageVersionSupport
        .supports24hSubmissionDelayDsoGovernance(
          Seq(dsoStore.key.dsoParty, dsoStore.key.svParty),
          context.clock.now,
        )
      confirmations <- dsoStore.listCreateBootstrapExternalPartyConfigStateInstructionConfirmation(
        svParty
      )
    } yield Seq(()).filter(_ =>
      supports24hSubmissionDelay.supported && !configStateExists.value && confirmations.isEmpty
    )
  }

  override def completeTask(
      task: Unit
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    dsoStore.existsExternalPartyConfigStateWithOffset().flatMap {
      case QueryResult(_, true) =>
        Future.successful(
          TaskSuccess(
            s"ExternalPartyConfigState already exists"
          )
        )
      case QueryResult(offset, false) =>
        dsoStore.listExternalPartyAmuletRulesConfirmation(svParty).flatMap {
          case _ +: _ =>
            Future.successful(
              TaskSuccess(
                s"Confirmation for creating BootstrapExternalPartyConfigStateInstruction already exists"
              )
            )
          case _ =>
            for {
              dsoRules <- dsoStore.getDsoRules()
              _ <- connection
                .submit(
                  actAs = Seq(svParty),
                  readAs = Seq(dsoParty),
                  update = dsoRules.exercise(
                    _.exerciseDsoRules_ConfirmAction(
                      svParty.toProtoPrimitive,
                      new ARC_DsoRules(
                        new SRARC_CreateBootstrapExternalPartyConfigStateInstruction(
                          new DsoRules_CreateBootstrapExternalPartyConfigStateInstruction(
                          )
                        )
                      ),
                    )
                  ),
                )
                .withDedup(
                  commandId = SpliceLedgerConnection.CommandId(
                    "org.lfdecentralizedtrust.splice.sv.createExternalPartyConfigStateInstruction",
                    Seq(svParty, dsoParty),
                  ),
                  deduplicationOffset = offset,
                )
                .yieldUnit()
            } yield TaskSuccess(
              s"Confirmation created for creating BootstrapExternalPartyConfigStateInstruction"
            )
        }
    }

  override def isStaleTask(task: Unit)(implicit tc: TraceContext): Future[Boolean] =
    // complete task already handles everything
    Future.successful(false)
}
