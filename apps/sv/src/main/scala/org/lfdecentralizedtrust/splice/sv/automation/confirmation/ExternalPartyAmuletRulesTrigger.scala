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
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateExternalPartyAmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CreateExternalPartyAmuletRules
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExternalPartyAmuletRulesTrigger(
    override protected val context: TriggerContext,
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
    val now = context.clock.now
    for {
      amuletRules <- dsoStore.getAmuletRules()
      tasks <-
        if (PackageIdResolver.supportsExternalPartyAmuletRules(now, amuletRules.payload)) {
          for {
            rulesO <- dsoStore.lookupExternalPartyAmuletRules()
            confirmations <- dsoStore.listExternalPartyAmuletRulesConfirmation(svParty)
          } yield Seq(()).filter(_ => rulesO.value.isEmpty && confirmations.isEmpty)
        } else {
          Future.successful(Seq.empty)
        }
    } yield tasks
  }

  override def completeTask(
      task: Unit
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    dsoStore.lookupExternalPartyAmuletRules().flatMap {
      case QueryResult(_, Some(nonce)) =>
        Future.successful(
          TaskSuccess(
            s"ExternalPartyAmuletRules already exists with contract id ${nonce.contractId}"
          )
        )
      case QueryResult(offset, None) =>
        dsoStore.listExternalPartyAmuletRulesConfirmation(svParty).flatMap {
          case confirmation +: _ =>
            Future.successful(
              TaskSuccess(
                s"Confirmation for creating ExternalPartyAmuletRules already exists with contract id ${confirmation.contractId}"
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
                        new SRARC_CreateExternalPartyAmuletRules(
                          new DsoRules_CreateExternalPartyAmuletRules(
                          )
                        )
                      ),
                    )
                  ),
                )
                .withDedup(
                  commandId = SpliceLedgerConnection.CommandId(
                    "org.lfdecentralizedtrust.splice.sv.createExternalPartyAmuletRules",
                    Seq(svParty, dsoParty),
                  ),
                  deduplicationOffset = offset,
                )
                .yieldUnit()
            } yield TaskSuccess(
              s"Confirmation created for creating ExternalPartyAmuletRules"
            )
        }
    }
  }

  override def isStaleTask(task: Unit)(implicit tc: TraceContext) =
    // completeTask already checks all necessary conditions so no need to do anything here.
    Future.successful(false)
}
