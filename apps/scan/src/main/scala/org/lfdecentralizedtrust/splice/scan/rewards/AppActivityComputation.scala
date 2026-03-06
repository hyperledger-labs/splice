// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.mediator.admin.v30
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}

/** Trait for computing app activity records. */
trait AppActivityComputation {

  /** Compute app activity records for a batch of verdicts.
    *
    * Records are returned with verdictRowId = 0 as a placeholder.
    * The caller is responsible for resolving the actual verdict row_ids
    * and patching them before insertion.
    *
    * @param summariesWithVerdicts paired traffic summaries and verdicts (pre-joined by sequencing time)
    * @return annotated input: each pair with an optional computed activity record (verdictRowId = 0)
    */
  def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  ): Seq[
    (
        DbScanVerdictStore.TrafficSummaryT,
        v30.Verdict,
        Option[DbAppActivityRecordStore.AppActivityRecordT],
    )
  ]
}

/** No-op implementation that produces no activity records. */
object NoOpAppActivityComputation extends AppActivityComputation {

  override def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  ): Seq[
    (
        DbScanVerdictStore.TrafficSummaryT,
        v30.Verdict,
        Option[DbAppActivityRecordStore.AppActivityRecordT],
    )
  ] =
    summariesWithVerdicts.map { case (s, v) => (s, v, None) }
}
