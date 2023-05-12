package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cc.domainfees.{DomainFeesConfig, ValidatorTraffic}
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
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

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
      (topupAmount, topupInterval) = computeTopupAmountAndInterval(
        buyExtraTrafficConfig.targetThroughput,
        buyExtraTrafficConfig.minTopupInterval.asFiniteApproximation,
        domainFeesConfig,
      )
      currentTrafficBalance <- scanConnection.getValidatorTrafficBalance(
        store.key.validatorParty
      )
      result <-
        if (toppingUpTooSoon(validatorTraffic.payload.lastPurchasedAt, topupInterval)) {
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
        } else if (currentTrafficBalance.remainingBalance >= topupAmount) {
          Future.successful(false)
        } else {
          topUpValidatorTraffic(
            validatorTreasury,
            validatorTraffic.contractId,
            topupInterval,
            topupAmount,
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
      topupInterval: FiniteDuration,
  ): Boolean = {
    lastPurchasedAt.toEpochMilli + topupInterval.toMillis > clock.nowInMicrosecondsSinceEpoch / 1000
  }

  private def isTrafficBalanceStale(
      trafficBalance: ValidatorTrafficBalance,
      ingestedTrafficContract: Contract[ValidatorTraffic.ContractId, ValidatorTraffic],
  ) =
    trafficBalance.totalPurchased < ingestedTrafficContract.payload.totalPurchased

  private def computeTopupAmountAndInterval(
      targetRateBytesPerSecond: Double,
      minTopupInterval: FiniteDuration,
      domainFeesConfig: DomainFeesConfig,
  ): (Long, FiniteDuration) = {
    val baseRateBytesPerSecond = domainFeesConfig.baseRateTrafficLimits.rate.doubleValue()
    if (targetRateBytesPerSecond <= baseRateBytesPerSecond) {
      (0, 1.second) // the second return value does not matter in this case
    } else {
      val minTopupAmountBytes = domainFeesConfig.minTopupAmount
      val pollingIntervalSecs = context.config.pollingInterval.duration.toSeconds
      // ensure minTopupInterval is at least equal to the polling interval
      val minTopupIntervalSecs = Math.max(minTopupInterval.toSeconds, pollingIntervalSecs)
      // round minTopupInterval up to the nearest multiple of the polling interval to determine when the
      // next topup is expected to occur.
      val multiple = (minTopupIntervalSecs + pollingIntervalSecs - 1) / pollingIntervalSecs
      val nextTopupAfterSecs = multiple * pollingIntervalSecs
      // calculate expectedTopupAmount as the amount of traffic needed to deliver target rate till next topup
      val expectedTopupAmountBytes =
        ((targetRateBytesPerSecond - baseRateBytesPerSecond) * nextTopupAfterSecs).toLong
      if (expectedTopupAmountBytes > minTopupAmountBytes) {
        (expectedTopupAmountBytes, nextTopupAfterSecs.seconds)
      } else {
        // if the minTopupAmount is higher than expectedTopupAmount, adjust the topup interval to
        // provide target traffic rate.
        // Note that the target rate is greater than the base rate at this point implying that the
        // denominator in this calculation is positive.
        (
          minTopupAmountBytes,
          (minTopupAmountBytes.toDouble / (targetRateBytesPerSecond - baseRateBytesPerSecond)).seconds,
        )
      }
    }
  }

  private def topUpValidatorTraffic(
      validatorTreasury: TreasuryService,
      validatorTrafficCid: ValidatorTraffic.ContractId,
      topupInterval: FiniteDuration,
      extraTrafficBytes: Long,
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    logger.info(s"Topping up traffic balance by ${extraTrafficBytes / 1e6} MB")
    val coBuyExtraTraffic = new CO_BuyExtraTraffic(
      extraTrafficBytes,
      validatorTrafficCid,
      new RelTime(topupInterval.toMillis * 1000),
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
