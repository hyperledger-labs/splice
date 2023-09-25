package com.daml.network.validator.automation

import akka.stream.Materializer
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_BuyMemberTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.{
  COO_BuyMemberTraffic,
  COO_Error,
}
import com.daml.network.codegen.java.cn.wallet.topupstate.ValidatorTopUpState
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.environment.{
  CNLedgerConnection,
  CommandPriority,
  ParticipantAdminConnection,
}
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
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.time.Instant
import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class TopupMemberTrafficTrigger(
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

  private val validator = store.key.validatorParty

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    // TODO(#7472): Clean up noisy logs
    logger.debug("Executing top-up member traffic trigger")
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
      topupState <- getOrCreateValidatorTopupState(activeDomainId)
      currentTrafficState <- participantAdminConnection.getParticipantTrafficState(activeDomainId)
      result <-
        if (shouldTopup(currentTrafficState, topupState, topupParameters))
          topUpValidatorTraffic(
            topupState,
            topupParameters,
          )
        else Future.successful(false)
    } yield result
  }

  private def shouldTopup(
      currentTrafficState: MemberTrafficStatus,
      topupState: Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState],
      topupParameters: ExtraTrafficTopupParameters,
  )(implicit traceContext: TraceContext): Boolean = {
    val currentExtraTrafficRemainder = currentTrafficState.trafficState.extraTrafficRemainder.value
    val tooSoon =
      topupState.payload.lastPurchasedAt.toEpochMilli + topupParameters.minTopupInterval.duration.toMillis > clock.now.toEpochMilli
    if (tooSoon) {
      logger.debug(s"Trying to top-up too soon after previous top-up")
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
      topupState: Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState],
      topupParameters: ExtraTrafficTopupParameters,
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    logger.info(s"Topping up traffic balance by ${topupParameters.topupAmount / 1e6} MB")
    val coBuyMemberTraffic = new CO_BuyMemberTraffic(
      topupParameters.topupAmount,
      topupState.payload.memberId,
      topupState.payload.domainId,
      new RelTime(topupParameters.minTopupInterval.duration.toMillis * 1000),
      Optional.of(topupState.contractId),
    )
    for {
      validatorTreasury <- getValidatorTreasury()
      taskOutcome <-
        validatorTreasury
          .enqueueCoinOperation(coBuyMemberTraffic, CommandPriority.High)
          .map {
            case outcome: COO_BuyMemberTraffic =>
              logger.info(
                s"Successfully bought extra traffic: $outcome"
              )
              true
            case error: COO_Error =>
              logger.info(
                s"Received an unexpected COOError: $error - ignoring for now"
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
            throw Status.NOT_FOUND
              .withDescription(
                s"No wallet install contract for validator ${store.key.validatorParty}."
              )
              .asRuntimeException()
          )
        )
      validatorWalletUser = walletInstall.payload.endUserName
      validatorWallet = walletManager
        .lookupUserWallet(validatorWalletUser)
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              s"No wallet found for validator user $validatorWalletUser."
            )
            .asRuntimeException()
        )
    } yield validatorWallet.treasury
  }

  private def getOrCreateValidatorTopupState(
      activeDomainId: DomainId
  )(implicit
      traceContext: TraceContext
  ): Future[Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState]] = {
    store.lookupValidatorTopUpStateWithOffset(activeDomainId).flatMap {
      case QueryResult(_, Some(topupState)) =>
        Future.successful(topupState)
      case QueryResult(dedupOffset, None) =>
        for {
          participantId <- participantAdminConnection.getParticipantId()
          topupState <- connection
            .submit(
              Seq(validator),
              Seq(validator),
              ValidatorTopUpState.create(
                validator.toProtoPrimitive,
                participantId.toProtoPrimitive,
                activeDomainId.toProtoPrimitive,
                Instant.ofEpochSecond(0),
              ),
              priority = CommandPriority.High,
            )
            .withDedup(
              CNLedgerConnection.CommandId(
                "com.daml.network.validator.automation.TopupMemberTrafficTrigger.getOrCreateValidatorTopupState",
                Seq(validator),
                activeDomainId.toProtoPrimitive,
              ),
              DedupOffset(dedupOffset),
            )
            .withDomainId(activeDomainId)
            .yieldResult()
            .flatMap(ev =>
              store.multiDomainAcsStore.getContractByIdOnDomain(ValidatorTopUpState.COMPANION)(
                activeDomainId,
                ev.contractId,
              )
            )
        } yield topupState
    }
  }
}
