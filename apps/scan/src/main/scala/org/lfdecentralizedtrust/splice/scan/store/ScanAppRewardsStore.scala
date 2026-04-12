// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.RewardComputationSummary

import scala.concurrent.Future

/** Store interface for the CIP-0104 reward computation pipeline.
  * Decouples RewardComputationTrigger from the DB implementation.
  */
trait ScanAppRewardsStore {

  /** Returns the latest round for which reward computation has completed
    * (i.e. a root hash exists). None if no rounds have been computed.
    */
  def lookupLatestRoundWithRewardComputation()(implicit
      tc: TraceContext
  ): Future[Option[Long]]

  /** Runs the full reward computation pipeline for a single round:
    * aggregation, CC conversion, and Merkle tree hashing.
    * MUST only be called on rounds for which all app activity records have
    * been ingested and for which the reward information has not yet been computed.
    * TODO(#4382): Accept RewardIssuanceParams so that computeAndStoreRewards
    * can pass it through to computeRewardTotals after aggregation.
    */
  def computeAndStoreRewards(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RewardComputationSummary]
}
