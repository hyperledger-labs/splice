// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.invalidtransferreason
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_AcceptTransferPreapprovalProposal
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as installCodegen,
  transferpreapproval as preapprovalCodegen,
}
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.validator.util.ValidatorUtil
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import org.lfdecentralizedtrust.splice.wallet.config.TransferPreapprovalConfig
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService.AmuletOperationDedupConfig
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.wallet.util.{TopupUtil, ValidatorTopupConfig}

import scala.concurrent.{ExecutionContext, Future}

class AcceptTransferPreapprovalProposalTrigger(
    override protected val context: TriggerContext,
    store: ValidatorStore,
    walletManager: UserWalletManager,
    transferPreapprovalConfig: TransferPreapprovalConfig,
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    globalSynchronizerAlias: SynchronizerAlias,
    scanConnection: BftScanConnection,
    validatorTopupConfig: ValidatorTopupConfig,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      preapprovalCodegen.TransferPreapprovalProposal.ContractId,
      preapprovalCodegen.TransferPreapprovalProposal,
    ](
      store,
      preapprovalCodegen.TransferPreapprovalProposal.COMPANION,
    ) {

  def withValidatedReceiver(
      receiver: PartyId
  )(f: => Future[TaskOutcome])(implicit tc: TraceContext): Future[TaskOutcome] =
    if (transferPreapprovalConfig.acceptNonHostedPreapprovalProposals) {
      f
    } else {
      for {
        participantId <- participantAdminConnection.getParticipantId()
        synchronizerId <- participantAdminConnection.getSynchronizerId(globalSynchronizerAlias)
        partyToParticipants <- participantAdminConnection.getPartyToParticipant(
          synchronizerId,
          receiver,
          topologySnapshot = TopologySnapshot.Effective,
        )
        outcome <-
          if (
            partyToParticipants.mapping.participants.exists(p => p.participantId == participantId)
          ) {
            f
          } else {
            Future.successful(
              TaskSuccess(
                s"Skipping TransferPreapproval as receiver $receiver is not hosted on our participant $participantId, hosting participants: ${partyToParticipants.mapping.participants}"
              )
            )
          }
      } yield outcome
    }

  override def completeTask(
      preapprovalProposal: AssignedContract[
        preapprovalCodegen.TransferPreapprovalProposal.ContractId,
        preapprovalCodegen.TransferPreapprovalProposal,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val receiverParty = PartyId.tryFromProtoPrimitive(preapprovalProposal.payload.receiver)
    withValidatedReceiver(receiverParty) {
      val operation = new CO_AcceptTransferPreapprovalProposal(
        preapprovalProposal.contractId,
        clock.now.plus(transferPreapprovalConfig.preapprovalLifetime.asJava).toInstant,
      )
      val commandId = SpliceLedgerConnection.CommandId(
        "org.lfdecentralizedtrust.splice.validator.acceptTransferPreapprovalProposal",
        Seq(
          receiverParty,
          store.key.validatorParty,
        ),
      )
      for {
        validatorWallet <- ValidatorUtil.getValidatorWallet(store, walletManager)
        commandPriority <- TopupUtil.commandPriorityForTopupFunds( // new
          scanConnection,
          validatorWallet.store,
          validatorTopupConfig,
          clock,
        )
        result <- store.lookupTransferPreapprovalByReceiverPartyWithOffset(receiverParty) flatMap {
          // Expired pre-approvals are ignored: the receiver cannot be paid through them anymore
          // and they may stick around for a while until the SV automation archives them.
          case QueryResult(_, Some(existing))
              if existing.payload.expiresAt.isAfter(clock.now.toInstant) =>
            Future.successful(
              TaskSuccess(show"TransferPreapproval for receiver $receiverParty already exists")
            )
          case QueryResult(offset, existing) =>
            existing.foreach(expired =>
              logger.info(
                s"Accepting proposal for receiver $receiverParty as its existing TransferPreapproval " +
                  s"${expired.contractId.contractId} expired at ${expired.payload.expiresAt}"
              )
            )
            validatorWallet.treasury
              .enqueueAmuletOperation(
                operation,
                priority = commandPriority,
                dedup = Some(AmuletOperationDedupConfig(commandId, DedupOffset(offset))).filter(_ =>
                  transferPreapprovalConfig.proposalAcceptanceDeduplication
                ),
              )
              .flatMap {
                case failedOperation: installCodegen.amuletoperationoutcome.COO_Error =>
                  failedOperation.invalidTransferReasonValue match {
                    case fundsError: invalidtransferreason.ITR_InsufficientFunds =>
                      val missingStr = s"(missing ${fundsError.missingAmount} CC)"
                      val msg = s"Insufficient funds to create transfer pre-approval $missingStr"
                      logger.info(msg)
                      Future.failed(Status.ABORTED.withDescription(msg).asRuntimeException())

                    case otherError =>
                      val msg =
                        s"Unexpectedly failed to create transfer pre-approval due to $otherError"
                      // We report this as INTERNAL, as we don't want to retry on this.
                      Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())

                  }
                case coo: installCodegen.amuletoperationoutcome.COO_AcceptTransferPreapprovalProposal =>
                  Future(
                    TaskSuccess(
                      s"Created transfer pre-approval for $receiverParty with contract ID ${coo.contractIdValue}"
                    )
                  )

                case unknownResult =>
                  val msg = s"Unexpected amulet-operation result $unknownResult"
                  Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
              } recoverWith {
              case ex: StatusRuntimeException
                  if ex.getStatus.getCode == Status.Code.ALREADY_EXISTS && ex.getStatus.getDescription
                    .contains("DUPLICATE_COMMAND") =>
                Future.successful(TaskSuccess(s"${ex.getMessage}"))
            }
        }
      } yield result
    }
  }
}
