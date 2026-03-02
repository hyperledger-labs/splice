// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.mediator.admin.v30
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}

/** Trait for computing app activity records. */
trait AppActivityComputation {

  /** Compute app activity records for a batch of verdicts (pure computation).
    *
    * @param summariesWithVerdicts paired traffic summaries and verdicts (pre-joined by sequencing time)
    * @return the computed app activity records
    */
  def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT]
}

/** No-op implementation that does nothing. */
object NoOpAppActivityComputation extends AppActivityComputation {

  override def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT] =
    Seq.empty
}
