// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.store.TcsStore

import scala.concurrent.{ExecutionContext, Future}

trait RewardsReferenceDataProvider {

  /** For a batch of record times, resolve the oldest open mining round at each time.
    * Returns map from record_time to (roundNumber, roundOpensAt).
    * It is expected that all record_time are present in returned map
    */
  def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]]

  def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]]
}

// store-backed implementation, cache will be implemented later for FAP lookup at this level.
class StoreBackedRewardsReferenceDataProvider(
    rewardsStore: DbScanRewardsReferenceStore
)(implicit ec: ExecutionContext)
    extends RewardsReferenceDataProvider {

  override def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]] = {
    val (minTime, maxTime) = recordTimes.foldLeft(
      (CantonTimestamp.MaxValue, CantonTimestamp.MinValue)
    ) { case ((lo, hi), t) => (lo.min(t), hi.max(t)) }
    rewardsStore.lookupOpenMiningRoundsActiveWithin(minTime, maxTime).map { activeWithinResult =>
      recordTimes.flatMap { recordTime =>
        val roundsAtTime = TcsStore.contractsActiveAsOf(activeWithinResult, recordTime)
        // Filter to rounds that are actually open (opensAt <= recordTime),
        val openRounds = roundsAtTime.filter { r =>
          CantonTimestamp.assertFromInstant(r.contract.payload.opensAt) <= recordTime
        }
        openRounds
          .minByOption(_.contract.payload.round.number)
          .map { r =>
            recordTime -> (
              r.contract.payload.round.number.toLong,
              CantonTimestamp.assertFromInstant(r.contract.payload.opensAt)
            )
          }
      }.toMap
    }
  }

  override def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]] =
    rewardsStore
      .lookupFeaturedAppRightsAsOf(asOf)
      .map(_.map(_.contract.payload.provider).toSet)
}
