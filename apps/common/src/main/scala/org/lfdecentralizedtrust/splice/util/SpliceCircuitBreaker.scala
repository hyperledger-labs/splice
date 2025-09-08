// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.pattern.CircuitBreaker
import org.lfdecentralizedtrust.splice.config.CircuitBreakerConfig

import scala.concurrent.ExecutionContext

object SpliceCircuitBreaker {

  def apply(
      name: String,
      config: CircuitBreakerConfig,
      logger: TracedLogger,
  )(implicit scheduler: Scheduler, ec: ExecutionContext): CircuitBreaker = {
    new CircuitBreaker(
      scheduler,
      maxFailures = config.maxFailures,
      callTimeout = config.callTimeout.underlying,
      resetTimeout = config.resetTimeout.underlying,
      maxResetTimeout = config.maxResetTimeout.underlying,
      exponentialBackoffFactor = config.exponentialBackoffFactor,
      randomFactor = config.randomFactor,
    ).onOpen {
      logger.warn(
        s"Circuit breaker $name tripped after ${config.maxFailures} failures"
      )(TraceContext.empty)
    }.onHalfOpen {
      logger.info(s"Circuit breaker $name moving to half-open state")(TraceContext.empty)
    }.onClose {
      logger.info(s"Circuit breaker $name moving to closed state")(TraceContext.empty)
    }
  }
}
