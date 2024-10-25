// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import cats.syntax.traverse.*
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.InputAmulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  TransferCommand,
  TransferCommand_Send,
}
import org.lfdecentralizedtrust.splice.environment.RetryFor
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class TransferCommandSendTrigger(
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
        amulets <- store.listAmuletsByOwner(sender)
        featuredAppRight <- transferPreapprovalO
          .traverse { preapproval =>
            val provider = PartyId.tryFromProtoPrimitive(preapproval.payload.provider)
            store.lookupFeaturedAppRight(provider)
          }
          .map(_.flatten)
        transferCommandNonce <- context.retryProvider.retry(
          RetryFor.Automation,
          "wait_for_transfer_command_counter",
          s"wait for TransferCommandCounter for $sender",
          store
            .lookupTransferCommandCounterBySender(sender)
            .map(
              _.getOrElse(
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"No TransferCommandCounter for $sender yet, waiting for SV automation to create it"
                  )
                  .asRuntimeException
              )
            ),
          logger,
        )
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
            new TransferCommand_Send(
              new PaymentTransferContext(
                amuletRules.contractId,
                transferContext,
              ),
              amulets.map[TransferInput](a => new InputAmulet(a.contractId)).asJava,
              transferPreapprovalO.map(_.contractId).toJava,
              transferCommandNonce.contractId,
            ),
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
  }
}
