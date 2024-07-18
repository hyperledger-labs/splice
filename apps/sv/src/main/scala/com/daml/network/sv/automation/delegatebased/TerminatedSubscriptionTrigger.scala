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
import com.daml.network.codegen.java.splice.wallet.subscriptions as subsCodegen
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TerminatedSubscriptionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      subsCodegen.TerminatedSubscription.ContractId,
      subsCodegen.TerminatedSubscription,
    ](svTaskContext.dsoStore, subsCodegen.TerminatedSubscription.COMPANION)
    with SvTaskBasedTrigger[AssignedContract[
      subsCodegen.TerminatedSubscription.ContractId,
      subsCodegen.TerminatedSubscription,
    ]] {

  private val dsoParty = svTaskContext.dsoStore.key.dsoParty
  private val svParty = svTaskContext.dsoStore.key.svParty

  override def completeTaskAsDsoDelegate(
      task: AssignedContract[
        subsCodegen.TerminatedSubscription.ContractId,
        subsCodegen.TerminatedSubscription,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      ansEntryContextO <- svTaskContext.dsoStore.lookupAnsEntryContext(
        task.contract.payload.reference
      )
      dsoRules <- svTaskContext.dsoStore.getDsoRules()
      _ <- ansEntryContextO match {
        case None =>
          throw Status.NOT_FOUND
            .withDescription(
              s"No associated ans entry context for reference ${task.contract.payload.reference} was found."
            )
            .asRuntimeException()
        case Some(ansEntryContext) =>
          for {
            _ <- svTaskContext.connection
              .submit(
                Seq(svParty),
                Seq(dsoParty),
                dsoRules.exercise(
                  _.exerciseDsoRules_TerminateSubscription(
                    ansEntryContext.contractId,
                    task.contractId,
                  )
                ),
              )
              .noDedup
              .yieldUnit()
          } yield ()
      }
    } yield TaskSuccess(
      "Archived AnsEntryContext because corresponding subscription got terminated"
    )
  }
}
