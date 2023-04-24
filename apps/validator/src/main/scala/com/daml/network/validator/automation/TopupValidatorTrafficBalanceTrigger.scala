package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cc.api.v1.validatortraffic.ValidatorTraffic
import com.daml.network.codegen.java.cc.coin.Coin
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_BuyExtraTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.{
  COO_BuyExtraTraffic,
  COO_Error,
}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{CNNodeUtil, Contract, DomainFeesConstants}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TopupValidatorTrafficBalanceTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
    store: ValidatorStore,
    scanConnection: ScanConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingTrigger {

  private def getValidatorTraffic
      : Future[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]] = {
    store.lookupValidatorTraffic.map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(
            s"No validator traffic contract for validator ${store.key.validatorParty}. Has onboarding finished yet?"
          )
        )
      )
    )
  }

  private def getValidatorWalletBalance()(implicit tc: TraceContext): Future[BigDecimal] = {
    for {
      coins <- store.multiDomainAcsStore.listContracts(Coin.COMPANION)
      currentRound <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
    } yield coins.view
      .map(c => BigDecimal(CNNodeUtil.currentAmount(c.contract.payload, currentRound)))
      .sum
  }

  private def getValidatorTreasury: Future[TreasuryService] = {
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

  private def topUpValidatorTraffic(
      validatorTreasury: TreasuryService,
      validatorTrafficCid: ValidatorTraffic.ContractId,
      amount: BigDecimal,
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    logger.info(s"Topping up traffic balance by $amount")
    val coBuyExtraTraffic = new CO_BuyExtraTraffic(amount.bigDecimal, validatorTrafficCid)
    // borrowed liberally from CollectRewardsAndMergeCoinsTrigger
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

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      validatorTreasury <- getValidatorTreasury
      validatorTrafficContract <- getValidatorTraffic
      currentTrafficBalance <- scanConnection.getValidatorTrafficBalance(
        store.key.validatorParty
      )
      validatorWalletBalance <- getValidatorWalletBalance()
      pollingIntervalInSecs = context.config.pollingInterval.duration.toSeconds
      topUpAmount =
        (DomainFeesConstants.targetThroughput.value - DomainFeesConstants.defaultThroughput.value)
          * pollingIntervalInSecs
      result <-
        if (
          // check that current traffic balance from scan app is not stale
          currentTrafficBalance.totalPaid >= validatorTrafficContract.payload.amount
            .doubleValue() &&
          // check to prevent topup attempt if validator clearly does not have enough coins (1 CC = 1 Traffic Unit)
          validatorWalletBalance > topUpAmount &&
          // check if traffic balance has fallen below minimum threshold
          currentTrafficBalance.remainingBalance < topUpAmount
        )
          topUpValidatorTraffic(validatorTreasury, validatorTrafficContract.contractId, topUpAmount)
        else Future.successful(false)
    } yield result
  }
}
