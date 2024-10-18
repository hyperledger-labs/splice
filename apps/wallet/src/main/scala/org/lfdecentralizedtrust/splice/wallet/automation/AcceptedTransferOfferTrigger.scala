// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.invalidtransferreason
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as installCodegen,
  transferoffer as transferOffersCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_CompleteAcceptedTransfer
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferoffer.AcceptedTransferOffer
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class AcceptedTransferOfferTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      transferOffersCodegen.AcceptedTransferOffer.ContractId,
      transferOffersCodegen.AcceptedTransferOffer,
    ](
      store,
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
    ) {

  override protected def extraMetricLabels = Seq(
    "party" -> store.key.endUserParty.toString
  )

  // Override the default source, as we can only auto-complete accepted offers if we are the sender
  override protected def source(implicit traceContext: TraceContext): Source[AssignedContract[
    transferOffersCodegen.AcceptedTransferOffer.ContractId,
    transferOffersCodegen.AcceptedTransferOffer,
  ], NotUsed] =
    store.multiDomainAcsStore
      .streamAssignedContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION)
      .filter(acceptedOffer =>
        acceptedOffer.payload.sender == store.key.endUserParty.toProtoPrimitive
      )

  override def completeTask(
      acceptedOffer: AssignedContract[
        transferOffersCodegen.AcceptedTransferOffer.ContractId,
        transferOffersCodegen.AcceptedTransferOffer,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val operation = new CO_CompleteAcceptedTransfer(acceptedOffer.contractId)
    treasury
      .enqueueAmuletOperation(operation)
      .flatMap {
        case failedOperation: installCodegen.amuletoperationoutcome.COO_Error =>
          failedOperation.invalidTransferReasonValue match {
            case fundsError: invalidtransferreason.ITR_InsufficientFunds =>
              val missingStr = s"(missing ${fundsError.missingAmount} CC)"
              val msg = s"Insufficient funds for the transfer $missingStr, aborting transfer offer"
              logger.info(msg)
              abortAcceptedTransferOffer(acceptedOffer, s"out of funds $missingStr")

            case otherError =>
              val msg = s"Unexpectedly failed to accepted transfer due to $otherError"
              // We report this as INTERNAL, as we don't want to retry on this.
              Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())

          }
        case _: installCodegen.amuletoperationoutcome.COO_CompleteAcceptedTransfer =>
          Future(TaskSuccess("completed accepted transfer offer"))

        case unknownResult =>
          val msg = s"Unexpected amulet-operation result $unknownResult"
          Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
      }
  }

  private def abortAcceptedTransferOffer(
      acceptedOffer: AssignedContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer],
      reason: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      cmd = install.exercise(
        _.exerciseWalletAppInstall_AcceptedTransferOffer_Abort(
          acceptedOffer.contractId,
          reason,
        )
      )
      _ <- connection
        .submit(Seq(store.key.validatorParty), Seq(store.key.endUserParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(s"aborted accepted transfer offer, $reason")
  }

}
