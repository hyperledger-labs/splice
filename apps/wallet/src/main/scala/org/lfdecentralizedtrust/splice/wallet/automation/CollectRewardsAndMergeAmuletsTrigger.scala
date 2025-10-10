// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.PollingTrigger.JitteredDelayedFutureScheduler
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_MergeTransferInputs
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome.{
  COO_Error,
  COO_MergeTransferInputs,
}
import org.lfdecentralizedtrust.splice.environment.{CommandPriority, RetryFor}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.util.IssuingRoundBasedUniformFutureScheduler
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import org.lfdecentralizedtrust.splice.wallet.util.{TopupUtil, ValidatorTopupConfig}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class CollectRewardsAndMergeAmuletsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
    scanConnection: BftScanConnection,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    clock: Clock,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    val mat: Materializer,
) extends PollingTrigger {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  override protected val pollingScheduler: PollingTrigger.DelayedFutureScheduler =
    if (context.config.rewardOperationEnableRoundBasedInterval)
      new IssuingRoundBasedUniformFutureScheduler(
        (ec: ExecutionContext, tc: TraceContext) =>
          scanConnection
            .getOpenAndIssuingMiningRounds()(ec, mat, tc)
            .map(_._2.map(_.contract.payload))(ec),
        context.config,
        clock,
        context.retryProvider,
        loggerFactory,
      )
    else
      new JitteredDelayedFutureScheduler(
        context.clock,
        context.config.rewardOperationPollingInterval,
        context.config.rewardOperationPollingJitter,
        context.retryProvider,
      )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    // Retry because we want to avoid missing rewards.
    // We add the retry here instead of using a PeriodicTaskTrigger because
    // PeriodicTaskTrigger does not allow defining whether work was performed
    // that skips the polling interval. So if we have more rewards than we can
    // get in one polling interval,
    // it would wait until the next polling interval before trying to collect
    // them which risks missing some.
    context.retryProvider.retry(
      RetryFor.Automation,
      "collect_rewards_and_merge_amulets",
      "Collect rewards and merge amulets",
      collectRewardsAndMergeAmulets(),
      logger,
    )

  private def collectRewardsAndMergeAmulets()(implicit
      traceContext: TraceContext
  ): Future[Boolean] =
    for {
      commandPriority <- validatorTopupConfigO match {
        case None =>
          Future.successful(CommandPriority.Low) // not the wallet of the validator operator
        case Some(validatorTopupConfig) =>
          TopupUtil
            .hasSufficientFundsForTopup(scanConnection, store, validatorTopupConfig, clock)
            .map(if (_) CommandPriority.Low else CommandPriority.High): Future[CommandPriority]
      }
      result <- treasury
        .enqueueAmuletOperation(
          new CO_MergeTransferInputs(com.daml.ledger.javaapi.data.Unit.getInstance()),
          commandPriority,
        )
        .transform {
          case Success(coo) =>
            coo match {
              case outcome: COO_MergeTransferInputs =>
                // if empty -> no work was done
                Success(!outcome.optionalValue.isEmpty)
              case error: COO_Error =>
                logger.debug(s"received an unexpected COOError: $error - ignoring for now")
                // given the error, don't retry immediately
                Success(false)
              case otherwise => sys.error(s"unexpected COO return type: $otherwise")
            }
          case Failure(ex: StatusRuntimeException)
              if ex.getStatus.getCode == Status.Code.UNAVAILABLE =>
            logger.debug("Skipping amulet merge because treasury service is shutting down")
            // given the error, don't retry immediately
            Success(false)
          case Failure(err) => Failure(err)
        }
    } yield result
}
