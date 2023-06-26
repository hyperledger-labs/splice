package com.daml.network.sv.util

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext

import io.grpc.Status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{blocking, ExecutionContext, Future}

// TODO(#5855) remove this class and file
private[sv] class ExpiringLock(
    expirationDuration: NonNegativeFiniteDuration,
    logger: TracedLogger,
)(implicit ec: ExecutionContext) {
  private val lockRef: AtomicReference[LockState] = new AtomicReference[LockState](Unlocked)

  def tryAcquire(reason: String, traceId: String)(implicit
      traceContext: TraceContext
  ): Future[Unit] = Future {
    logger.debug(s"Trying to acquire lock for $reason ($traceId)")
    val success = tryAcquire()
    if (success) {
      logger.debug(s"Acquired lock ($traceId)")
    } else {
      logger.debug(s"Failed to acquire lock ($traceId)")
      throw Status.ABORTED.withDescription("Lock is not free").asRuntimeException()
    }
  }

  def release(reason: String, traceId: String)(implicit traceContext: TraceContext): Future[Unit] =
    Future {
      release()
      logger.debug(s"Released lock for $reason ($traceId)")
    }

  def withGlobalLock[T](
      reason: String
  )(f: => Future[T])(implicit traceContext: TraceContext): Future[T] = {
    val traceId = UUID.randomUUID.toString
    tryAcquire(reason, traceId).flatMap { _ =>
      f.transformWith(r => release(reason, traceId).transform(rr => rr.flatMap(_ => r)))
    }
  }

  private def tryAcquire()(implicit traceContext: TraceContext): Boolean = {
    blocking {
      // we probably don't need this but we also never want to have to debug this code again
      synchronized {
        val oldLockState = lockRef.get
        val newLockState = LockState(locked = true, Instant.now().plus(expirationDuration.asJava))
        if (oldLockState == Unlocked) {
          lockRef.compareAndSet(oldLockState, newLockState)
        } else if (
          oldLockState.expiresAt
            .isBefore(Instant.now()) && lockRef.compareAndSet(oldLockState, newLockState)
        ) {
          logger.warn(
            s"Acquired expired lock held since ${oldLockState.expiresAt.minus(expirationDuration.asJava)}, assuming that lock holder has died."
          )
          true
        } else false
      }
    }
  }

  private def release()(implicit traceContext: TraceContext): Unit = {
    blocking {
      // we probably don't need this we but also never want to have to debug this code
      synchronized {
        val oldLockState = lockRef.getAndSet(Unlocked)
        if (oldLockState == Unlocked) {
          logger.error("Released an already unlocked lock")
        } else if (oldLockState.expiresAt.isBefore(Instant.now())) {
          logger.warn(
            s"Released an expired lock held since ${oldLockState.expiresAt.minus(expirationDuration.asJava)}."
          )
        }
      }
    }
  }
}
private case class LockState(locked: Boolean, expiresAt: Instant);
private object Unlocked extends LockState(locked = false, Instant.MIN)
