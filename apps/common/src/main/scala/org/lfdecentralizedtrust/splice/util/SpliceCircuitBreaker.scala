// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.base.error.ErrorCategory
import com.digitalasset.base.error.ErrorCategory.{
  InvalidGivenCurrentSystemStateOther,
  InvalidGivenCurrentSystemStateResourceExists,
  InvalidGivenCurrentSystemStateResourceMissing,
  InvalidGivenCurrentSystemStateSeekAfterEnd,
  InvalidIndependentOfSystemState,
}
import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.StatusRuntimeException
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.pattern.CircuitBreaker
import org.lfdecentralizedtrust.splice.config.CircuitBreakerConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class SpliceCircuitBreaker(underlying: CircuitBreaker) {

  private val errorCategoriesToIgnore: Set[ErrorCategory] = Set(
    InvalidIndependentOfSystemState,
    InvalidGivenCurrentSystemStateOther,
    InvalidGivenCurrentSystemStateResourceExists,
    InvalidGivenCurrentSystemStateResourceMissing,
    InvalidGivenCurrentSystemStateSeekAfterEnd,
  )

  def withCircuitBreaker[T](body: => Future[T]): Future[T] =
    underlying.withCircuitBreaker[T](
      body,
      isResultIgnored,
    )

  private def isResultIgnored[T](result: Try[T]): Boolean = {
    result match {
      case Failure(ex: StatusRuntimeException) =>
        val isIgnoredCategory = ErrorDetails
          .from(ex)
          .collect {
            case ErrorDetails.ErrorInfoDetail(_, metadata) if metadata.contains("category") =>
              metadata
                .get("category")
                .flatMap(_.toIntOption)
                .flatMap(ErrorCategory.fromInt)
          }
          .flatten
          .exists(failureCategory => errorCategoriesToIgnore.contains(failureCategory))
        !isIgnoredCategory
      case Failure(_) =>
        true
      case Success(_) => false
    }
  }

  def isOpen: Boolean = underlying.isOpen
  def isClosed: Boolean = underlying.isClosed
  def isHalfOpen: Boolean = underlying.isHalfOpen

}

object SpliceCircuitBreaker {

  def apply(
      name: String,
      config: CircuitBreakerConfig,
      logger: TracedLogger,
  )(implicit scheduler: Scheduler, ec: ExecutionContext): SpliceCircuitBreaker =
    new SpliceCircuitBreaker(
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
    )
}
