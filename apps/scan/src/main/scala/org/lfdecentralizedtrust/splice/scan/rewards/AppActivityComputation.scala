// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.mediator.admin.v30
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}

/** Trait for computing app activity records. */
trait AppActivityComputation {

  /** Compute app activity records for a batch of verdicts.
    *
    * @param summariesWithVerdicts paired traffic summaries and verdicts (pre-joined by sequencing time)
    * @param featuredAppProviders the set of featured app provider party IDs
    * @param rowIdByTime map from verdict record_time to the generated verdict row_id
    * @return the computed app activity records
    */
  def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)],
      featuredAppProviders: Set[PartyId],
      rowIdByTime: Map[CantonTimestamp, Long],
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT]
}

/** No-op implementation that does nothing. */
object NoOpAppActivityComputation extends AppActivityComputation {

  override def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)],
      featuredAppProviders: Set[PartyId],
      rowIdByTime: Map[CantonTimestamp, Long],
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT] =
    Seq.empty
}
