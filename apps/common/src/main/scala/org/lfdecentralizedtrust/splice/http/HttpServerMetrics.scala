// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricQualification.Latency
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.server.{Directive0, RouteResult}

import scala.util.{Failure, Success}

object HttpServerMetrics {
  val customSourceUiHeader = "x-source-ui"
  object LabelNames {
    val Operation = "operation"
    val Status = "status"
    val StatusCode = "status_code"
    val HttpService = "http_service"
    val SourceUi = "source_ui"
  }
}

class HttpServerMetrics(
    metricsFactory: LabeledMetricsFactory,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {
  import HttpServerMetrics.*
  import HttpServerMetrics.LabelNames.*

  private val prefix: MetricName = MetricName.Daml :+ "http"

  private val requestTiming = metricsFactory.timer(
    MetricInfo(
      name = prefix :+ "requests" :+ "duration",
      summary = "Histogram for http request durations",
      qualification = Latency,
      labelsWithDescription = Map(
        Operation -> "Descriptor of the HTTP operation done, as per the OpenAPI spec",
        Status -> "Status of the HTTP request: completed, rejected, failure",
      ),
    )
  )

  private val inFlightRequests = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "requests" :+ "inflight",
      summary = "Count of in-flight http requests",
      qualification = Latency,
      labelsWithDescription = Map(
        Operation -> "Descriptor of the HTTP operation done, as per the OpenAPI spec"
      ),
    )
  )

  // This directive is used to wrap HTTP routes with metrics collection.
  // We need to pass the operation explicitly, which represents the OpenAPI operation ID.
  def withMetrics(service: String)(operation: String)(implicit tc: TraceContext): Directive0 = {
    import org.apache.pekko.http.scaladsl.server.Directives.*

    extractExecutionContext.flatMap { implicit ec =>
      extractRequest.flatMap { req =>
        val c = MetricsContext(Operation -> operation, HttpService -> service)
        implicit val mc: MetricsContext = req.headers
          .find(_.is(customSourceUiHeader)) // custom header added by the UI
          .map { header =>
            val sourceUi = header.value
            logger.debug(
              s"HTTP Request from UI($customSourceUiHeader: $sourceUi): service = $service, operation= $operation"
            )
            c.withExtraLabels(SourceUi -> sourceUi)
          }
          .getOrElse {
            logger.debug(
              s"HTTP Request no source UI header: service = $service, operation= $operation"
            )
            c
          }

        inFlightRequests.inc()
        val timing = requestTiming.startAsync()
        mapRouteResultFuture { resultFuture =>
          resultFuture.map[RouteResult] {
            case res @ RouteResult.Complete(response) =>
              timing.stop()(
                MetricsContext(
                  StatusCode -> response.status.intValue().toString,
                  Status -> "completed",
                )
              )
              res
            case res @ RouteResult.Rejected(_) =>
              timing.stop()(MetricsContext(Status -> "rejected"))
              res
          } andThen {
            case Failure(_) =>
              timing.stop()(MetricsContext(Status -> "failure"))
              inFlightRequests.dec()
            case Success(_) =>
              inFlightRequests.dec()
          }
        }
      }
    }
  }

}
