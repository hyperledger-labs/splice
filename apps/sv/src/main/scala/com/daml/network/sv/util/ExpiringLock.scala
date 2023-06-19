package com.daml.network.sv.util

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

// TODO(#5855) remove this class and file
private[sv] class ExpiringLock(
    expirationDuration: NonNegativeFiniteDuration,
    logger: TracedLogger,
) {
  private val lockRef: AtomicReference[LockState] = new AtomicReference[LockState](Unlocked)

  def tryAcquire()(implicit traceContext: TraceContext): Boolean = {
    val newLockState = LockState(locked = true, Instant.now().plus(expirationDuration.asJava))
    lockRef.compareAndSet(Unlocked, newLockState) || {
      val oldLockState = lockRef.get
      if (
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

  def release()(implicit traceContext: TraceContext): Unit = {
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
private case class LockState(locked: Boolean, expiresAt: Instant);
private object Unlocked extends LockState(locked = false, Instant.MIN)
