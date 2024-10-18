// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.TransferCommand
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateTransferCommandCounter
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CreateTransferCommandCounter
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TransferCommandCounterTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      TransferCommand.ContractId,
      TransferCommand,
    ](
      dsoStore,
      TransferCommand.COMPANION,
    ) {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  override def completeTask(
      transferCommand: AssignedContract[
        TransferCommand.ContractId,
        TransferCommand,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val sender = PartyId.tryFromProtoPrimitive(transferCommand.payload.sender)
    dsoStore.lookupTransferCommandCounterBySenderWithOffset(sender).flatMap {
      case QueryResult(_, Some(nonce)) =>
        Future.successful(
          TaskSuccess(
            s"TransferCommandCounter already exists for $sender with contract id ${nonce.contractId}"
          )
        )
      case QueryResult(offset, None) =>
        dsoStore.listTransferCommandCounterConfirmationBySender(svParty, sender).flatMap {
          case confirmation +: _ =>
            Future.successful(
              TaskSuccess(
                s"Confirmation for creating TransferCommandCounter already exists for $sender with contract id ${confirmation.contractId}"
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
                        new SRARC_CreateTransferCommandCounter(
                          new DsoRules_CreateTransferCommandCounter(
                            sender.toProtoPrimitive
                          )
                        )
                      ),
                    )
                  ),
                )
                .withDedup(
                  commandId = SpliceLedgerConnection.CommandId(
                    "org.lfdecentralizedtrust.splice.sv.createTransferCommandCounter",
                    Seq(svParty, dsoParty, sender),
                  ),
                  deduplicationOffset = offset,
                )
                .yieldUnit()
            } yield TaskSuccess(
              s"Confirmation created for creating TransferCommandCounter for $sender"
            )
        }
    }
  }
}
