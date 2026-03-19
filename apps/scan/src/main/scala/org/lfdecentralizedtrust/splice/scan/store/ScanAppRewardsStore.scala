// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.tracing.TraceContext

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
    * Raises on duplicate key if already computed.
    */
  def computeRewards(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Unit]
}
