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
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.validator.util.ValidatorUtil
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import org.lfdecentralizedtrust.splice.wallet.config.TransferPreapprovalConfig
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService.AmuletOperationDedupConfig
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class AcceptTransferPreapprovalProposalTrigger(
    override protected val context: TriggerContext,
    store: ValidatorStore,
    walletManager: UserWalletManager,
    transferPreapprovalConfig: TransferPreapprovalConfig,
    clock: Clock,
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

  override def completeTask(
      preapprovalProposal: AssignedContract[
        preapprovalCodegen.TransferPreapprovalProposal.ContractId,
        preapprovalCodegen.TransferPreapprovalProposal,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val receiverParty = PartyId.tryFromProtoPrimitive(preapprovalProposal.payload.receiver)
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
      result <- store.lookupTransferPreapprovalByReceiverPartyWithOffset(receiverParty) flatMap {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(show"TransferPreapproval for receiver $receiverParty already exists")
          )
        case QueryResult(offset, None) =>
          validatorWallet.treasury
            .enqueueAmuletOperation(
              operation,
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
