// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.apache.pekko.stream.Materializer
import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.metrics.RewardComputationMetrics
import org.lfdecentralizedtrust.splice.scan.rewards.RewardComputationInputs
import org.lfdecentralizedtrust.splice.scan.store.{
  AppActivityStore,
  ScanAppRewardsStore,
  ScanRewardsReferenceStore,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Trigger that drives the CIP-0104 reward computation pipeline via
  * ScanAppRewardsStore.computeAndStoreRewards, which runs three
  * computation steps in one transaction:
  *   1. Aggregate activity totals from app activity records
  *   2. Compute reward totals (CC minting allowances with threshold filtering)
  *   3. Build the Merkle tree of batched reward hashes
  */
class RewardComputationTrigger(
    appRewardsStore: ScanAppRewardsStore,
    appActivityStore: AppActivityStore,
    rewardsReferenceStore: ScanRewardsReferenceStore,
    updateHistory: UpdateHistory,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[RewardComputationTrigger.Task] {

  private val rewardMetrics = new RewardComputationMetrics(context.metricsFactory)(
    MetricsContext(
      "current_migration_id" -> updateHistory.domainMigrationInfo.currentMigrationId.toString
    )
  )

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[RewardComputationTrigger.Task]] = {
    if (!updateHistory.isReady) {
      logger.debug("Waiting for UpdateHistory to become ready.")
      Future.successful(Seq.empty)
    } else
      for {
        earliestCompleteO <- appActivityStore.earliestRoundWithCompleteAppActivity()
        latestCompleteO <- appActivityStore.latestRoundWithCompleteAppActivity()
        latestComputedO <- appRewardsStore.lookupLatestRoundWithRewardComputation()
        // While no rewards have been computed yet, skip pre-CIP-104 rounds
        // by finding the earliest round with rewardConfig set.
        earliestComputableO <- latestComputedO match {
          case Some(_) => Future.successful(earliestCompleteO)
          case None => earliestRoundWithRewardConfig(earliestCompleteO, latestCompleteO)
        }
        candidateRoundO = RewardComputationTrigger.nextRound(
          earliestComputableO,
          latestCompleteO,
          latestComputedO,
        )
        // Returns Seq.empty (no task created) when data is unavailable.
        // This skips the round for this poll cycle — the trigger will poll
        // again on its next interval. Unlike a failing task, this does not
        // consume one of the trigger's limited task retries (which, once
        // exhausted, cause the task to be abandoned with a log warning).
        task <- candidateRoundO match {
          case None => Future.successful(Seq.empty)
          case Some(roundNumber) =>
            rewardsReferenceStore.lookupOpenMiningRoundByNumber(roundNumber).map {
              case None =>
                logger.debug(
                  s"OpenMiningRound for round $roundNumber not yet ingested, waiting."
                )
                Seq.empty
              case Some(contract) =>
                RewardComputationInputs.fromOpenMiningRound(contract.payload) match {
                  case None =>
                    logger.debug(
                      s"Round $roundNumber missing rewardConfig or trafficPrice, skipping."
                    )
                    Seq.empty
                  case Some((inputs, batchSize)) =>
                    Seq(RewardComputationTrigger.Task(roundNumber, batchSize, inputs))
                }
            }
        }
      } yield task
  }

  override protected def completeTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    appRewardsStore
      .computeAndStoreRewards(task.roundNumber, task.batchSize, task.inputs)
      .map { summary =>
        rewardMetrics.record(summary)
        TaskSuccess(
          s"Computed rewards for round ${task.roundNumber}: " +
            s"${summary.activePartiesCount} active parties, " +
            s"${summary.activityRecordsCount} activity records, " +
            s"${summary.rewardedPartiesCount} rewarded parties, " +
            s"${summary.batchesCreatedCount} batches"
        )
      }

  override protected def isStaleTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] =
    appRewardsStore
      .lookupLatestRoundWithRewardComputation()
      .map(_.exists(_ >= task.roundNumber))

  /** Gate + linear search for the earliest round with rewardConfig.
    * First checks that the latest complete round has rewardConfig (i.e. CIP-104
    * is active). If not, returns None to skip entirely. If yes, searches backward
    * from latestComplete to find the earliest contiguous round with rewardConfig.
    *
    * Called each poll cycle until a reward is successfully computed.
    * Before CIP-104 activates, only the gate check runs (one indexed lookup
    * on the latest complete round), so repeated polling is cheap.
    */
  private def earliestRoundWithRewardConfig(
      earliestCompleteO: Option[Long],
      latestCompleteO: Option[Long],
  )(implicit tc: TraceContext): Future[Option[Long]] =
    (earliestCompleteO, latestCompleteO) match {
      case (Some(from), Some(to)) =>
        rewardsReferenceStore.lookupOpenMiningRoundByNumber(to).flatMap {
          case Some(c) if c.payload.rewardConfig.isPresent =>
            searchBackward(from, to).map { result =>
              logger.debug(s"Earliest round with rewardConfig: $result")
              Some(result)
            }
          case _ =>
            logger.debug(
              "CIP-104 not yet active (latest complete round has no rewardConfig), skipping."
            )
            Future.successful(None)
        }
      case _ => Future.successful(None)
    }

  private def searchBackward(
      from: Long,
      candidate: Long,
  )(implicit tc: TraceContext): Future[Long] =
    if (candidate <= from) Future.successful(candidate)
    else
      rewardsReferenceStore.lookupOpenMiningRoundByNumber(candidate - 1).flatMap {
        case Some(c) if c.payload.rewardConfig.isPresent =>
          searchBackward(from, candidate - 1)
        case _ =>
          Future.successful(candidate)
      }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("RewardComputationMetrics", rewardMetrics.close())
}

object RewardComputationTrigger {

  final case class Task(
      roundNumber: Long,
      batchSize: Int,
      inputs: RewardComputationInputs,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("roundNumber", _.roundNumber))
  }

  /** Compute the next round to process, given the bounds of complete activity data
    * and the latest round for which rewards have already been computed.
    *
    * TODO(#4570): Support parallel execution
    */
  def nextRound(
      earliestCompleteO: Option[Long],
      latestCompleteO: Option[Long],
      latestComputedO: Option[Long],
  ): Option[Long] =
    (earliestCompleteO, latestCompleteO) match {
      case (Some(earliestComplete), Some(latestComplete)) if earliestComplete <= latestComplete =>
        val start = math.max(earliestComplete, latestComputedO.fold(0L)(_ + 1))
        if (start <= latestComplete) Some(start)
        else None
      case _ => None
    }

}
