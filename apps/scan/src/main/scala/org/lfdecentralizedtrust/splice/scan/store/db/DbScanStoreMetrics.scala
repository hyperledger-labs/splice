// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Latency
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.CacheMetrics
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.store.HistoryMetrics

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.CollectionHasAsScala

class DbScanStoreMetrics(
    metricsFactory: LabeledMetricsFactory
) extends AutoCloseable {

  private val createdCaches = new ConcurrentLinkedQueue[CacheMetrics]()
  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan_store"

  val earliestAggregatedRound: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "earliest-aggregated-round",
        summary = "Earliest aggregated round",
        description = "The earliest aggregated round.",
        qualification = Latency,
      ),
      -1L,
    )(MetricsContext.Empty)

  val latestAggregatedRound: Gauge[Long] =
    metricsFactory.gauge(
      MetricInfo(
        name = prefix :+ "latest-aggregated-round",
        summary = "Latest aggregated round",
        description = "The latest aggregated round.",
        qualification = Latency,
      ),
      -1L,
    )(MetricsContext.Empty)

  def registerNewCacheMetrics(
      cacheName: String
  ): CacheMetrics = {
    val cacheMetrics = new CacheMetrics(cacheName, metricsFactory)
    createdCaches.add(cacheMetrics)
    cacheMetrics
  }

  val history = new HistoryMetrics(metricsFactory)(MetricsContext.Empty)

  override def close() = {
    LifeCycle.close(
      (Seq(earliestAggregatedRound, latestAggregatedRound) ++
        createdCaches.asScala
          .map(cache =>
            new AutoCloseable {
              override def close(): Unit = cache.closeAcquired()
            }
          )
          .toSeq)*
    )(NamedLoggerFactory.root.getTracedLogger(getClass))
  }
}
