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
import com.daml.network.codegen.java.splice.amuletrules.transferinput.InputAmulet
import com.daml.network.codegen.java.splice.amuletrules.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
}
import com.daml.network.codegen.java.splice.externalpartyamuletrules.TransferCommand
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class TransferCommandsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[TransferCommand.ContractId, TransferCommand](
      svTaskContext.dsoStore,
      TransferCommand.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[TransferCommand.ContractId, TransferCommand]] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      transferCommand: AssignedContract[TransferCommand.ContractId, TransferCommand]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val now = context.clock.now
    val sender = PartyId.tryFromProtoPrimitive(transferCommand.payload.sender)
    val receiver = PartyId.tryFromProtoPrimitive(transferCommand.payload.receiver)
    if (CantonTimestamp.tryFromInstant(transferCommand.payload.expiresAt) < now) {
      Future.successful(TaskSuccess("TransferCommand is expired, skipping"))
    } else {
      for {
        transferPreapprovalO <- store.lookupTransferPreapprovalByParty(receiver)
        r <- transferPreapprovalO match {
          case None =>
            // TODO(#15159) Archive the contract here.
            Future.successful(
              TaskSuccess(
                s"No TransferPreapproval contract for receiver ${transferCommand.payload.receiver}"
              )
            )
          case Some(transferPreapproval) =>
            val provider = PartyId.tryFromProtoPrimitive(transferPreapproval.payload.provider)
            for {
              amulets <- store.listAmuletsByOwner(sender)
              featuredAppRight <- store.lookupFeaturedAppRight(provider)
              dsoRules <- store.getDsoRules()
              amuletRules <- store.getAmuletRules()
              openRound <- store.getLatestUsableOpenMiningRound(now)
              transferContext = new TransferContext(
                openRound.contractId,
                // TODO(#15160) Include app rewards
                Map.empty.asJava,
                // validator right contracts are not visible to DSO
                // so cannot use validator rewards. That's fine
                // as we also don't expect the external party to have any
                Map.empty.asJava,
                featuredAppRight.map(_.contractId).toJava,
              )
              cmd = dsoRules.exercise(
                _.exerciseDsoRules_TransferCommand_Send(
                  transferCommand.contractId,
                  new PaymentTransferContext(
                    amuletRules.contractId,
                    transferContext,
                  ),
                  amulets.map[TransferInput](a => new InputAmulet(a.contractId)).asJava,
                  transferPreapproval.contractId,
                )
              )
              result <- svTaskContext.connection
                .submit(
                  Seq(store.key.svParty),
                  Seq(store.key.dsoParty),
                  cmd,
                )
                .noDedup
                .yieldResult()
            } yield TaskSuccess(s"Completed TransferCommand with: $result")
        }
      } yield r
    }
  }
}
