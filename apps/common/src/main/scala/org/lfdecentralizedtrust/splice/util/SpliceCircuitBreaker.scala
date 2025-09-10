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
import org.apache.pekko.pattern.{CircuitBreaker, CircuitBreakerOpenException}
import org.lfdecentralizedtrust.splice.config.CircuitBreakerConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SpliceCircuitBreaker(name: String, underlying: CircuitBreaker)(implicit
    ec: ExecutionContext
) {

  private val errorCategoriesToIgnore: Set[ErrorCategory] = Set(
    InvalidIndependentOfSystemState,
    InvalidGivenCurrentSystemStateOther,
    InvalidGivenCurrentSystemStateResourceExists,
    InvalidGivenCurrentSystemStateResourceMissing,
    InvalidGivenCurrentSystemStateSeekAfterEnd,
  )

  def withCircuitBreaker[T](body: => Future[T]): Future[T] = {
    if (underlying.isClosed || underlying.isHalfOpen) {
      callAndMark(body)
    } else {
      Future.failed(
        new CircuitBreakerOpenException(
          underlying.resetTimeout,
          s"Circuit breaker $name is open, calls are failing fast",
        )
      )
    }
  }

  private def callAndMark[T](body: => Future[T]) = {
    body.andThen {
      case Failure(exception) =>
        if (!isFailureIgnored(exception)) {
          underlying.fail()
        }
      case Success(_) => underlying.succeed()
    }
  }

  private def isFailureIgnored[T](result: Throwable): Boolean = {
    result match {
      case ex: StatusRuntimeException =>
        ErrorDetails
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
      case _ => false
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
      name,
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
      },
    )
}
