package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cc.globaldomain.{
  ValidatorTraffic,
  ValidatorTrafficCreationIntent,
}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_BuyExtraTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.{
  COO_BuyExtraTraffic,
  COO_Error,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types as damlTypes
import com.daml.network.environment.{
  CNLedgerConnection,
  CommandPriority,
  ParticipantAdminConnection,
}
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.{CoinConfigSchedule, Contract}
import com.daml.network.validator.config.BuyExtraTrafficConfig
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.traffic.MemberTrafficStatus
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TopupValidatorTrafficBalanceTrigger(
    override protected val context: TriggerContext,
    store: ValidatorStore,
    connection: CNLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    buyExtraTrafficConfig: BuyExtraTrafficConfig,
    clock: Clock,
    walletManager: UserWalletManager,
    scanConnection: ScanConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingTrigger {

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    // TODO(#3816) - Clean up noisy logs
    logger.debug("Executing top-up validator traffic balance trigger")
    for {
      coinRules <- scanConnection.getCoinRules()
      globalDomainConfig = CoinConfigSchedule(coinRules)
        .getConfigAsOf(clock.now)
        .globalDomain
      topupParameters = ExtraTrafficTopupParameters(
        globalDomainConfig.fees,
        buyExtraTrafficConfig,
        context.config.pollingInterval,
      )
      result <-
        if (topupParameters.topupAmount == 0L) {
          logger.debug(
            s"Validator is not configured to buy extra traffic. Skipping..."
          )
          Future.successful(false)
        } else {
          val activeDomainId = DomainId.tryFromString(globalDomainConfig.activeDomain)
          checkAndTopupIfNeeded(topupParameters, activeDomainId)
        }
    } yield result
  }

  private def checkAndTopupIfNeeded(
      topupParameters: ExtraTrafficTopupParameters,
      activeDomainId: DomainId,
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      intentCidOrTraffic <- getOrCreateIntentCidOrTraffic(activeDomainId)
      currentTrafficState <- participantAdminConnection.getParticipantTrafficState(activeDomainId)
      result <-
        if (shouldTopup(currentTrafficState, intentCidOrTraffic.toOption, topupParameters))
          topUpValidatorTraffic(
            intentCidOrTraffic,
            topupParameters,
          )
        else Future.successful(false)
    } yield result
  }

  private def shouldTopup(
      currentTrafficState: MemberTrafficStatus,
      validatorTraffic: Option[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]],
      topupParameters: ExtraTrafficTopupParameters,
  )(implicit traceContext: TraceContext): Boolean = {
    val totalPurchasedTraffic = validatorTraffic.fold(0L)(_.payload.totalPurchased)
    val currentExtraTrafficLimit =
      currentTrafficState.trafficState.extraTrafficLimit.fold(0L)(_.value)
    val currentExtraTrafficRemainder = currentTrafficState.trafficState.extraTrafficRemainder.value
    val tooSoon = validatorTraffic.fold(false)(traffic =>
      traffic.payload.lastPurchasedAt.toEpochMilli + topupParameters.minTopupInterval.duration.toMillis > clock.now.toEpochMilli
    )
    if (tooSoon) {
      logger.debug(s"Trying to top-up too soon after previous top-up")
      false
    } else if (currentExtraTrafficLimit < totalPurchasedTraffic) {
      logger.info(s"There is another top-up already in progress. Retry in some time.")
      false
    } else if (currentExtraTrafficRemainder >= topupParameters.topupAmount) {
      logger.debug(
        s"Skipping top-up because sufficient traffic balance remains. " +
          s"Current traffic balance: $currentExtraTrafficRemainder bytes"
      )
      false
    } else {
      true
    }
  }

  private def topUpValidatorTraffic(
      intentOrTraffic: Either[
        ValidatorTrafficCreationIntent.ContractId,
        Contract[ValidatorTraffic.ContractId, ValidatorTraffic],
      ],
      topupParameters: ExtraTrafficTopupParameters,
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    logger.info(s"Topping up traffic balance by ${topupParameters.topupAmount / 1e6} MB")
    val coBuyExtraTraffic = new CO_BuyExtraTraffic(
      topupParameters.topupAmount,
      intentOrTraffic.fold(
        intentCid => new damlTypes.either.Left(intentCid),
        traffic => new damlTypes.either.Right(traffic.contractId),
      ),
      new RelTime(topupParameters.minTopupInterval.duration.toMillis * 1000),
    )
    for {
      validatorTreasury <- getValidatorTreasury()
      taskOutcome <-
        validatorTreasury
          .enqueueCoinOperation(coBuyExtraTraffic, CommandPriority.High)
          .map {
            case outcome: COO_BuyExtraTraffic =>
              logger.info(
                s"topUpValidatorTraffic outcome - successfully bought extra traffic: $outcome"
              )
              true
            case error: COO_Error =>
              logger.info(
                s"topUpValidatorTraffic outcome - received an unexpected COOError: $error - ignoring for now"
              )
              // given the error, don't retry immediately
              false
            case otherwise => sys.error(s"unexpected COO return type: $otherwise")
          }
    } yield taskOutcome
  }

  private def getValidatorTreasury()(implicit tc: TraceContext): Future[TreasuryService] = {
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

  private def getOrCreateIntentCidOrTraffic(activeDomainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[
    Either[
      ValidatorTrafficCreationIntent.ContractId,
      Contract[ValidatorTraffic.ContractId, ValidatorTraffic],
    ]
  ] = {
    store.lookupValidatorTrafficCreationIntentWithOffset(activeDomainId).flatMap {
      case QueryResult(_, Some(co)) =>
        Future.successful(Left(co.contractId))
      case QueryResult(dedupOffset, None) =>
        store.lookupValidatorTrafficWithOffset(activeDomainId).flatMap {
          case QueryResult(_, Some(traffic)) =>
            Future.successful(Right(traffic))
          case QueryResult(_, None) =>
            val validator = store.key.validatorParty
            connection
              .submitWithResult(
                Seq(validator),
                Seq(validator),
                ValidatorTrafficCreationIntent.create(
                  validator.toProtoPrimitive,
                  activeDomainId.toProtoPrimitive,
                ),
                CNLedgerConnection.CommandId(
                  "com.daml.network.validator.automation.TopupValidatorTrafficBalanceTrigger.getOrCreateIntentCidOrTraffic",
                  Seq(validator),
                  activeDomainId.toProtoPrimitive,
                ),
                DedupOffset(dedupOffset),
                activeDomainId,
                priority = CommandPriority.High,
              )
              .map(ev =>
                Left(new ValidatorTrafficCreationIntent.ContractId(ev.contractId.contractId))
              )
        }
    }
  }
}
