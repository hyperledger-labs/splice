// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Latency
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{FlagCloseable, LifeCycle, UnlessShutdown}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.metrics.CacheMetrics
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.store.HistoryMetrics

class DbScanStoreMetrics(
    metricsFactory: LabeledMetricsFactory,
    val logger: TracedLogger,
    val timeouts: ProcessingTimeout,
) extends FlagCloseable {

  /** Storing all the created cache metrics to ensure we always have just one instance per name
    * This is done because creating the same gauge with teh same name is not safe
    * Eventually we might want to move this all the way into the metrics factory to always return the same gauge
    */
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
    performUnlessClosing(s"register cache $cacheName") {
      logger.info(s"Registering new cache metrics for ${cacheName}")
      cacheOfMetrics.getOrElseUpdate(cacheName, new CacheMetrics(cacheName, metricsFactory))
    }

  val history = new HistoryMetrics(metricsFactory)(MetricsContext.Empty)

  override protected def onClosed(): Unit = {
    LifeCycle.close(
      (Seq(earliestAggregatedRound, latestAggregatedRound, history) ++
        cacheOfMetrics.values
          .map(cache =>
            new AutoCloseable {
              override def close(): Unit = cache.closeAcquired()
            }
          )
          .toSeq)*
    )(logger)
    cacheOfMetrics.clear()
  }

}
