// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.environment.RetryProvider
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import io.grpc.Status

import scala.concurrent.{blocking, ExecutionContext, Future, Promise}

abstract class DomainTimeSynchronization {

  /** Blocks until the domain time delay is sufficiently small
    */
  def waitForDomainTimeSync()(implicit tc: TraceContext): Future[Unit]
}

object DomainTimeSynchronization {
  final class NoopDomainTimeSynchronization extends DomainTimeSynchronization {
    override def waitForDomainTimeSync()(implicit tc: TraceContext): Future[Unit] = Future.unit
  }

  val Noop = new NoopDomainTimeSynchronization()
}

final class DomainTimeStore(
    clock: Clock,
    allowedDomainTimeDelay: PositiveFiniteDuration,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends DomainTimeSynchronization
    with AutoCloseable
    with NamedLogging {

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var state: DomainTimeStore.State = DomainTimeStore.State(
    None,
    None,
  )
  private val mutex = Mutex()

  override def waitForDomainTimeSync()(implicit tc: TraceContext): Future[Unit] = {
    val promiseO = checkDomainTimeDelay()
    promiseO match {
      case None => Future.unit
      case Some(promise) =>
        retryProvider
          .waitUnlessShutdown(promise.future)
          .failOnShutdownTo {
            Status.UNAVAILABLE
              .withDescription(
                s"Aborted waitForDomainTimeSync, as RetryProvider(${retryProvider.loggerFactory.properties}) is shutting down"
              )
              .asRuntimeException()
          }
    }
  }

  private def checkDomainTimeDelay()(implicit tc: TraceContext): Option[Promise[Unit]] = blocking {
    mutex.exclusive {
      val now = clock.now
      state.lastDomainTime match {
        // If we are just starting up, try to submit something to not delay startup.
        // We'll block on the next update if we're actually lagging behind.
        case None =>
          None
        case Some(domainTime) =>
          val currentDelay = now - domainTime
          if (currentDelay.compareTo(allowedDomainTimeDelay.asJava) <= 0) {
            None
          } else {
            logger.info(
              show"Domain time delay is currently $currentDelay ($now - $domainTime), waiting until delay is below ${allowedDomainTimeDelay}. This is expected if the node restored from backup"
            )
            state.delayPromise match {
              case Some(promise) => Some(promise)
              case None =>
                val promise = Promise[Unit]()
                state = state.copy(delayPromise = Some(promise))
                Some(promise)
            }
          }
      }
    }
  }

  def ingestDomainTime(time: CantonTimestamp)(implicit tc: TraceContext): Future[Unit] = Future {
    blocking {
      mutex.exclusive {
        val now = clock.now
        val newState = state.copy(lastDomainTime = Some(time))
        state.delayPromise match {
          case None =>
            state = newState
          case Some(promise) =>
            val delay = now - time
            if (delay.compareTo(allowedDomainTimeDelay.asJava) <= 0) {
              logger.info(
                show"Domain time delay is now $delay ($now - $time) which is below the configured max delay ${allowedDomainTimeDelay}"
              )
              promise.success(())
              state = newState.copy(delayPromise = None)
            } else {
              logger.info(
                show"Domain time delay is now $delay ($now - $time) which is still above the configured max delay ${allowedDomainTimeDelay}"
              )
              state = newState
            }
        }
      }
    }
  }

  override def close(): Unit = ()
}

object DomainTimeStore {

  private final case class State(
      lastDomainTime: Option[CantonTimestamp],
      delayPromise: Option[Promise[Unit]],
  )
}
