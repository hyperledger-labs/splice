// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Trigger that drives the CIP-0104 reward computation pipeline through three
  * idempotent computation steps:
  *   1. Aggregate activity totals from app activity records
  *   2. Compute reward totals (CC minting allowances with threshold filtering)
  *   3. Build the Merkle tree of batched reward hashes
  *
  * Note: FeaturedAppRight filtering is handled upstream by AppActivityComputation,
  * which only produces activity records for parties with FeaturedAppRight.
  *
  * TODO(#4384): Add CalculateRewardsV2 / ProcessRewardsV2 contract that gates
  * triggering
  */
class RewardComputationTrigger(
    store: ScanStore,
    appRewardsStore: DbScanAppRewardsStore,
    updateHistory: UpdateHistory,
    override protected val context: TriggerContext,
)(implicit val ec: ExecutionContext, val tracer: Tracer)
    extends PollingTrigger {

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    if (!updateHistory.isReady) {
      Future.successful(false)
    } else {
      val historyId = updateHistory.historyId
      for {
        lastClosedO <- store.lookupRoundOfLatestData()
        result <- lastClosedO match {
          case Some((lastClosed, _)) =>
            for {
              nextRoundO <- appRewardsStore.getNextRoundWithoutRootHash(historyId, lastClosed)
              completedAggregation <- nextRoundO match {
                case Some(nextRound) =>
                  appRewardsStore
                    .aggregateActivityTotals(historyId, nextRound)
                    .map(_ => true)
                case None => Future.successful(false)
              }
            } yield completedAggregation
          case None => Future.successful(false)
        }
      } yield result
    }
  }
}
