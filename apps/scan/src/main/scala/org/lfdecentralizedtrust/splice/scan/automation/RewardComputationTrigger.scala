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
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Trigger that drives the CIP-0104 reward computation pipeline via
  * ScanAppRewardsStore.computeRewards, which will eventually run three
  * idempotent steps in one transaction:
  *   1. Aggregate activity totals from app activity records
  *   2. Compute reward totals (CC minting allowances with threshold filtering)
  *   3. Build the Merkle tree of batched reward hashes
  *
  * TODO(#4118): use ScanRewardsReferenceStore for synchronization in computeRewards needs it
  */
class RewardComputationTrigger(
    appRewardsStore: ScanAppRewardsStore,
    appActivityStore: AppActivityStore,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[RewardComputationTrigger.Task] {

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[RewardComputationTrigger.Task]] = {
    for {
      earliestCompleteO <- appActivityStore.earliestRoundWithCompleteAppActivity()
      latestCompleteO <- appActivityStore.latestRoundWithCompleteAppActivity()
      latestComputedO <- appRewardsStore.lookupLatestRoundWithRewardComputation()
    } yield {
      (earliestCompleteO, latestCompleteO) match {
        case (Some(earliestComplete), Some(latestComplete)) =>
          val start = math.max(earliestComplete, latestComputedO.fold(0L)(_ + 1))
          // TODO(#4570): Support parallel execution
          Seq(start).filter(_ <= latestComplete).map(RewardComputationTrigger.Task(_))
        case _ => Seq.empty
      }
    }
  }

  override protected def completeTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    appRewardsStore
      .computeRewards(task.roundNumber)
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
}
