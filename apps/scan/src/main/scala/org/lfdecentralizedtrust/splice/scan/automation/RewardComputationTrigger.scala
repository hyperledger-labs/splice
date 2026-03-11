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

/** Provides the set of featured app parties for a given round.
  *
  * Ultimately, will be implemneted using a TCS-based implementation
  * (using DbScanRewardsReferenceStore from PR #4322) when that is merged into this
  * feature branch.
  *
  * For now, when an ACS-based implementation is used.
  */
trait FeaturedAppRightProvider {
  def getFeaturedParties(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[String]]
}

/** Trigger that drives the CIP-0104 reward computation pipeline through three
  * idempotent computation steps:
  *   1. Aggregate activity totals from app activity records
  *   2. Compute reward totals (CC minting allowances with threshold filtering)
  *   3. Build the Merkle tree of batched reward hashes
  *
  * FIXME(#4384): Add CalculateRewardsV2 / ProcessRewardsV2 contract that gates
  * triggering
  */
class RewardComputationTrigger(
    store: ScanStore,
    appRewardsStore: DbScanAppRewardsStore,
    updateHistory: UpdateHistory,
    featuredAppRightProvider: FeaturedAppRightProvider,
    override protected val context: TriggerContext,
)(implicit val ec: ExecutionContext, val tracer: Tracer)
    extends PollingTrigger {

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    val historyId = updateHistory.historyId
    for {
      lastClosedO <- store.lookupRoundOfLatestData()
      result <- lastClosedO match {
        case Some((lastClosed, _)) =>
          for {
            nextRoundO <- appRewardsStore.getNextRoundWithoutRootHash(historyId, lastClosed)
            completedAggregation <- nextRoundO match {
              case Some(nextRound) =>
                for {
                  featuredParties <- featuredAppRightProvider.getFeaturedParties(nextRound)
                  _ <- appRewardsStore.aggregateActivityTotals(
                    historyId,
                    nextRound,
                    featuredParties,
                  )
                } yield true
              case None => Future.successful(false)
            }
          } yield completedAggregation
        case None => Future.successful(false)
      }
    } yield result
  }
}
