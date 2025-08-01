// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricQualification.Latency
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.apache.pekko.http.scaladsl.server.{Directive0, RouteResult}

import scala.util.{Failure, Success}

class HttpServerMetrics(metricsFactory: LabeledMetricsFactory) {

  private val prefix: MetricName = MetricName.Daml :+ "http"

  private val requestTiming = metricsFactory.timer(
    MetricInfo(
      name = prefix :+ "requests" :+ "duration",
      summary = "Histogram for http request durations",
      qualification = Latency,
      labelsWithDescription = Map(
        "operation" -> "Descriptor of the HTTP operation done, as per the OpenAPI spec",
        "status" -> "Status of the HTTP request: completed, rejected, failure",
      ),
    )
  )

  private val inFlightRequests = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "requests" :+ "inflight",
      summary = "Histogram for http request durations",
      qualification = Latency,
      labelsWithDescription = Map(
        "operation" -> "Descriptor of the HTTP operation done, as per the OpenAPI spec"
      ),
    )
  )

  // This directive is used to wrap HTTP routes with metrics collection.
  // We need to pass the operation explicitly, which represents the OpenAPI operation ID.
  def withMetrics(service: String)(operation: String): Directive0 = {
    import org.apache.pekko.http.scaladsl.server.Directives.*
    implicit val mc: MetricsContext =
      MetricsContext("operation" -> operation, "http_service" -> service)

    extractExecutionContext.flatMap { implicit ec =>
      extractRequest.flatMap { _ =>
        inFlightRequests.inc()
        val timing = requestTiming.startAsync()
        mapRouteResultFuture { resultFuture =>
          resultFuture.map[RouteResult] {
            case res @ RouteResult.Complete(response) =>
              timing.stop()(
                MetricsContext(
                  "status_code" -> response.status.intValue().toString,
                  "status" -> "completed",
                )
              )
              res
            case res @ RouteResult.Rejected(_) =>
              timing.stop()(MetricsContext("status" -> "rejected"))
              res
          } andThen {
            case Failure(_) =>
              timing.stop()(MetricsContext("status" -> "failure"))
              inFlightRequests.dec()
            case Success(_) =>
              inFlightRequests.dec()
          }
        }
      }
    }
  }

}
