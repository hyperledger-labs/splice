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
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.StatusRuntimeException
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.pattern.{CircuitBreaker, CircuitBreakerOpenException}
import org.lfdecentralizedtrust.splice.config.CircuitBreakerConfig

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SpliceCircuitBreaker(
    name: String,
    config: CircuitBreakerConfig,
    clock: Clock,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    scheduler: Scheduler,
) extends NamedLogging {

  private val lastFailure: AtomicReference[Option[CantonTimestamp]] = new AtomicReference(None)

  private val errorCategoriesToIgnore: Set[ErrorCategory] = Set(
    InvalidIndependentOfSystemState,
    InvalidGivenCurrentSystemStateOther,
    InvalidGivenCurrentSystemStateResourceExists,
    InvalidGivenCurrentSystemStateResourceMissing,
    InvalidGivenCurrentSystemStateSeekAfterEnd,
  )

  val underlying = new CircuitBreaker(
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

  def withCircuitBreaker[T](body: => Future[T])(implicit tc: TraceContext): Future[T] = {
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

  private def callAndMark[T](body: => Future[T])(implicit tc: TraceContext) = {
    lastFailure.updateAndGet(_.filter { lastFailureTime =>
      val elapsed = clock.now - lastFailureTime
      if (elapsed.compareTo(config.resetFailuresAfter.asJava) >= 0) {
        // Note: We only reset in callAndMark so this does not apply if the circuit breaker is already open.
        // This is deliberate, in that case we want to wait for resetTimeout not resetFailuresAfter.
        logger.info(
          s"Resetting circuit breaker as last failure was $elapsed ago which is more than ${config.resetFailuresAfter}"
        )
        underlying.succeed()
        false
      } else {
        true
      }
    })

    body.andThen {
      case Failure(exception) =>
        if (!isFailureIgnored(exception)) {
          underlying.fail()
          lastFailure.set(Some(clock.now))
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
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
  )(implicit scheduler: Scheduler, ec: ExecutionContext): SpliceCircuitBreaker =
    new SpliceCircuitBreaker(
      name,
      config,
      clock,
      loggerFactory,
    )
}
