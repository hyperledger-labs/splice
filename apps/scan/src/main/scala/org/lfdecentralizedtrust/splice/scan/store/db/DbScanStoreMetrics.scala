// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Latency
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{FlagCloseable, LifeCycle, UnlessShutdown}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.metrics.CacheMetrics
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.store.HistoryMetrics

class DbScanStoreMetrics(
    metricsFactory: LabeledMetricsFactory,
    val loggerFactory: NamedLoggerFactory,
    val timeouts: ProcessingTimeout,
) extends FlagCloseable
    with NamedLogging {

  // storing the caches as they have to be closed so that all the created gauges are closed
  private val cacheOfMetrics = scala.collection.concurrent
    .TrieMap[String, CacheMetrics]()

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
  )(implicit tc: TraceContext): UnlessShutdown[CacheMetrics] =
    synchronizeWithClosingSync(s"register cache $cacheName") {
      cacheOfMetrics.getOrElseUpdate(
        cacheName, {
          logger.info(s"Registering new cache metrics for $cacheName")
          new CacheMetrics(cacheName, metricsFactory)
        },
      )
    }

  val history = new HistoryMetrics(metricsFactory)(MetricsContext.Empty)

  override protected def onClosed(): Unit = {
    LifeCycle.close(
      Seq(earliestAggregatedRound, latestAggregatedRound, history)
        .map(cache => () => cache.close())*
    )(logger)
    cacheOfMetrics.clear()
  }

}
