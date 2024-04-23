package com.daml.network.validator.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.amulet as amuletCodegen
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
import com.daml.network.util.{AmuletConfigSchedule, CNNodeUtil, Contract, PrettyInstances}
import com.daml.network.validator.config.BuyExtraTrafficConfig
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.daml.network.wallet.{UserWalletManager, UserWalletService}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.traffic.MemberTrafficStatus
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

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
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[TopupMemberTrafficTrigger.Task] {

  private val validator = store.key.validatorParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[TopupMemberTrafficTrigger.Task]] = {
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
      _ = assert(topupParameters.topupAmount > 0, "topupAmount must be positive")
      activeSynchronizerId = DomainId.tryFromString(
        decentralizedSynchronizerConfig.activeSynchronizer
      )
      currentTrafficState <- participantAdminConnection.getParticipantTrafficState(
        activeSynchronizerId
      )
      topupState <- getOrCreateValidatorTopupState(activeSynchronizerId)
      amuletPrice <- scanConnection.getLatestOpenMiningRound().map(_.payload.amuletPrice)
      extraTrafficPrice = BigDecimal(decentralizedSynchronizerConfig.fees.extraTrafficPrice)
      amuletBalance <- getWalletBalance()
    } yield {
      if (
        shouldTopup(
          currentTrafficState,
          topupState,
          topupParameters,
          amuletBalance,
          amuletPrice,
          extraTrafficPrice,
          amuletRules.payload.isDevNet,
        )
      ) {
        Seq(
          TopupMemberTrafficTrigger.Task(
            topupParameters,
            topupState,
            currentTrafficState,
            amuletBalance,
            amuletRules.payload.isDevNet,
          )
        )
      } else Seq.empty
    }
  }

  override protected def completeTask(
      task: TopupMemberTrafficTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val coBuyMemberTraffic = new CO_BuyMemberTraffic(
      task.topupParameters.topupAmount,
      task.topupState.payload.memberId,
      task.topupState.payload.synchronizerId,
      task.topupState.payload.migrationId,
      new RelTime(task.topupParameters.minTopupInterval.duration.toMillis * 1000),
      Optional.of(task.topupState.contractId),
    )
    for {
      validatorTreasury <- getValidatorWallet().map(_.treasury)
      outcome <- validatorTreasury
        .enqueueAmuletOperation(coBuyMemberTraffic, CommandPriority.High)
        .map {
          case outcome: COO_BuyMemberTraffic =>
            TaskSuccess(s"Successfully bought extra traffic: $outcome")
          case error: COO_Error =>
            throw Status.ABORTED
              .withDescription(s"Received an unexpected COOError: $error - ignoring for now")
              .asRuntimeException()
          case otherwise =>
            throw Status.INTERNAL
              .withDescription(s"Unexpected COO return type: $otherwise")
              .asRuntimeException()
        }
    } yield outcome
  }

  override protected def isStaleTask(
      task: TopupMemberTrafficTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      currentTopupState <- store
        .lookupValidatorTopUpStateWithOffset(
          DomainId.tryFromString(task.topupState.payload.synchronizerId)
        )
        .map(_.value)
    } yield currentTopupState.fold(false)(
      _.payload.lastPurchasedAt.isAfter(task.topupState.payload.lastPurchasedAt)
    )
  }

  private def shouldTopup(
      currentTrafficState: MemberTrafficStatus,
      topupState: Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState],
      topupParameters: ExtraTrafficTopupParameters,
      amuletBalance: BigDecimal,
      amuletPrice: BigDecimal,
      extraTrafficPrice: BigDecimal,
      isDevNet: Boolean,
  )(implicit traceContext: TraceContext): Boolean = {
    val currentExtraTrafficRemainder = currentTrafficState.trafficState.extraTrafficRemainder.value
    val currentTime = clock.now
    val tooSoon =
      topupState.payload.lastPurchasedAt.toEpochMilli + topupParameters.minTopupInterval.duration.toMillis > currentTime.toEpochMilli
    val (_, amuletsNeededForTopup) =
      CNNodeUtil.synchronizerFees(
        topupParameters.topupAmount,
        extraTrafficPrice,
        amuletPrice,
      )
    if (tooSoon) {
      logger.trace(
        s"Trying to top-up too soon after previous top-up (last purchased at = ${topupState.payload.lastPurchasedAt}, current time = $currentTime)"
      )
      false
    } else if (currentExtraTrafficRemainder >= topupParameters.topupAmount) {
      logger.trace(
        s"Sufficient traffic balance remains (current traffic balance = $currentExtraTrafficRemainder, topup amount = ${topupParameters.topupAmount})"
      )
      false
    } else if (!isDevNet && amuletBalance < amuletsNeededForTopup) {
      // We avoid submitting the top-up tx if there are insufficient funds even though this is enforced in daml
      // because a failing tx will still consume traffic (see #11809).
      // On DevNet (and in tests) we auto-tap coins which renders this check unnecessary. In this case, this check
      // would actively slow down tests and make them flaky so we explicitly avoid doing it.
      // TODO(#8046): Considering removing this once we remove auto-tapping in DevNet
      logger.trace(
        s"Insufficient funds to purchase traffic (current wallet balance = $amuletBalance, required = $amuletsNeededForTopup)"
      )
      false
    } else {
      true
    }
  }

  private def getValidatorWallet()(implicit tc: TraceContext): Future[UserWalletService] = {
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
    } yield validatorWallet
  }

  private def getWalletBalance()(implicit traceContext: TraceContext) = {
    for {
      validatorUserStore <- getValidatorWallet().map(_.store)
      amulets <- validatorUserStore.multiDomainAcsStore.listContracts(
        amuletCodegen.Amulet.COMPANION
      )
      currentRound <- scanConnection
        .getLatestOpenMiningRound()
        .map(_.payload.round.number)
    } yield {
      amulets.view
        .map(c => BigDecimal(CNNodeUtil.currentAmount(c.payload, currentRound)))
        .sum
    }
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
              deadline = buyExtraTrafficConfig.grpcDeadline,
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

object TopupMemberTrafficTrigger {
  final case class Task(
      topupParameters: ExtraTrafficTopupParameters,
      topupState: Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState],
      trafficState: MemberTrafficStatus,
      amuletBalance: BigDecimal,
      isDevNet: Boolean,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Task] =
      prettyOfClass[Task](
        param("topupParameters", _.topupParameters),
        param("topupState", _.topupState),
        param("trafficState", _.trafficState),
        param(
          "amuletBalance",
          (x: Task) => x.amuletBalance.toString,
          (x: Task) => !x.isDevNet,
        )(PrettyInstances.prettyString),
      )
  }
}
