// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.Gauge.CloseableGauge
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricQualification.Debug
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.util.HasHealth

import scala.util.control.NonFatal

class AutomationMetrics(
    metricsFactory: LabeledMetricsFactory
)(implicit mc: MetricsContext)
    extends AutoCloseable {
  import AutomationMetrics.*

  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "automation"

  private val triggerParallelismInfo: MetricInfo = MetricInfo(
    name = SpliceMetrics.MetricsPrefix :+ "trigger" :+ "parallelism",
    summary = "Maximum number of tasks a trigger can process concurrently",
    description =
      "Reports the configured parallelism for task-based triggers and one for polling triggers.",
    qualification = Debug,
    labelsWithDescription = Map(
      "trigger_name" -> "The name of the trigger",
      "trigger_type" -> "Whether the trigger is task-based or polling",
    ),
  )

  private val healthInfo: MetricInfo = MetricInfo(
    name = prefix :+ "background-service-health",
    summary = "Health of an automation background service",
    description =
      "Reports the health of a background service registered with an AutomationService. " +
        s"$HealthyValue - service is healthy, $UnhealthyValue - service is not healthy. ",
    qualification = Debug,
    labelsWithDescription =
      Map("service" -> "The name of the service for which the health is being reported"),
  )

  private val healthGauges: MultiGauge[HasHealth] =
    MultiGauge.long(metricsFactory, healthInfo) { service =>
      try {
        if (service.isHealthy) HealthyValue else UnhealthyValue
      } catch {
        case NonFatal(_) => UnhealthyValue
      }
    }

  private val triggerParallelismGauges: MultiGauge[Long] =
    MultiGauge.long(metricsFactory, triggerParallelismInfo)(identity)

  def registerHealthGauge(service: HasHealth): CloseableGauge = {
    val serviceName = service.getClass.getSimpleName
    healthGauges.register(service)(mc.withExtraLabels(("service", serviceName)))
  }

  def registerTriggerParallelismGauge(
      triggerName: String,
      triggerType: String,
      parallelism: Long,
  ): CloseableGauge =
    triggerParallelismGauges.register(parallelism)(
      mc.withExtraLabels(
        "trigger_name" -> triggerName,
        "trigger_type" -> triggerType,
      )
    )

  override def close(): Unit = {
    healthGauges.close()
    triggerParallelismGauges.close()
  }
}

object AutomationMetrics {

  val HealthyValue: Long = 0L
  val UnhealthyValue: Long = 1L
}
