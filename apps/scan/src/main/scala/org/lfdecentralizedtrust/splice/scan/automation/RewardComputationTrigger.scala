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
import org.lfdecentralizedtrust.splice.scan.store.{AppActivityStore, ScanAppRewardsStore}
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
  *
  * TODO(#4383): use ScanRewardsReferenceStore for synchronization
  */
class RewardComputationTrigger(
    appRewardsStore: ScanAppRewardsStore,
    appActivityStore: AppActivityStore,
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

        // TODO(#4383): obtain inputs and batchSize from the appropriate Contracts
        inputs <- Future.successful(RewardComputationTrigger.placeholderInputs)
        batchSize <- Future.successful(RewardComputationTrigger.placeholderBatchSize)
      } yield RewardComputationTrigger.nextTask(
        earliestCompleteO,
        latestCompleteO,
        latestComputedO,
        batchSize,
        inputs,
      )
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
    * Returns at most one task.
    *
    * TODO(#4570): Support parallel execution
    */
  def nextTask(
      earliestCompleteO: Option[Long],
      latestCompleteO: Option[Long],
      latestComputedO: Option[Long],
      batchSize: Int,
      inputs: RewardComputationInputs,
  ): Seq[Task] =
    (earliestCompleteO, latestCompleteO) match {
      case (Some(earliestComplete), Some(latestComplete)) =>
        val start = math.max(earliestComplete, latestComputedO.fold(0L)(_ + 1))
        if (start <= latestComplete)
          Seq(Task(start, batchSize, inputs))
        else Seq.empty
      case _ => Seq.empty
    }

  // TODO(#4383): Remove this once it is obtained from the appropriate Contract
  private[scan] val placeholderBatchSize: Int = 100

  // TODO(#4383): Remove this once the values are obtained from the appropriate Contract
  // These placeholder values are from MainNet DSO config:
  //
  // (Round 89782: Checked in RewardComputationInputsTest).
  private[scan] val placeholderInputs: RewardComputationInputs = {
    import RewardComputationInputs.{fromBigDecimal as n}
    val tickDurationMicros: Long = 600L * 1000000L
    RewardComputationInputs(
      amuletToIssuePerYear = n(BigDecimal("10000000000")),
      appRewardPercentage = n(BigDecimal("0.62")),
      featuredAppRewardCap = n(BigDecimal("1.5")),
      unfeaturedAppRewardCap = n(BigDecimal("0.6")),
      developmentFundPercentage = n(BigDecimal("0.05")),
      tickDurationMicros = tickDurationMicros,
      amuletPrice = n(BigDecimal("0.14877")),
      trafficPrice = n(BigDecimal("60")),
      appRewardCouponThreshold = n(BigDecimal("0.5")),
    )
  }
}
