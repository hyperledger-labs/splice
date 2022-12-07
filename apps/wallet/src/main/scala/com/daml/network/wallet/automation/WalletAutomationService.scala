package com.daml.network.wallet.automation

import akka.stream.Materializer
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Manages background automation that runs on an Wallet app. */
class WalletAutomationService(
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    walletManager: UserWalletManager,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    implicit protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clockConfig, retryProvider) {
  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      "WalletStore",
      walletManager.store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  // TODO(#763): not handling archive events, uninstalling wallets without a restart is not supported yet
  registerTrigger(
    "create user wallet",
    walletManager.store.acs.streamContracts(installCodegen.WalletAppInstall.COMPANION),
  )((install, logger) => { implicit traceContext =>
    Future {
      val endUserName = install.payload.endUserName
      if (walletManager.getOrCreateUserWallet(install))
        Some(s"onboarded wallet end-user '$endUserName'")
      else {
        logger.warn(s"Unexpected duplicate on-boarding of wallet user '$endUserName'")
        Some(s"skipped duplicate on-boarding wallet end-user '$endUserName'")
      }
    }
  })

  // TODO(#1808): move to UserWalletAutomationService
  registerPollingTrigger(
    "make due subscription payments",
    automationConfig.pollingInterval,
    connection,
  )((now, logger) => { implicit traceContext =>
    {
      // process each store separately
      walletManager.endUserWallets
        .map(wallet => makeDueSubscriptionPaymentsForStore(wallet.store, now))
        .toList
        .sequence
        // join results
        .map(_.flatten.partitionMap(r => r) match {
          case (lefts, rights) =>
            Some(s"created ${rights.size} subscription payments (${lefts.size} failures).")
        })
    }
  })

  private def makeDueSubscriptionPaymentsForStore(
      userStore: UserWalletStore,
      now: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Seq[Either[String, Unit]]] = {
    userStore.acs
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
          .map(readyState => makeSubscriptionPayment(readyState.contractId, userStore))
          .sequence
      }
  }

  private def makeSubscriptionPayment(
      stateCid: subsCodegen.SubscriptionIdleState.ContractId,
      userStore: UserWalletStore,
  )(implicit tc: TraceContext): Future[Either[String, Unit]] = {
    val operation = new installCodegen.coinoperation.CO_SubscriptionMakePayment(stateCid)
    (walletManager.lookupUserWallet(userStore.key.endUserName) match {
      case None => Future(Left(s"missing end-user treasury"))
      case Some(userWallet) =>
        userWallet.treasury
          .enqueueCoinOperation(operation)
          .map {
            case failedOperation: installCodegen.coinoperationoutcome.COO_Error =>
              Left(
                s"the coin operation failed with a Daml exception: ${failedOperation.stringValue}."
              )
            case _ =>
              logger.info(s"Made a subscription payment on state $stateCid")
              Right(())
          }
    }).map(_.leftMap(error => {
      logger.warn(s"Failed making a subscription payment on state $stateCid: $error")
      s"state $stateCid: $error"
    }))
  }
}
