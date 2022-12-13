package com.daml.network.wallet.automation

import akka.stream.Materializer
import cats.syntax.traverse.*
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_CompleteAcceptedTransfer
import com.daml.network.codegen.java.cn.wallet.transferoffer.{AcceptedTransferOffer, TransferOffer}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class UserWalletAutomationService(
    store: UserWalletStore,
    treasury: TreasuryService,
    ledgerClient: CoinLedgerClient,
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clockConfig, retryProvider) {

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

  private def abortAcceptedTransferOffer(
      acceptedOffer: JavaContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer]
  )(implicit tc: TraceContext): Future[Either[String, String]] = {

    for {
      install <- getInstall
      cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Abort(
        acceptedOffer.contractId
      )
      res <- connection
        .submitWithResultNoDedup(
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
        )
        .map(_ => "Aborted accepted transfer offer")
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
          if (failedOperation.stringValue.startsWith("Input to output+fee quantity balance")) {
            // Insufficient funds - abort the offer
            // TODO(#1799): Push this string comparison down to daml, and make COO_Error carry a variant
            abortAcceptedTransferOffer(acceptedOffer)
          } else {
            val msg =
              show"Failed making a transfer with accepted offer ${acceptedOffer}: ${failedOperation.stringValue.singleQuoted}"
            logger.info(
              msg
            ) // We report this only at an info level, because it will be retried automatically
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

  registerPollingTrigger(
    "TryExpiringTransferOffers",
    automationConfig.pollingInterval,
    connection,
  )((now, logger) => { implicit traceContext =>
    {
      logger.debug("Looking for transfer offers to expire...")
      store.acs
        .listContracts(
          transferOffersCodegen.TransferOffer.COMPANION,
          (c: JavaContract[
            transferOffersCodegen.TransferOffer.ContractId,
            transferOffersCodegen.TransferOffer,
          ]) =>
            now.toInstant.isAfter(
              c.payload.expiresAt.plus(automationConfig.clockSkewAutomationDelay.duration)
            ),
        )
        .flatMap({ case QueryResult(_, offers) =>
          logger.info(s"Attempting to expire ${offers.length} expired transfer offers")
          offers
            .traverse { c =>
              expireTransferOffer(c)
                .transform(Success(_))
            /* Note that we're disabling retries altogether here, regardless of the failure reason.
               That's ok since this task will be periodically re-triggered anyway, and we don't care about fine-grained prompt cleanup */
            }
            .map(_.partitionMap(_.toEither) match {
              case (lefts, rights) =>
                val errs = lefts.foldLeft("")((s, l) => s"$s; $l")
                Some(
                  s"cleaned out ${rights.size} expired transfer offers with (${lefts.size} failures: $errs)."
                )
            })
        })
    }
  })

  registerPollingTrigger(
    "TryExpiringAcceptedTransferOffers",
    automationConfig.pollingInterval,
    connection,
  )((now, logger) => { implicit traceContext =>
    {
      logger.debug("Looking for accepted transfer offers to expire...")
      store.acs
        .listContracts(
          transferOffersCodegen.AcceptedTransferOffer.COMPANION,
          (c: JavaContract[
            transferOffersCodegen.AcceptedTransferOffer.ContractId,
            transferOffersCodegen.AcceptedTransferOffer,
          ]) =>
            now.toInstant.isAfter(
              c.payload.expiresAt.plus(automationConfig.clockSkewAutomationDelay.duration)
            ),
        )
        .flatMap({ case QueryResult(_, offers) =>
          logger.info(s"Attempting to expire ${offers.length} expired accepted transfer offers")
          offers
            .traverse { c =>
              expireAcceptedTransferOffer(c)
                .transform(Success(_))
            /* Note that we're disabling retries altogether here, regardless of the failure reason.
               That's ok since this task will be periodically re-triggered anyway, and we don't care about fine-grained prompt cleanup */
            }
            .map(_.partitionMap(a => a.toEither) match {
              case (lefts, rights) =>
                val errs = lefts.foldLeft("")((s, l) => s"$s; $l")
                Some(
                  s"cleaned out ${rights.size} expired accepted transfer offers with (${lefts.size} failures: $errs)."
                )
            })
        })
    }
  })

  private def getInstall =
    store
      .lookupInstall()
      .map(
        _.value.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("WalletAppInstall contract")
          )
        )
      )

  private def expireTransferOffer(
      expiredTransferOffer: JavaContract[TransferOffer.ContractId, TransferOffer]
  )(implicit traceContext: TraceContext): Future[String] = {

    for {
      install <- getInstall

      cmd = install.contractId.exerciseWalletAppInstall_TransferOffer_Expire(
        expiredTransferOffer.contractId,
        store.key.endUserParty.toProtoPrimitive,
      )
      res <- connection
        .submitWithResultNoDedup(
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
        )
        .map(_ => "Successfully expired transfer offer")
    } yield res
  }

  private def expireAcceptedTransferOffer(
      expiredAcceptedTransferOffer: JavaContract[
        AcceptedTransferOffer.ContractId,
        AcceptedTransferOffer,
      ]
  )(implicit traceContext: TraceContext): Future[String] = {

    for {
      install <- getInstall

      cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Expire(
        expiredAcceptedTransferOffer.contractId,
        store.key.endUserParty.toProtoPrimitive,
      )
      res <- connection
        .submitWithResultNoDedup(
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
        )
        .map(_ => "Successfully expired accepted transfer offer")
    } yield res
  }

  registerPollingTrigger(
    "make due subscription payments",
    automationConfig.pollingInterval,
    connection,
  )((now, logger) => { implicit traceContext =>
    {
      makeDueSubscriptionPaymentsForStore(now, logger)
        .map(_.partitionMap(r => r) match {
          case (lefts, rights) =>
            Some(s"created ${rights.size} subscription payments (${lefts.size} failures).")
        })
    }
  })

  private def makeDueSubscriptionPaymentsForStore(
      now: CantonTimestamp,
      logger: TracedLogger,
  )(implicit tc: TraceContext): Future[Seq[Either[String, Unit]]] = {
    store.acs
      .listContracts(subsCodegen.SubscriptionIdleState.COMPANION)
      .flatMap { case QueryResult(_, subStates) =>
        subStates
          // extract subscriptions we can make payments on now
          .filter(state =>
            now.toInstant.isAfter(
              state.payload.nextPaymentDueAt
                .minus(
                  state.payload.payData.paymentDuration.microseconds,
                  ChronoUnit.MICROS,
                )
                // we don't pay immediately to account for potential clock skew
                .plus(automationConfig.clockSkewAutomationDelay.duration)
            )
          )
          // initiate payment via treasury
          .map(readyState => makeSubscriptionPayment(readyState.contractId, logger))
          .sequence
      }
  }

  private def makeSubscriptionPayment(
      stateCid: subsCodegen.SubscriptionIdleState.ContractId,
      logger: TracedLogger,
  )(implicit tc: TraceContext): Future[Either[String, Unit]] = {
    val operation = new installCodegen.coinoperation.CO_SubscriptionMakePayment(stateCid)
    treasury
      .enqueueCoinOperation(operation)
      .map {
        case failedOperation: installCodegen.coinoperationoutcome.COO_Error =>
          val error =
            s"the coin operation failed with a Daml exception: ${failedOperation.stringValue}."
          logger.warn(s"Failed making a subscription payment on state $stateCid: $error")
          Left(s"state $stateCid: $error")

        case _ =>
          logger.info(s"Made a subscription payment on state $stateCid")
          Right(())
      }
  }
}
