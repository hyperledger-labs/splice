// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Saturation
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

import scala.collection.concurrent.TrieMap

class TopologyMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {
  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "synchronizer-topology"

  val numPartiesPerParticipantGauges: TrieMap[ParticipantId, Gauge[Double]] =
    TrieMap.empty

  val numPartiesGauge: Gauge[Double] =
    metricsFactory.gauge[Double](
      MetricInfo(
        prefix :+ "num-parties",
        summary = "Total number of parties",
        description =
          "The total number of parties allocated on the Global Synchronizer. Only available if the topology metrics are exported.",
        qualification = Saturation,
      ),
      Double.NaN,
    )(MetricsContext.Empty)

  def getNumPartiesPerParticipantGauge(participantId: ParticipantId): Gauge[Double] = {
    // TODO(tech-debt): factor out this allocation logic for labelled metrics
    numPartiesPerParticipantGauges.get(participantId) match {
      case Some(gauge) => gauge
      case None =>
        val newGauge = metricsFactory.gauge[Double](
          MetricInfo(
            prefix :+ "num-parties-per-participant",
            summary = "Number of parties per participant",
            description =
              "The number of parties hosted on a participant connected to the Global Synchronizer. Only available if the topology metrics are exported.",
            qualification = Saturation,
          ),
          Double.NaN,
        )(MetricsContext.Empty.withExtraLabels("participant_id" -> participantId.toString))
        val optOldValue = numPartiesPerParticipantGauges.putIfAbsent(participantId, newGauge)
        optOldValue match {
          case None => newGauge
          case Some(oldGauge) =>
            // Another thread inserted a gauge in the meantime, close the one we created and return the old one
            newGauge.close()
            oldGauge
        }
    }
  }

  override def close(): Unit = {
    numPartiesGauge.close()
    numPartiesPerParticipantGauges.values.foreach(_.close())
  }
}
