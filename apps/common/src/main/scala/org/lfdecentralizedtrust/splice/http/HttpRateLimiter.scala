// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricsContext
import org.apache.pekko.http.scaladsl.model.{HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directive0
import org.lfdecentralizedtrust.splice.config.RateLimitersConfig
import org.lfdecentralizedtrust.splice.util.{SpliceRateLimitMetrics, SpliceRateLimiter}

class HttpRateLimiter(
    config: RateLimitersConfig,
    metricsFactory: LabeledMetricsFactory,
) {

  def withRateLimit(service: String)(operation: String): Directive0 = {
    val rateLimiter = new SpliceRateLimiter(
      operation,
      config.forRateLimiter(operation),
      SpliceRateLimitMetrics(metricsFactory)(
        MetricsContext(
          "http_service" -> service
        )
      ),
    )

    import org.apache.pekko.http.scaladsl.server.Directives.*

    if (rateLimiter.markRun()) {
      pass
    } else {
      complete(
        StatusCodes.TooManyRequests,
        HttpEntity(
          "Server is busy, please try again later."
        ),
      )
    }

  }
}
