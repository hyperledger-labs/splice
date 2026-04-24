// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** Store interface for app activity record queries.
  * Decouples callers from the DB implementation.
  */
trait AppActivityStore {

  /** Find the earliest round for which all app activity records have been ingested.
    */
  def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]]

  /** Find the latest round for which all app activity records have been ingested.
    */
  def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]]
}
