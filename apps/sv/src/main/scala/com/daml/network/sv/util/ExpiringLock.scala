package com.daml.network.sv.util

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext

import io.grpc.Status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{blocking, ExecutionContext, Future}

// TODO(#5855) remove this class and file

// This is roughly an RW lock where multiple R operations can
// acquire the lock concurrently but a W operation
// cannot acquire the operation concurrently with other W or R operations.
// Our operations don't correspond to reads/writes so we instead use "shared"/"exclusive"
private[sv] class ExpiringLock(
    expirationDuration: NonNegativeFiniteDuration,
    logger: TracedLogger,
)(implicit ec: ExecutionContext) {
  import ExpiringLock.*

  private val lockRef: AtomicReference[LockState] =
    new AtomicReference[LockState](LockState.Unlocked)

  def lockType(exclusive: Boolean) = if (exclusive) "exclusive" else "shared"

  def tryAcquire(reason: String, traceId: String, exclusive: Boolean)(implicit
      traceContext: TraceContext
  ): Future[Unit] = Future {
    logger.debug(s"Trying to acquire ${lockType(exclusive)} lock for $reason ($traceId)")
    val success = tryAcquire(exclusive)
    if (success) {
      logger.debug(s"Acquired lock ($traceId)")
    } else {
      logger.debug(s"Failed to acquire lock ($traceId)")
      throw Status.ABORTED.withDescription("Lock is not free").asRuntimeException()
    }
  }

  def release(reason: String, traceId: String, exclusive: Boolean)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    Future {
      release(exclusive)
      logger.debug(s"Released ${lockType(exclusive)} lock for $reason ($traceId)")
    }

  def withGlobalLock[T](
      reason: String,
      exclusive: Boolean,
  )(f: => Future[T])(implicit traceContext: TraceContext): Future[T] = {
    val traceId = UUID.randomUUID.toString
    tryAcquire(reason, traceId, exclusive).flatMap { _ =>
      f.transformWith(r => release(reason, traceId, exclusive).transform(rr => rr.flatMap(_ => r)))
    }
  }

  private def tryAcquire(exclusive: Boolean)(implicit traceContext: TraceContext): Boolean = {
    import LockState.{LockedState, Locked, Unlocked}
    blocking {
      synchronized {
        val oldLockState = lockRef.get
        val expiresAt = Instant.now().plus(expirationDuration.asJava)
        oldLockState match {
          case Unlocked =>
            lockRef.set(
              Locked(
                expiresAt,
                if (exclusive) LockedState.Exclusive else LockedState.Shared(PositiveInt.one),
              )
            )
            true
          case Locked(oldExpiresAt, state) =>
            val isExpired = oldExpiresAt.isBefore(Instant.now())
            state match {
              case LockedState.Shared(count) if !exclusive =>
                // No reason to worry about expiry here since there can be multiple shared lock holders
                // without issues.
                lockRef.set(Locked(expiresAt, LockedState.Shared(count + PositiveInt.one)))
                true
              case _ if exclusive && isExpired =>
                logger.warn(
                  s"Acquired expired lock held since ${oldExpiresAt.minus(expirationDuration.asJava)}, assuming that lock holder has died."
                )
                lockRef.set(Locked(expiresAt, LockedState.Exclusive))
                true
              case LockedState.Exclusive if isExpired =>
                logger.warn(
                  s"Acquired expired lock held since ${oldExpiresAt.minus(expirationDuration.asJava)}, assuming that lock holder has died."
                )
                lockRef.set(Locked(expiresAt, LockedState.Shared(PositiveInt.one)))
                true
              case _ =>
                false
            }
        }
      }
    }
  }

  private def release(exclusive: Boolean)(implicit traceContext: TraceContext): Unit = {
    import LockState.{LockedState, Locked, Unlocked}
    blocking {
      synchronized {
        val oldLockState = lockRef.get
        oldLockState match {
          case Unlocked =>
            logger.error("Released an already unlocked lock")
          case Locked(expiresAt, state) =>
            state match {
              case LockedState.Exclusive =>
                if (exclusive) {
                  lockRef.set(Unlocked)
                } else {
                  logger.error(
                    "Trying to release a shared lock but lock is locked as exclusive, likely the shared lock expired"
                  )
                }
              case LockedState.Shared(count) =>
                if (!exclusive) {
                  val newCount = PositiveInt.create(count.value - 1)
                  lockRef.set(
                    newCount.fold(_ => Unlocked, c => Locked(expiresAt, LockedState.Shared(c)))
                  )
                } else {
                  logger.error(
                    "Trying to release an exclusive lock but lock is locked as shared, likely the exclusive lock expired"
                  )
                }
            }
        }
      }
    }
  }
}

private object ExpiringLock {
  sealed abstract class LockState extends Product with Serializable

  object LockState {
    final case object Unlocked extends LockState
    final case class Locked(expiresAt: Instant, state: LockedState) extends LockState

    sealed abstract class LockedState

    object LockedState {
      final case object Exclusive extends LockedState
      final case class Shared(count: PositiveInt) extends LockedState
    }
  }
}
