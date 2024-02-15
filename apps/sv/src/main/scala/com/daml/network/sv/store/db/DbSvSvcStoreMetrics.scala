package com.daml.network.sv.store.db

import com.daml.metrics.api.MetricDoc.MetricQualification.Latency
import com.daml.metrics.api.MetricHandle.Gauge
import com.daml.metrics.api.{MetricsContext, MetricDoc, MetricName}
import com.daml.network.environment.CNMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

class DbSvSvcStoreMetrics(metricsFactory: CantonLabeledMetricsFactory) extends AutoCloseable {

  val prefix: MetricName = CNMetrics.MetricsPrefix :+ "sv_svc_store"

  @MetricDoc.Tag(
    summary = "Latest open mining round",
    description =
      "The number of the latest open mining round (not necessarily active yet) ingested by the store.",
    qualification = Latency,
  )
  val latestOpenMiningRound: Gauge[Long] =
    metricsFactory.gauge(prefix :+ "latest-open-mining-round", -1L)(MetricsContext.Empty)

  override def close() = {
    latestOpenMiningRound.close()
  }
}
