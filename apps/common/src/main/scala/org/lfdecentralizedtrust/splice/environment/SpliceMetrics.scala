// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Timer}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.data.CantonTimestamp
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

object SpliceMetrics {

  val MetricsPrefix: MetricName = MetricName("splice")

  private type CantonTimestampMicros = Long

  def cantonTimestampGauge(
      metricsFactory: LabeledMetricsFactory,
      _info: MetricInfo,
      initial: CantonTimestamp,
  )(implicit mc: MetricsContext): Gauge[CantonTimestamp] = new Gauge[CantonTimestamp] {
    private val underlying: Gauge[CantonTimestampMicros] =
      metricsFactory.gauge(_info, initial.toMicros)

    override def updateValue(newValue: CantonTimestamp)(implicit mc: MetricsContext): Unit =
      underlying.updateValue(newValue.toMicros)

    override def updateValue(f: CantonTimestamp => CantonTimestamp): Unit =
      underlying.updateValue(micros => f(CantonTimestamp.assertFromLong(micros)).toMicros)

    override def getValue: CantonTimestamp = CantonTimestamp.assertFromLong(underlying.getValue)

    override def getValueAndContext: (CantonTimestamp, MetricsContext) = {
      val (micros, context) = underlying.getValueAndContext
      (CantonTimestamp.assertFromLong(micros), context)
    }

    override def close(): Unit = underlying.close()

    override def info: MetricInfo = underlying.info
  }

  def timeDBIO[T](dbio: => DBIO[T], timer: Timer)(implicit mc: MetricsContext): DBIO[T] = {
    val handle = timer.startAsync()(mc)
    dbio.cleanUp(_ => DBIO.successful(handle.stop()(mc)))(ExecutionContext.parasitic)
  }

}
