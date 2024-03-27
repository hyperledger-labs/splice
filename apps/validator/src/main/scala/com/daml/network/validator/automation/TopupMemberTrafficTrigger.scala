package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.splice.wallet.install.amuletoperation.CO_BuyMemberTraffic
import com.daml.network.codegen.java.splice.wallet.install.amuletoperationoutcome.{
  COO_BuyMemberTraffic,
  COO_Error,
}
import com.daml.network.codegen.java.splice.wallet.topupstate.ValidatorTopUpState
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.environment.{
  CNLedgerConnection,
  CommandPriority,
  ParticipantAdminConnection,
}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.{AmuletConfigSchedule, Contract}
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
    scanConnection: BftScanConnection,
    domainMigrationId: Long,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  private val validator = store.key.validatorParty

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      amuletRules <- scanConnection.getAmuletRulesWithState()
      decentralizedSynchronizerConfig = AmuletConfigSchedule(amuletRules)
        .getConfigAsOf(clock.now)
        .decentralizedSynchronizer
      topupParameters = ExtraTrafficTopupParameters(
        decentralizedSynchronizerConfig.fees,
        buyExtraTrafficConfig,
        context.config.pollingInterval,
      )
      result <- {
        assert(topupParameters.topupAmount > 0, "topupAmount must be positive")
        val activeSynchronizerId =
          DomainId.tryFromString(decentralizedSynchronizerConfig.activeSynchronizer)
        checkAndTopupIfNeeded(topupParameters, activeSynchronizerId)
      }
    } yield result
  }

  private def checkAndTopupIfNeeded(
      topupParameters: ExtraTrafficTopupParameters,
      activeSynchronizerId: DomainId,
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      topupState <- getOrCreateValidatorTopupState(activeSynchronizerId)
      currentTrafficState <- participantAdminConnection.getParticipantTrafficState(
        activeSynchronizerId
      )
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
      topupState.payload.synchronizerId,
      topupState.payload.migrationId,
      new RelTime(topupParameters.minTopupInterval.duration.toMillis * 1000),
      Optional.of(topupState.contractId),
    )
    for {
      validatorTreasury <- getValidatorTreasury()
      taskOutcome <-
        validatorTreasury
          .enqueueAmuletOperation(coBuyMemberTraffic, CommandPriority.High)
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
      activeSynchronizerId: DomainId
  )(implicit
      traceContext: TraceContext
  ): Future[Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState]] = {
    store.lookupValidatorTopUpStateWithOffset(activeSynchronizerId).flatMap {
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
                store.key.dsoParty.toProtoPrimitive,
                validator.toProtoPrimitive,
                participantId.toProtoPrimitive,
                activeSynchronizerId.toProtoPrimitive,
                domainMigrationId,
                Instant.ofEpochSecond(0),
              ),
              priority = CommandPriority.High,
            )
            .withDedup(
              CNLedgerConnection.CommandId(
                "com.daml.network.validator.automation.TopupMemberTrafficTrigger.getOrCreateValidatorTopupState",
                Seq(validator),
                activeSynchronizerId.toProtoPrimitive,
              ),
              DedupOffset(dedupOffset),
            )
            .withDomainId(activeSynchronizerId)
            .yieldResult()
            .flatMap(ev =>
              // topping up is tied to the domain in scope here, which was
              // picked from the on-ledger domain config
              store.multiDomainAcsStore.getContractByIdOnDomain(ValidatorTopUpState.COMPANION)(
                activeSynchronizerId,
                ev.contractId,
              )
            )
        } yield topupState
    }
  }
}
