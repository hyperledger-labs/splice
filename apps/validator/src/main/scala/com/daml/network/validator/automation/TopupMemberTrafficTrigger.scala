// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.automation

import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.wallet.install.amuletoperation.CO_BuyMemberTraffic
import com.daml.network.codegen.java.splice.wallet.install.amuletoperationoutcome.{
  COO_BuyMemberTraffic,
  COO_Error,
}
import com.daml.network.codegen.java.splice.wallet.topupstate.ValidatorTopUpState
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.RetryProvider.QuietNonRetryableException
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.environment.{
  SpliceLedgerConnection,
  CommandPriority,
  ParticipantAdminConnection,
}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.{AmuletConfigSchedule, Contract}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.wallet.util.{ExtraTrafficTopupParameters, TopupUtil, ValidatorTopupConfig}
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.sequencing.protocol.{SequencerErrors, TrafficState}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.time.Instant
import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class TopupMemberTrafficTrigger(
    override protected val context: TriggerContext,
    store: ValidatorStore,
    connection: SpliceLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    validatorTopupConfig: ValidatorTopupConfig,
    grpcDeadline: Option[NonNegativeFiniteDuration],
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
        validatorTopupConfig.targetThroughput,
        validatorTopupConfig.minTopupInterval,
        decentralizedSynchronizerConfig.fees.minTopupAmount,
        validatorTopupConfig.topupTriggerPollingInterval,
      )
      _ = assert(topupParameters.topupAmount > 0, "topupAmount must be positive")
      // TODO(#13301) This switches over to purchasing traffic for the new synchronizer
      // as soon as it is active. This might be sufficient for Amulet where
      // there a validator has a relatively small amount of contracts and everything is
      // forced to switch over so the remaining extra traffic + the base rate might
      // be sufficient to complete any unassign commands.
      // However for other apps that might switch over later or have much larger ACS,
      // we likely want to still allow purchasing traffic for the old synchronizer.
      activeSynchronizerId = DomainId.tryFromString(
        decentralizedSynchronizerConfig.activeSynchronizer
      )
      currentTrafficState <- participantAdminConnection.getParticipantTrafficState(
        activeSynchronizerId
      )
      topupState <- getOrCreateValidatorTopupState(activeSynchronizerId)
      validatorWallet <- ValidatorUtil.getValidatorWallet(store, walletManager)
      hasSufficientFunds <- TopupUtil
        .hasSufficientFundsForTopup(
          scanConnection,
          validatorWallet.store,
          validatorTopupConfig,
          clock,
        )
    } yield {
      if (
        shouldTopup(
          hasSufficientFunds,
          currentTrafficState,
          topupState,
          topupParameters,
        )
      ) {
        Seq(
          TopupMemberTrafficTrigger.Task(
            topupParameters,
            topupState,
            currentTrafficState,
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
      validatorWallet <- ValidatorUtil.getValidatorWallet(store, walletManager)
      outcome <- validatorWallet.treasury
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
        .recover {
          case GrpcException(GrpcStatus(statusCode, someDescription), _)
              if statusCode == Status.Code.FAILED_PRECONDITION && someDescription.exists(
                _.contains(SequencerErrors.TrafficCredit.id)
              ) =>
            throw OutOfTrafficCredit()
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
      hasSufficientFunds: Boolean,
      currentTrafficState: TrafficState,
      topupState: Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState],
      topupParameters: ExtraTrafficTopupParameters,
  )(implicit traceContext: TraceContext): Boolean = {
    // we do not even submit the topup tx if the validator does not have sufficient funds because we know
    // the tx would fail but it would still drain synchronizer traffic which we would like to avoid (see #11915).
    if (!hasSufficientFunds) {
      logger.warn(
        s"Insufficient funds to buy configured traffic amount. Please ensure that the validator's wallet has enough amulets to purchase " +
          s"${BigDecimal(topupParameters.topupAmount) / 1e6} MB of traffic to continue healthy operation."
      )
      false
    } else {
      val currentExtraTrafficRemainder =
        currentTrafficState.extraTrafficRemainder
      val currentTime = clock.now
      val tooSoon =
        topupState.payload.lastPurchasedAt.toEpochMilli + topupParameters.minTopupInterval.duration.toMillis > currentTime.toEpochMilli
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
      } else {
        true
      }
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
              deadline = grpcDeadline,
            )
            .withDedup(
              SpliceLedgerConnection.CommandId(
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
      trafficState: TrafficState,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Task] =
      prettyOfClass[Task](
        param("topupParameters", _.topupParameters),
        param("topupState", _.topupState),
        param("trafficState", _.trafficState),
      )
  }
}

final case class OutOfTrafficCredit()
    extends QuietNonRetryableException("Member does not have sufficient traffic credit available")
