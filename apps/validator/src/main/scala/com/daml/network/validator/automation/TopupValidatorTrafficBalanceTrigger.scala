package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cc.domainfees.ValidatorTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_BuyExtraTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.{
  COO_BuyExtraTraffic,
  COO_Error,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorTrafficBalance
import com.daml.network.util.Contract
import com.daml.network.validator.config.BuyExtraTrafficConfig
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TopupValidatorTrafficBalanceTrigger(
    override protected val context: TriggerContext,
    buyExtraTrafficConfig: BuyExtraTrafficConfig,
    clock: Clock,
    walletManager: UserWalletManager,
    store: ValidatorStore,
    scanConnection: ScanConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingTrigger {

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    // TODO(#4721) - Clean up the code in this trigger to be more readable.
    //  Also add a check to see if the user has sufficient balance to do a topup.
    // TODO(#3816) - Clean up noisy logs
    logger.debug("Executing top-up validator traffic balance trigger")
    for {
      validatorTreasury <- getValidatorTreasury
      validatorTraffic <- store.getValidatorTraffic()
      domainFeesConfig <- scanConnection
        .getCoinRules()
        .map(_.payload.configSchedule.currentValue.domainFeesConfig)
      topupParameters = ExtraTrafficTopupParameters(
        domainFeesConfig,
        buyExtraTrafficConfig,
        context.config.pollingInterval,
      )
      currentTrafficBalance <- scanConnection.getValidatorTrafficBalance(
        store.key.validatorParty
      )
      result <-
        if (
          toppingUpTooSoon(
            validatorTraffic.payload.lastPurchasedAt,
            topupParameters.minTopupInterval,
          )
        ) {
          logger.debug(
            s"Trying to topup too soon after previous topup at ${validatorTraffic.payload.lastPurchasedAt}"
          )
          Future.successful(false)
        } else if (isTrafficBalanceStale(currentTrafficBalance, validatorTraffic)) {
          logger.debug(
            s"Traffic balance from scan is stale. Retry in some time. " +
              s"Total purchased traffic from scan: ${currentTrafficBalance.totalPurchased}, " +
              s"Latest purchased traffic ingested from ledger: ${validatorTraffic.payload.totalPurchased}"
          )
          Future.successful(false)
        } else if (currentTrafficBalance.remainingBalance >= topupParameters.topupAmount) {
          Future.successful(false)
        } else {
          topUpValidatorTraffic(
            validatorTreasury,
            validatorTraffic.contractId,
            topupParameters,
          )
        }
    } yield result
  }

  private def getValidatorTreasury(implicit tc: TraceContext): Future[TreasuryService] = {
    for {
      walletInstall <- store
        .lookupInstallByParty(store.key.validatorParty)
        .map(
          _.getOrElse(
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                s"No wallet install contract for validator ${store.key.validatorParty}."
              )
            )
          )
        )
      validatorWalletUser = walletInstall.payload.endUserName
      validatorWallet = walletManager
        .lookupUserWallet(validatorWalletUser)
        .getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription(
              s"No wallet found for validator user $validatorWalletUser."
            )
          )
        )
    } yield validatorWallet.treasury
  }

  private def toppingUpTooSoon(
      lastPurchasedAt: java.time.Instant,
      topupInterval: NonNegativeFiniteDuration,
  ): Boolean = {
    lastPurchasedAt.toEpochMilli + topupInterval.duration.toMillis > clock.nowInMicrosecondsSinceEpoch / 1000
  }

  private def isTrafficBalanceStale(
      trafficBalance: ValidatorTrafficBalance,
      ingestedTrafficContract: Contract[ValidatorTraffic.ContractId, ValidatorTraffic],
  ) =
    trafficBalance.totalPurchased < ingestedTrafficContract.payload.totalPurchased

  private def topUpValidatorTraffic(
      validatorTreasury: TreasuryService,
      validatorTrafficCid: ValidatorTraffic.ContractId,
      topupParameters: ExtraTrafficTopupParameters,
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    logger.info(s"Topping up traffic balance by ${topupParameters.topupAmount / 1e6} MB")
    val coBuyExtraTraffic = new CO_BuyExtraTraffic(
      topupParameters.topupAmount,
      validatorTrafficCid,
      new RelTime(topupParameters.minTopupInterval.duration.toMillis * 1000),
    )
    validatorTreasury
      .enqueueCoinOperation(coBuyExtraTraffic)
      .flatMap {
        case outcome: COO_BuyExtraTraffic =>
          logger.info(
            s"topUpValidatorTraffic outcome - successfully bought extra traffic: $outcome"
          )
          Future.successful(true)
        case error: COO_Error =>
          logger.info(
            s"topUpValidatorTraffic outcome - received an unexpected COOError: $error - ignoring for now"
          )
          // given the error, don't retry immediately
          Future.successful(false)
        case otherwise => sys.error(s"unexpected COO return type: $otherwise")
      }
  }
}
