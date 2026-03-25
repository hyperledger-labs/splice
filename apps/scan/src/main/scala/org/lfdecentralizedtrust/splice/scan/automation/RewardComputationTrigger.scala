// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.store.{AppActivityStore, ScanAppRewardsStore}
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Trigger that drives the CIP-0104 reward computation pipeline via
  * ScanAppRewardsStore.computeAndStoreRewards, which will eventually run three
  * computation steps in one transaction:
  *   1. Aggregate activity totals from app activity records
  *   2. Compute reward totals (CC minting allowances with threshold filtering)
  *   3. Build the Merkle tree of batched reward hashes
  *
  * TODO(#4384): use ScanRewardsReferenceStore for synchronization when computeAndStoreRewards requires it
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
      } yield RewardComputationTrigger.nextTask(
        earliestCompleteO,
        latestCompleteO,
        latestComputedO,
      )
  }

  override protected def completeTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    appRewardsStore
      .computeAndStoreRewards(task.roundNumber)
      .map(_ => TaskSuccess(s"Computed rewards for round ${task.roundNumber}"))

  override protected def isStaleTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] =
    appRewardsStore
      .lookupLatestRoundWithRewardComputation()
      .map(_.exists(_ >= task.roundNumber))
}

object RewardComputationTrigger {
  final case class Task(roundNumber: Long) extends PrettyPrinting {
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
  ): Seq[Task] =
    (earliestCompleteO, latestCompleteO) match {
      case (Some(earliestComplete), Some(latestComplete)) =>
        val start = math.max(earliestComplete, latestComputedO.fold(0L)(_ + 1))
        if (start <= latestComplete) Seq(Task(start)) else Seq.empty
      case _ => Seq.empty
    }
}
