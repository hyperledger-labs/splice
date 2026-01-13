// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import cats.syntax.traverse.*
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.InputAppRewardCoupon
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  TransferCommand
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.environment.{RetryFor, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.{ContractWithState, AssignedContract, SpliceUtil}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.wallet.ExternalPartyWalletManager
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
    scanConnection: BftScanConnection,
    store: ValidatorStore,
    walletManager: ExternalPartyWalletManager,
    ledgerConnection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[TransferCommand.ContractId, TransferCommand](
      store,
      TransferCommand.COMPANION,
    ) {

  override def completeTask(
      transferCommand: AssignedContract[TransferCommand.ContractId, TransferCommand]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val now = context.clock.now
    val sender = PartyId.tryFromProtoPrimitive(transferCommand.payload.sender)
    val receiver = PartyId.tryFromProtoPrimitive(transferCommand.payload.receiver)
    if (CantonTimestamp.tryFromInstant(transferCommand.payload.expiresAt) < now) {
      Future.successful(TaskSuccess("TransferCommand is expired, skipping"))
    } else {
      walletManager.lookupExternalPartyWallet(sender) match {
        case None =>
          Future.successful(
            TaskSuccess(
              s"Sender of transfer command $sender is not an onboarded external party, skipping."
            )
          )
        case Some(wallet) =>
          for {
            transferPreapprovalO <- scanConnection.lookupTransferPreapprovalByParty(receiver)
            (openRounds, issuingMiningRounds) <- scanConnection.getOpenAndIssuingMiningRounds()
            openRound = SpliceUtil.selectLatestOpenMiningRound(now, openRounds)
            configUsd = openRound.payload.transferConfigUsd
            maxNumInputs = configUsd.maxNumInputs.intValue()
            amulets <- wallet.store.listSortedAmuletsAndQuantity(
              PageLimit.tryCreate(maxNumInputs)
            )
            openIssuingRounds = issuingMiningRounds.filter(c =>
              c.payload.opensAt.isBefore(now.toInstant)
            )
            issuingRoundsMap = openIssuingRounds.view.map { r =>
              val imr = r.payload
              (imr.round, imr)
            }.toMap
            appRewards <- wallet.store.listSortedAppRewards(
              issuingRoundsMap,
              PageLimit.tryCreate(maxNumInputs),
            )
            inputs: Seq[TransferInput] = (amulets.map(_._2) ++ appRewards.map(a =>
              new InputAppRewardCoupon(a._1.contractId)
            )).take(maxNumInputs)
            featuredAppRight <- transferPreapprovalO
              .traverse { preapproval =>
                val provider = PartyId.tryFromProtoPrimitive(preapproval.payload.provider)
                scanConnection.lookupFeaturedAppRight(provider)
              }
              .map(_.flatten)
            transferCommandNonce <- context.retryProvider.retry(
              RetryFor.Automation,
              "wait_for_transfer_command_counter",
              s"wait for TransferCommandCounter for $sender",
              wallet.store
                .lookupTransferCommandCounter()
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
            amuletRules <- scanConnection.getAmuletRulesWithState()
            rewardInputRounds = appRewards.map(_._1.payload.round).toSet
            transferContext = new TransferContext(
              openRound.contractId,
              openIssuingRounds.view
                // only provide rounds that are actually used in transfer context to avoid unnecessary fetching.
                .filter(r => rewardInputRounds.contains(r.payload.round))
                .map(r =>
                  (
                    r.payload.round,
                    r.contractId,
                  )
                )
                .toMap[Round, IssuingMiningRound.ContractId]
                .asJava,
              // validator right contracts are not visible to DSO
              // so cannot use validator rewards. That's fine
              // as we also don't expect the external party to have any
              Map.empty.asJava,
              featuredAppRight.map(_.contractId).toJava,
            )
            cmd = transferCommand.exercise(
              _.exerciseTransferCommand_Send(
                new PaymentTransferContext(
                  amuletRules.contractId,
                  transferContext,
                ),
                inputs.asJava,
                transferPreapprovalO.map(_.contractId).toJava,
                transferCommandNonce.contractId,
              )
            )
            result <- ledgerConnection
              .submit(
                Seq(store.key.validatorParty),
                Seq(sender),
                cmd,
              )
              .withDisclosedContracts(
                ledgerConnection
                  .disclosedContracts(
                    amuletRules,
                    (openRound +: transferPreapprovalO.toList)*
                  )
                  .addAll(openIssuingRounds)
                  // copy paste the state from amulet rules as we don't currently expose it in scan for featured
                  // app rights.
                  .addAll(featuredAppRight.map(ContractWithState(_, amuletRules.state)).toList)
              )
              .noDedup
              .yieldResult()
          } yield TaskSuccess(s"Completed TransferCommand with: $result")
      }
    }
  }
}
