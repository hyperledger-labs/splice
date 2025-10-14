// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.automation.RoundBasedRewardTrigger.RoundBasedTask
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_MergeTransferInputs
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome.{
  COO_Error,
  COO_MergeTransferInputs,
}
import org.lfdecentralizedtrust.splice.environment.CommandPriority
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger.{
  CollectOrMergeRound,
  CollectRewardsAndMergeAmuletsTask,
}
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import org.lfdecentralizedtrust.splice.wallet.util.{TopupUtil, ValidatorTopupConfig}

import java.time.{Duration, Instant}
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
) extends RoundBasedRewardTrigger[CollectRewardsAndMergeAmuletsTask] {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  override protected def retrieveAvailableTasksForRound()(implicit
      tc: TraceContext
  ): Future[Seq[CollectRewardsAndMergeAmuletsTask]] = {
    for {
      (_, issuingRounds) <- scanConnection.getOpenAndIssuingMiningRounds().map {
        case (openRounds, issuingRounds) =>
          (
            openRounds.map(round =>
              CollectOrMergeRound(
                Long.unbox(round.payload.round.number),
                round.payload.opensAt,
                round.payload.targetClosesAt,
              )
            ),
            issuingRounds.map(round =>
              CollectOrMergeRound(
                Long.unbox(round.payload.round.number),
                round.payload.opensAt,
                round.payload.targetClosesAt,
              )
            ),
          )
      }
    } yield {
      val openRoundsForTask = issuingRounds
        .filter(_.opensAt.isBefore(context.clock.now.toInstant))
      openRoundsForTask
        .maxByOption(_.opensAt)
        .map(round =>
          CollectRewardsAndMergeAmuletsTask(
            round.number,
            round.opensAt,
            tickDuration =
              Duration.ofMillis(Duration.between(round.opensAt, round.closesAt).toMillis / 2),
            openRoundsForTask
              .filter(_.closesAt.isAfter(context.clock.now.toInstant))
              .map(_.closesAt)
              .minOption
              .getOrElse(round.closesAt),
            round.closesAt,
            context.config.rewardOperationRoundsCloseBufferDuration.asJava,
          )
        )
        .toList
    }
  }

  override protected def completeTask(task: CollectRewardsAndMergeAmuletsTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = collectRewardsAndMergeAmulets().map(workWasDone =>
    if (workWasDone) {
      TaskSuccess(
        s"Collected rewards and merged amulets for round ${task.roundNumber}, still more work to do"
      )
    } else {
      logger.info(
        s"Finished collecting rewards and merging amulets for round ${task.roundNumber}"
      )
      TaskNoop
    }
  )

  override protected def isStaleTask(task: CollectRewardsAndMergeAmuletsTask)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

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

object CollectRewardsAndMergeAmuletsTrigger {

  case class CollectOrMergeRound(
      number: Long,
      opensAt: Instant,
      closesAt: Instant,
  )

  case class CollectRewardsAndMergeAmuletsTask(
      roundNumber: Long,
      opensAt: Instant,
      tickDuration: Duration,
      closingTimeOfEarliestRound: Instant,
      closesAt: Instant,
      bufferDuration: Duration,
  ) extends PrettyPrinting
      with RoundBasedTask {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("round", _.roundNumber),
        param("opensAt", _.opensAt),
        param("tickDuration", _.tickDuration),
        param("closesAt", _.closesAt),
      )

    /** We schedule the task to run between round opening time and the earliest time between a tick after
      * the round opens and the closing time of the earliest round we know of. In reality these two time points should be pretty close to each other.
      * We also check the closing time of the earliest round to ensure that we run for a second time for the round so that we cover edge cases where the first run failed (because of issues with the syncrhonizer for example).
      * This also covers cases where the opening of the round was delayed because of downtime.
      * This can be further optimized by checking the actual rewards we still need to collect but it would result in the same scheduling time so we play it safe and just schedule it to cover the earlier round as well.
      */
    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    override def scheduleAtMaxTime: Instant = Seq(
      closingTimeOfEarliestRound
        // run 2 minutes before the closing time to account for any latency and avoid missing rewards because the round closed
        // this is relevant only during downtimes where we might have missed the first opportunity to collect rewards
        // rate limiting should protect us against any spikes caused by this running at the same time on every node
        // we calculate this here instead of letting the trigger handle this because we always return the task only for the latest round that is open
        .minus(bufferDuration),
      opensAt.plus(tickDuration),
    ).min
  }
}
