package com.daml.network.wallet.automation

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc.coin.invalidtransferreason
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_CompleteAcceptedTransfer
import com.daml.network.codegen.java.cn.wallet.transferoffer.AcceptedTransferOffer
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class AcceptedTransferOfferTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      transferOffersCodegen.AcceptedTransferOffer.Contract,
      transferOffersCodegen.AcceptedTransferOffer.ContractId,
      transferOffersCodegen.AcceptedTransferOffer,
    ](store.acs, transferOffersCodegen.AcceptedTransferOffer.COMPANION) {

  // Override the default source, as we can only auto-complete accepted offers if we are the sender
  override protected val source: Source[JavaContract[
    transferOffersCodegen.AcceptedTransferOffer.ContractId,
    transferOffersCodegen.AcceptedTransferOffer,
  ], NotUsed] =
    store.acs
      .streamContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION)
      .filter(acceptedOffer =>
        acceptedOffer.payload.sender == store.key.endUserParty.toProtoPrimitive
      )

  override def completeTask(
      acceptedOffer: JavaContract[
        transferOffersCodegen.AcceptedTransferOffer.ContractId,
        transferOffersCodegen.AcceptedTransferOffer,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val operation = new CO_CompleteAcceptedTransfer(acceptedOffer.contractId)
    treasury
      .enqueueCoinOperation(operation)
      .flatMap {
        case failedOperation: installCodegen.coinoperationoutcome.COO_Error =>
          failedOperation.invalidTransferReasonValue match {
            case fundsError: invalidtransferreason.ITR_InsufficientFunds =>
              val missingStr = s"(missing ${fundsError.missingQuantity} CC)"
              val msg = s"Insufficient funds for the transfer $missingStr, aborting transfer offer"
              logger.info(msg)
              abortAcceptedTransferOffer(acceptedOffer, s"out of funds $missingStr")

            case otherError =>
              val msg = s"Unexpectedly failed to accepted transfer due to $otherError"
              // We report this as INTERNAL, as we don't want to retry on this.
              Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())

          }
        case _: installCodegen.coinoperationoutcome.COO_CompleteAcceptedTransfer =>
          Future(TaskSuccess("completed accepted transfer offer"))

        case unknownResult =>
          val msg = s"Unexpected coin-operation result $unknownResult"
          Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
      }
  }

  private def abortAcceptedTransferOffer(
      acceptedOffer: JavaContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer],
      reason: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      domainId <- store.domains.getUniqueDomainId()
      cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Abort(
        acceptedOffer.contractId
      )
      _ <- connection
        .submitWithResultNoDedup(
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
          domainId,
        )
    } yield TaskSuccess(s"aborted accepted transfer offer, $reason")
  }

}
