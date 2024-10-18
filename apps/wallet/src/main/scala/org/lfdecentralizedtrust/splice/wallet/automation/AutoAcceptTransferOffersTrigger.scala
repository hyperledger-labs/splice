// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferoffer as transferOffersCodegen
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerConnection, CommandPriority}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Codec}
import org.lfdecentralizedtrust.splice.wallet.config.AutoAcceptTransfersConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.util.{TopupUtil, ValidatorTopupConfig}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class AutoAcceptTransferOffersTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
    autoAcceptTransfers: AutoAcceptTransfersConfig,
    scanConnection: ScanConnection,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    clock: Clock,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
) extends OnAssignedContractTrigger.Template[
      transferOffersCodegen.TransferOffer.ContractId,
      transferOffersCodegen.TransferOffer,
    ](store, transferOffersCodegen.TransferOffer.COMPANION) {

  override protected def extraMetricLabels = Seq(
    "party" -> store.key.endUserParty.toString
  )

  override protected def completeTask(
      transferOffer: AssignedContract[
        transferOffersCodegen.TransferOffer.ContractId,
        transferOffersCodegen.TransferOffer,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    if (
      transferOffer.contract.payload.receiver == store.key.validatorParty.toProtoPrimitive && autoAcceptTransfers.fromParties
        .contains(Codec.tryDecode(Codec.Party)(transferOffer.contract.payload.sender))
    ) {
      for {
        install <- store.getInstall()
        cmd = install.exercise(
          _.exerciseWalletAppInstall_TransferOffer_Accept(
            transferOffer.contractId
          )
        )
        // validatorTopupConfigO exist iff we are the validator party
        commandPriority <- validatorTopupConfigO match {
          case None => Future.successful(CommandPriority.Low)
          case Some(validatorTopupConfig) =>
            TopupUtil
              .hasSufficientFundsForTopup(scanConnection, store, validatorTopupConfig, clock)
              .map(if (_) CommandPriority.Low else CommandPriority.High): Future[CommandPriority]
        }
        res <- connection
          .submit(
            Seq(store.key.validatorParty),
            Seq(),
            cmd,
            priority = commandPriority,
          )
          .noDedup
          .yieldResult()
      } yield {
        TaskSuccess(s"Accepted transfer offer: $res")
      }
    } else {
      Future.successful(
        TaskSuccess(
          s"Not configured to auto-accept transfer offers from sender: ${transferOffer.contract.payload.sender} "
        )
      )
    }
  }
}

object AutoAcceptTransferOffersTrigger {
  final type Task = AssignedContract[
    transferOffersCodegen.TransferOffer.ContractId,
    transferOffersCodegen.TransferOffer,
  ]
}
