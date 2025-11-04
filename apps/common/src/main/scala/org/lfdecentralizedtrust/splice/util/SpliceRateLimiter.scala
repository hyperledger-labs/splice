// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricQualification.Saturation
import com.daml.metrics.api.{MetricHandle, MetricInfo, MetricsContext}
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.TracedLogger
import com.google.common.util.concurrent.RateLimiter
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

import java.time.Instant
import java.util
import java.util.Collections
import scala.concurrent.Future
import scala.jdk.CollectionConverters.CollectionHasAsScala

case class SpliceRateLimitMetrics(otelFactory: LabeledMetricsFactory, logger: TracedLogger)(implicit
    mc: MetricsContext
) extends AutoCloseable {

  private val gaugesToClose = Collections.synchronizedList(new util.ArrayList[AutoCloseable]())

  val meter: MetricHandle.Meter = otelFactory.meter(
    MetricInfo(
      SpliceMetrics.MetricsPrefix :+ "rate_limiting",
      "Rate limits applied in the node",
      Saturation,
    )
  )

  /*we need to pass the full context when we create it to avoid duplicate values warnings*/
  def gauge(implicit extraMc: MetricsContext): MetricHandle.Gauge[Double] = {
    val createdGauge = otelFactory.gauge[Double](
      MetricInfo(
        SpliceMetrics.MetricsPrefix :+ "rate_limiting_max_limit_per_second",
        "Max allowed rate per second",
        Saturation,
      ),
      0,
    )(mc.merge(extraMc))
    gaugesToClose.add(createdGauge)
    createdGauge
  }

  override def close(): Unit = {
    LifeCycle.close(gaugesToClose.asScala.toSeq*)(logger)
  }

}

case class SpliceRateLimitConfig(
    enabled: Boolean = true,
    ratePerSecond: Double,
)

class SpliceRateLimiter(
    name: String,
    config: SpliceRateLimitConfig,
    metrics: SpliceRateLimitMetrics,
    enforceAfter: Instant = Instant.now(),
) {

  // noinspection UnstableApiUsage
  private val rateLimiter = RateLimiter.create(config.ratePerSecond)
  metrics
    .gauge(
      MetricsContext("limiter" -> name)
    )
    .updateValue(config.ratePerSecond)(
    )

  def markRun(): Boolean = {
    if (config.enabled && Instant.now().isAfter(enforceAfter)) {
      val canRun = rateLimiter.tryAcquire()
      if (canRun) {
        metrics.meter.mark()(
          MetricsContext("result" -> "accepted", "limiter" -> name)
        )
      } else {
        metrics.meter.mark()(
          MetricsContext("result" -> "rejected", "limiter" -> name)
        )
      }
      canRun
    } else true
  }

  def runWithLimit[T](f: => Future[T]): Future[T] = {
    if (markRun()) {
      f
    } else {
      Future.failed(
        io.grpc.Status.RESOURCE_EXHAUSTED
          .withDescription("Rate limit exceeded")
          .asRuntimeException()
      )
    }
  }

}
