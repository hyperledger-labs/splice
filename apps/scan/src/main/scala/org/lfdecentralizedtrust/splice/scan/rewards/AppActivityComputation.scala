// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.mediator.admin.v30
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}

import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import java.time.Instant

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

// TODO(#4384): Remove once the PR adding the real AppActivityComputation is merged into this feature branch.
/** Fake implementation that produces hardcoded activity records for every verdict.
  * Used in integration tests to populate app_activity_record_store so the
  * RewardComputationTrigger can exercise the full reward pipeline.
  */
class FakeAppActivityComputation(
    lookupRound: () => Long
) extends AppActivityComputation {

  override def computeActivities(
      summariesWithVerdicts: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)]
  ): Seq[
    (
        DbScanVerdictStore.TrafficSummaryT,
        v30.Verdict,
        Option[DbAppActivityRecordStore.AppActivityRecordT],
    )
  ] =
    summariesWithVerdicts.map { case (summary, verdict) =>
      val record = DbAppActivityRecordStore.AppActivityRecordT(
        verdictRowId = 0,
        roundNumber = lookupRound(),
        appProviderParties = Seq("IntegrationTest1", "IntegrationTest2"),
        appActivityWeights = Seq(9L, 1L),
      )
      (summary, verdict, Some(record))
    }
}

object FakeAppActivityComputation {

  // TODO(#4384): Remove once the PR adding the real AppActivityComputation is merged into this feature branch.
  /** Create a FakeAppActivityComputation that resolves the round number by
    * looking up the latest closed round from the scan store.
    * Uses Await.result because computeActivities is synchronous.
    */
  def withRoundFromStore(
      lookupRoundOfLatestData: TraceContext => Future[Option[(Long, Instant)]]
  ): FakeAppActivityComputation =
    new FakeAppActivityComputation(
      lookupRound = () =>
        Await
          .result(lookupRoundOfLatestData(TraceContext.empty), 5.seconds)
          .map(_._1)
          .getOrElse(0L)
    )
}
