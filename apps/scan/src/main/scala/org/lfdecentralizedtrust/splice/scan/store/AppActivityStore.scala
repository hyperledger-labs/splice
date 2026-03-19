// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** Store interface for app activity record queries.
  * Decouples callers from the DB implementation.
  */
trait AppActivityStore {

  /** Check whether app activity ingestion is complete for a round.
    * Returns true if activity records exist for the given round AND for
    * the prior round (proving ingestion was running continuously through
    * round N).
    */
  def isAppActivityCompleteForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Boolean]

  /** Find the earliest round with complete app activity.
    * A round is complete if the prior round also has activity records,
    * proving ingestion was running continuously through it.
    * Returns None if fewer than two consecutive rounds have been ingested.
    */
  def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]]
}
