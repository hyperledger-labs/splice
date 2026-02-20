// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.mediator.admin.v30
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbAppActivityRecordStore,
  DbSequencerTrafficSummaryStore,
}
import slick.dbio.DBIO

/** Trait for computing and inserting app activity records. */
trait AppActivityComputation {

  /** Compute app activity records for a batch of verdicts (pure computation).
    *
    * @param summariesWithVerdicts paired traffic summaries and verdicts (pre-joined by sequencing time)
    * @param migrationId the current migration id
    * @return the computed app activity records
    */
  def computeActivities(
      summariesWithVerdicts: Seq[(DbSequencerTrafficSummaryStore.TrafficSummaryT, v30.Verdict)],
      migrationId: Long,
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT]

  /** Returns a DBIO action for inserting app activity records (for use in combined transactions).
    */
  def insertActivitiesDBIO(
      records: Seq[DbAppActivityRecordStore.AppActivityRecordT]
  )(implicit tc: TraceContext): DBIO[Unit]
}

/** No-op implementation that does nothing. */
object NoOpAppActivityComputation extends AppActivityComputation {

  override def computeActivities(
      summariesWithVerdicts: Seq[(DbSequencerTrafficSummaryStore.TrafficSummaryT, v30.Verdict)],
      migrationId: Long,
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT] =
    Seq.empty

  override def insertActivitiesDBIO(
      records: Seq[DbAppActivityRecordStore.AppActivityRecordT]
  )(implicit tc: TraceContext): DBIO[Unit] =
    DBIO.successful(())
}
