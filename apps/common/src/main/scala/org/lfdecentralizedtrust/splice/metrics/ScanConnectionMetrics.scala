// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.metrics

import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import com.daml.metrics.api.MetricsContext.Implicits.empty
import com.daml.metrics.api.{MetricInfo, MetricName}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class ScanConnectionMetrics(metricsFactory: LabeledMetricsFactory) {

  private val prefix: MetricName =
    SpliceMetrics.MetricsPrefix :+ "validator" :+ "scan"

  private val perConnectionLabels: Map[String, String] = Map(
    "scan_connection" -> "The scan connection (host) handling the request",
    "request" -> "Name of the scan request being called (no arguments)",
  )

  /** Latency of a single HTTP/gRPC call to one scan node. */
  val latencyPerConnection: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "per_connection_latency",
        summary = "Latency of a single request to one scan connection",
        qualification = Latency,
        labelsWithDescription = perConnectionLabels,
      )
    )

  /** Count of succeeded and failed HTTP/gRPC calls to one scan node. */
  val callPerConnection: Meter =
    metricsFactory.meter(
      MetricInfo(
        name = prefix :+ "per_connection_calls",
        summary = "Count of succeeded and failed requests to a scan connection",
        qualification = Traffic,
        labelsWithDescription = perConnectionLabels ++ Map(
          "outcome" -> "Category of failure or success",
          "http_status" -> ("For failures, the HTTP status code of the response when available, " +
            "'none' otherwise (e.g. transport-level failures). Absent for successful requests."),
        ),
      )
    )

  /** End-to-end latency of a BFT read across the f+1 scan connections, including
    * retries and the consensus check.
    */
  val bftReadLatency: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "bft_read_latency",
        summary = "End-to-end latency of a BFT read across scan connections",
        qualification = Latency,
        labelsWithDescription = Map(
          "request" -> "Name of the scan request being called (no arguments)"
        ),
      )
    )

  /** Count of BFT reads that succeeded and failed (ok, consensus not reached, not enough scans
    * available, or any underlying transport error).
    */
  val bftCalls: Meter =
    metricsFactory.meter(
      MetricInfo(
        name = prefix :+ "bft_calls",
        summary = "Count of BFT reads",
        qualification = Traffic,
        labelsWithDescription = Map(
          "request" -> "Name of the scan request being called (no arguments)",
          "outcome" -> ("BFT outcome: " +
            "ok (succeeded), " +
            "not_enough_scans (fewer than f+1 reachable scans), " +
            "consensus_not_reached (responses did not agree), " +
            "transport_error (all underlying calls failed), " +
            "not_yet (not enough scans have the data yet, but eventually may succeed), " +
            "never (all scans have indicated they will never have the data)"),
        ),
      )
    )

  val bftPerConnectionConsensus: Meter =
    metricsFactory.meter(
      MetricInfo(
        name = prefix :+ "bft_per_connection_consensus",
        summary = "Count of per-connection agreements/disagreements with the BFT consensus result",
        qualification = Traffic,
        labelsWithDescription = Map(
          "scan_connection" -> "The scan connection that responded",
          "request" -> "Name of the scan request being called",
          "consensus" -> ("Whether the connection's response matched the consensus result: " +
            "agree (matched the consensus response), " +
            "disagree (differed from the consensus response, including error responses)"),
          "success" -> ("For disagreements, whether the disagreeing response was itself a " +
            "successful (2xx) response (true) or an error response (false)"),
          "http_status" -> ("For disagreements, the HTTP status code of the disagreeing response " +
            "when available (absent for successful responses and transport-level failures)"),
        ),
      )
    )

}
