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
import io.grpc.Status
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
      "current_migration_id" -> updateHistory.domainMigrationId.toString
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
        // List active CalculateRewardsV2 contracts, ascending by round
        // and filter out
        // - rounds where the rewards are already computed
        // - rounds with incomplete activity
        candidates <- rewardsReferenceStore.listActiveCalculateRewardsV2()
        _ = rewardMetrics.calculateRewardsContractCountDryRun.updateValue(
          candidates.count(_.payload.dryRun)
        )
        _ = rewardMetrics.calculateRewardsContractCountMinting.updateValue(
          candidates.count(!_.payload.dryRun)
        )
        candidateRounds = candidates.map(_.payload.round.number.toLong).distinct.sorted
        computedRounds <- appRewardsStore.roundsWithComputedRewards(candidateRounds)
        afterComputedFilter = candidateRounds.filterNot(computedRounds.contains)
        earliestCompleteO <- appActivityStore.earliestRoundWithCompleteAppActivity()
        latestCompleteO <- appActivityStore.latestRoundWithCompleteAppActivity()
        eligible = (earliestCompleteO, latestCompleteO) match {
          case (Some(earliest), Some(latest)) =>
            afterComputedFilter.filter(r => r >= earliest && r <= latest)
          case _ => Seq.empty[Long]
        }
      } yield eligible.map(RewardComputationTrigger.Task(_))
  }

  // TODO(#111): (placeholder issue #, replace with real follow-up) dispatch on
  // CalculateRewardsV2.kind and route validator-kind contracts to the
  // validator-reward compute pipeline; today they compute as app-flow and
  // produce hashes that can't match any real validator batch.
  override protected def completeTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      roundContract <- rewardsReferenceStore.lookupOpenMiningRoundByNumber(task.roundNumber)
      (inputs, batchSize) = roundContract match {
        case None =>
          throw Status.INTERNAL
            .withDescription(
              s"Round ${task.roundNumber} has a CalculateRewardsV2 contract and complete activity " +
                s"but its OpenMiningRound is not in the rewards reference store."
            )
            .asRuntimeException()
        case Some(contract) =>
          RewardComputationInputs.fromOpenMiningRound(contract.payload).getOrElse {
            throw Status.INTERNAL
              .withDescription(
                s"Round ${task.roundNumber} has a CalculateRewardsV2 contract but its " +
                  s"OpenMiningRound is missing rewardConfig or trafficPrice."
              )
              .asRuntimeException()
          }
      }
      summary <- appRewardsStore.computeAndStoreRewards(task.roundNumber, batchSize, inputs)
    } yield {
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
    for {
      contractExists <- rewardsReferenceStore
        .listActiveCalculateRewardsV2ForRound(task.roundNumber)
        .map(_.nonEmpty)
      alreadyComputed <- appRewardsStore
        .roundsWithComputedRewards(Seq(task.roundNumber))
        .map(_.nonEmpty)
    } yield !contractExists || alreadyComputed

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("RewardComputationMetrics", rewardMetrics.close())
}

object RewardComputationTrigger {

  final case class Task(
      roundNumber: Long
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("roundNumber", _.roundNumber)
      )
  }
}
