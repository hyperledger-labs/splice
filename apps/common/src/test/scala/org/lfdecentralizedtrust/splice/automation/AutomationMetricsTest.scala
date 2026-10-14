// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.testing.InMemoryMetricsFactory
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AutomationMetricsTest extends AnyWordSpecLike with Matchers {

  "AutomationMetrics" should {
    "report and close trigger parallelism gauges" in {
      implicit val metricsContext: MetricsContext =
        MetricsContext("node_type" -> "validator")
      val metricsFactory = new InMemoryMetricsFactory()
      val metrics = new AutomationMetrics(metricsFactory)

      val _ = metrics.registerTriggerParallelismGauge("TaskTrigger", "taskbased", 8L)
      val _ = metrics.registerTriggerParallelismGauge("PollingTrigger", "polling", 1L)

      val metricName = SpliceMetrics.MetricsPrefix :+ "trigger" :+ "parallelism"
      val gauges = metricsFactory.metrics.asyncGauges(metricName)
      gauges should have size 2
      gauges.collect {
        case (context, gaugeValue) if context.labels.get("trigger_type").contains("taskbased") =>
          gaugeValue()
      }.toSeq shouldBe Seq(8L)
      gauges.collect {
        case (context, gaugeValue) if context.labels.get("trigger_type").contains("polling") =>
          gaugeValue()
      }.toSeq shouldBe Seq(1L)

      metrics.close()
      metricsFactory.metrics.asyncGauges(metricName) shouldBe empty
    }
  }
}
