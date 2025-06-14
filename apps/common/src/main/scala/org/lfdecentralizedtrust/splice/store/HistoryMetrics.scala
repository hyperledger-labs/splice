// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.metrics.api.MetricHandle.{Counter, Gauge, LabeledMetricsFactory, Meter}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricQualification.{Debug, Traffic}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import com.digitalasset.canton.data.CantonTimestamp

class HistoryMetrics(metricsFactory: LabeledMetricsFactory)(implicit
    metricsContext: MetricsContext
) {
  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "history"

  object UpdateHistoryBackfilling {
    private val historyBackfillingPrefix: MetricName = prefix :+ "backfilling"

    type CantonTimestampMicros =
      Long // OpenTelemetry Gauges only allow numeric types and there's no way to map it
    val latestRecordTime: Gauge[CantonTimestampMicros] =
      metricsFactory.gauge(
        MetricInfo(
          name = historyBackfillingPrefix :+ "latest-record-time",
          summary = "The latest record time that has been backfilled",
          Traffic,
        ),
        initial = CantonTimestamp.MinValue.toMicros,
      )(metricsContext)

    val updateCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = historyBackfillingPrefix :+ "transaction-count",
          summary = "The number of updates (txs & reassignments) that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    val eventCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = historyBackfillingPrefix :+ "event-count",
          summary = "The number of events that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    val completed: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = historyBackfillingPrefix :+ "completed",
          summary = "Whether it was completed (1) or not (0)",
          Debug,
        ),
        initial = 0,
      )(metricsContext)
  }

  object TxLogBackfilling {
    private val historyBackfillingPrefix: MetricName = prefix :+ "txlog-backfilling"

    type CantonTimestampMicros =
      Long // OpenTelemetry Gauges only allow numeric types and there's no way to map it
    val latestRecordTime: Gauge[CantonTimestampMicros] =
      metricsFactory.gauge(
        MetricInfo(
          name = historyBackfillingPrefix :+ "latest-record-time",
          summary = "The latest record time that has been backfilled",
          Traffic,
        ),
        initial = CantonTimestamp.MinValue.toMicros,
      )(metricsContext)

    val updateCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = historyBackfillingPrefix :+ "transaction-count",
          summary = "The number of updates (txs & reassignments) that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    val eventCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = historyBackfillingPrefix :+ "event-count",
          summary = "The number of events that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    val completed: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = historyBackfillingPrefix :+ "completed",
          summary = "Whether it was completed (1) or not (0)",
          Debug,
        ),
        initial = 0,
      )(metricsContext)
  }

  object ImportUpdatesBackfilling {
    private val importUpdatesBackfillingPrefix: MetricName = prefix :+ "import-updates-backfilling"

    val latestMigrationId: Gauge[Long] =
      metricsFactory.gauge(
        MetricInfo(
          name = importUpdatesBackfillingPrefix :+ "latest-record-time",
          summary = "The migration id of the latest backfilled import update",
          Traffic,
        ),
        initial = -1L,
      )(metricsContext)

    val contractCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = importUpdatesBackfillingPrefix :+ "contract-count",
          summary = "The number of contracts that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    val completed: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = importUpdatesBackfillingPrefix :+ "completed",
          summary = "Whether it was completed (1) or not (0)",
          Debug,
        ),
        initial = 0,
      )(metricsContext)
  }

  object CorruptAcsSnapshots {
    private val corruptAcsSnapshotsPrefix: MetricName = prefix :+ "corrupt-acs-snapshots"

    type CantonTimestampMicros =
      Long // OpenTelemetry Gauges only allow numeric types and there's no way to map it
    val latestRecordTime: Gauge[CantonTimestampMicros] =
      metricsFactory.gauge(
        MetricInfo(
          name = corruptAcsSnapshotsPrefix :+ "latest-record-time",
          summary = "The record time of the latest corrupt snapshot that has been deleted",
          Traffic,
        ),
        initial = CantonTimestamp.MinValue.toMicros,
      )(metricsContext)

    val count: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = corruptAcsSnapshotsPrefix :+ "count",
          summary = "The number of corrupt ACS snapshots deleted",
          Traffic,
        )
      )(metricsContext)

    val completed: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = corruptAcsSnapshotsPrefix :+ "completed",
          summary = "Whether all corrupt snapshots are deleted (1) or not (0)",
          Debug,
        ),
        initial = 0,
      )(metricsContext)
  }

  object UpdateHistory {
    private val updateHistoryPrefix: MetricName = prefix :+ "updates"

    val assignments: Meter = metricsFactory.meter(
      MetricInfo(
        name = updateHistoryPrefix :+ "assignments",
        summary =
          "Total number of assignments in update history (note that this should be used only for tracking the delta over time, the absolute value may be wrong)",
        Traffic,
      )
    )(metricsContext)

    val unassignments: Meter = metricsFactory.meter(
      MetricInfo(
        name = updateHistoryPrefix :+ "unassignments",
        summary = "Total number of unassignments in update history",
        Traffic,
      )
    )(metricsContext)

    val transactionsTrees: Meter = metricsFactory.meter(
      MetricInfo(
        name = updateHistoryPrefix :+ "transactions",
        summary = "Total number of transaction trees in update history",
        Traffic,
      )
    )(metricsContext)

  }
}
