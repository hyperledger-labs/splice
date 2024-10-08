// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.splice.amuletrules.{
  TransferPreapproval2,
  invalidtransferreason,
}
import com.daml.network.codegen.java.splice.wallet.install.amuletoperation.CO_RenewTransferPreapproval
import com.daml.network.codegen.java.splice.wallet.install.amuletoperationoutcome
import com.daml.network.util.AssignedContract
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.config.TransferPreapprovalConfig
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class RenewTransferPreapprovalTrigger(
    override protected val context: TriggerContext,
    store: ValidatorStore,
    walletManager: UserWalletManager,
    transferPreapprovalConfig: TransferPreapprovalConfig,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends MultiDomainExpiredContractTrigger.Template[
      TransferPreapproval2.ContractId,
      TransferPreapproval2,
    ](
      store.multiDomainAcsStore,
      store.listExpiringTransferPreapprovals(transferPreapprovalConfig.renewalDuration),
      TransferPreapproval2.COMPANION,
    ) {

  override def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[TransferPreapproval2.ContractId, TransferPreapproval2]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      validatorWallet <- ValidatorUtil.getValidatorWallet(store, walletManager)
      expiringPreapproval = task.work.contract
      newExpiresAt = expiringPreapproval.payload.expiresAt.plus(
        transferPreapprovalConfig.preapprovalLifetime.asJava
      )
      outcome <- validatorWallet.treasury
        .enqueueAmuletOperation(
          new CO_RenewTransferPreapproval(
            expiringPreapproval.contractId,
            newExpiresAt,
          )
        )
        .flatMap {
          case successResult: amuletoperationoutcome.COO_RenewTransferPreapproval =>
            Future.successful(
              TaskSuccess(
                s"Renewed transfer pre-approval for party ${expiringPreapproval.payload.receiver} with new expiry at $newExpiresAt: $successResult"
              )
            )
          case failedOperation: amuletoperationoutcome.COO_Error =>
            failedOperation.invalidTransferReasonValue match {
              case fundsError: invalidtransferreason.ITR_InsufficientFunds =>
                val missingStr = s"(missing ${fundsError.missingAmount} CC)"
                val msg = s"Insufficient funds for the transfer pre-approval renewal $missingStr"
                logger.info(msg)
                Future.failed(Status.ABORTED.withDescription(msg).asRuntimeException())

              case otherError =>
                val msg =
                  s"Unexpectedly failed to complete transfer pre-approval renewal for ${expiringPreapproval.payload.receiver} due to $otherError"
                // We report this as INTERNAL, as we don't want to retry on this.
                Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())

            }
          case unknownResult =>
            val msg = s"Unexpected amulet-operation result $unknownResult"
            Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
        }
    } yield outcome
  }
}
