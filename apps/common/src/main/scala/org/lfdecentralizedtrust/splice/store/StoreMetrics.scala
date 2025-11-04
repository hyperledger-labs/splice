// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.metrics.api.MetricHandle.{Gauge, Histogram, LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

import scala.collection.concurrent.TrieMap

class StoreMetrics(metricsFactory: LabeledMetricsFactory)(metricsContext: MetricsContext)
    extends AutoCloseable {

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "store"

  val signalWhenIngestedLatency: Timer =
    metricsFactory.timer(
      MetricInfo(
        prefix :+ "signal-when-ingested-latency",
        "How long it takes to signal offset ingestion.",
        Latency,
        "This metric measures the time taken for the future returned by `signalWhenIngestedOrShutdown` to complete as an indicication for how far our transaction ingestion lags behind ledger end.",
      )
    )

  val acsSize: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "acs-size",
        summary = "The number of active contracts in this store",
        Traffic,
        "The number of active contracts in this store. Note that this is only in the given store. The participant might have contracts we do not ingest.",
      ),
      0L,
    )(metricsContext)

  val ingestedTxLogEntries: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "ingested-tx-log-entries",
      summary = "The number of transaction log entries ingested by this store",
      Traffic,
      "The number of transaction log entries ingested by this store. Note that there can be more than one entry per transaction.",
    )
  )(metricsContext)

  val batchSize: Histogram = metricsFactory.histogram(
    MetricInfo(
      name = prefix :+ "ingestion-batch-size",
      summary = "The number of TreeUpdateOrOffsetCheckpoint in each batch",
      Traffic,
    )
  )

  val completedIngestions: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "completed-ingestions",
      summary = "The number of completed ingestions by this store",
      Traffic,
      "The number of completed ingestions by this store. This is one for each ACS, transaction tree or reassignment.",
    )
  )(metricsContext)

  private val perSynchronizerLastIngestedRecordTimeMs: TrieMap[SynchronizerId, Gauge[Long]] =
    TrieMap.empty

  def getLastIngestedRecordTimeMsForSynchronizer(synchronizerId: SynchronizerId) =
    perSynchronizerLastIngestedRecordTimeMs.getOrElseUpdate(
      synchronizerId,
      metricsFactory.gauge(
        MetricInfo(
          name = prefix :+ "last-ingested-record-time-ms",
          summary = "The most recent record time ingested by this store",
          Traffic,
          "The most recent record time ingested by this store for each synchronizer in milliseconds. Note that this only updates when the store processes a new transaction so if there is no activity the time won't update.",
        ),
        0L,
      )(metricsContext.merge(MetricsContext((Map("synchronizer_id" -> synchronizerId.toString))))),
    )

  override def close(): Unit = {
    acsSize.close()
    perSynchronizerLastIngestedRecordTimeMs.values.foreach(_.close())
  }
}
