package com.daml.network.wallet.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cc.coin.invalidtransferreason
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_CompleteAcceptedTransfer
import com.daml.network.codegen.java.cn.wallet.transferoffer.{AcceptedTransferOffer}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class UserWalletAutomationService(
    store: UserWalletStore,
    treasury: TreasuryService,
    ledgerClient: CoinLedgerClient,
    automationConfig: AutomationConfig,
    clock: Clock,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clock, retryProvider) {

  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      s"UserWalletStore(${store.key.endUserName})",
      store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )
  registerNewStyleTrigger(new ExpireTransferOfferTrigger(triggerContext, store, connection))
  registerNewStyleTrigger(new ExpireAcceptedTransferOfferTrigger(triggerContext, store, connection))
  registerNewStyleTrigger(new SubscriptionReadyForPaymentTrigger(triggerContext, store, treasury))

  private def abortAcceptedTransferOffer(
      acceptedOffer: JavaContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer],
      reason: String,
  )(implicit tc: TraceContext): Future[Either[String, String]] = {

    for {
      install <- store.getInstall()
      cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Abort(
        acceptedOffer.contractId
      )
      res <- connection
        .submitWithResultNoDedup(
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
        )
        .map(_ => s"aborted accepted transfer offer, $reason")
    } yield Right(res)
  }

  /** Tries to complete an accepted offer (and with that archive it).
    * If it fails due to insufficient funds - archives the accepted offer without completing it.
    */
  private def completeOrAbortAcceptedTransferOffer(
      acceptedOffer: JavaContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer]
  )(implicit tc: TraceContext): Future[Either[String, String]] = {
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
            case otherError: invalidtransferreason.ITR_Other => {
              val msg =
                show"Failed making a transfer with accepted offer ${acceptedOffer}: ${otherError.description.singleQuoted}"
              logger.info(
                msg
              ) // We report this only at an info level, because it will be retried automatically
              Future(Left(msg))
            }
            case unknown =>
              val msg = s"Unexpected error: ${unknown}"
              logger.warn(
                msg
              )
              Future(Left(msg))
          }
        case _ =>
          val msg = s"Completed a transfer with accepted offer ${acceptedOffer}"
          logger.info(msg)
          Future(Right(msg))
      }
  }

  private def completeOrArchiveAcceptedTransferOfferIfSender(
      acceptedOffer: JavaContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer]
  )(implicit tc: TraceContext): Future[Either[String, String]] = {
    store.key.endUserParty.toProtoPrimitive match {
      case acceptedOffer.payload.sender =>
        logger.info("Transfer offer accepted, trying to complete the transfer...")
        completeOrAbortAcceptedTransferOffer(acceptedOffer)
      case acceptedOffer.payload.receiver =>
        val msg = "AcceptedTransferOffer ignored as the receiver"
        logger.debug(msg)
        Future(Right(msg))
      case _ =>
        val msg =
          s"end user party (${store.key.endUserParty}) is unexpectedly neither the sender (${acceptedOffer.payload.sender}) nor the receiver (${acceptedOffer.payload.receiver})"
        logger.error(
          msg
        )
        Future(Left(msg))
    }
  }

  registerTrigger(
    "Complete accepted transfer offers",
    store.acs.streamContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION),
  )((acceptedOffer, logger) => { implicit traceContext =>
    logger.info(s"Ingested new transfer offer: ${acceptedOffer}")
    completeOrArchiveAcceptedTransferOfferIfSender(acceptedOffer).map(_ match {
      case Left(err) =>
        // A retryable error has occurred (hence the accepted transfer offer has not been aborted) -
        // so we throw an UNAVAILABLE exception here, and let automation retry the action.
        throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription(err))
      case Right(msg) => Some(msg)
    })
  })

}
