package org.lfdecentralizedtrust.splice.util

import com.digitalasset.base.error.ErrorCode
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.participant.protocol.TransactionProcessor.SubmissionErrors
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import io.grpc.StatusRuntimeException
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.pattern.CircuitBreakerOpenException
import org.lfdecentralizedtrust.splice.config.CircuitBreakerConfig
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

class SpliceCircuitBreakerTest
    extends AnyWordSpec
    with BaseTest
    with ScalaFutures
    with HasActorSystem
    with HasExecutionContext {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(10, Millis))

  private implicit val scheduler: Scheduler = actorSystem.scheduler

  private def createCircuitBreaker(): SpliceCircuitBreaker = {
    val config = CircuitBreakerConfig(
      maxFailures = 2,
      callTimeout = NonNegativeFiniteDuration.ofSeconds(1),
      resetTimeout = NonNegativeFiniteDuration.ofMillis(100),
      maxResetTimeout = NonNegativeFiniteDuration.ofSeconds(1),
      exponentialBackoffFactor = 1.0,
      randomFactor = 0.0,
    )
    SpliceCircuitBreaker("test", config, logger)
  }

  "SpliceCircuitBreaker" should {
    "open after maxFailures non-ignored errors" in {
      val cb = createCircuitBreaker()

      val future1 = cb.withCircuitBreaker(Future.failed(new RuntimeException("test failure 1")))
      whenReady(future1.failed) { ex =>
        ex shouldBe a[RuntimeException]
        cb.isClosed shouldBe true
      }
      loggerFactory.suppressWarnings {
        val future2 = cb.withCircuitBreaker(Future.failed(new RuntimeException("test failure 2")))
        whenReady(future2.failed) { ex =>
          ex shouldBe a[RuntimeException]
          ex.getMessage shouldBe "test failure 2"
          cb.isOpen shouldBe true
        }
      }

      val future3 = cb.withCircuitBreaker(Future.successful("should not reach here"))
      whenReady(future3.failed) { ex =>
        ex shouldBe a[CircuitBreakerOpenException]
        cb.isOpen shouldBe true
      }
    }

    "not open after failures with ignored error categories" in {
      val cb = createCircuitBreaker()

      val ignoredError =
        SubmissionErrors.PackageNotVettedByRecipients.Error(Seq.empty)
      val exception = ErrorCode.asGrpcError(ignoredError)
      1 to 20 foreach { _ =>
        val future = cb.withCircuitBreaker(Future.failed(exception))

        whenReady(future.failed) { ex =>
          ex shouldBe a[StatusRuntimeException]
          cb.isClosed shouldBe true
        }
      }
    }

    "open after mixed ignored and non-ignored error categories" in {
      val cb = createCircuitBreaker()

      val ignoredError = SubmissionErrors.PackageNotVettedByRecipients.Error(Seq.empty)
      val ignoredException = ErrorCode.asGrpcError(ignoredError)
      val future1 = cb.withCircuitBreaker(Future.failed(ignoredException))
      whenReady(future1.failed) { ex =>
        ex shouldBe a[StatusRuntimeException]
        cb.isClosed shouldBe true
      }

      val nonIgnoredError =
        SubmissionErrors.SequencerBackpressure.Rejection("Sequencer is overloaded")

      val exception1 = ErrorCode.asGrpcError(nonIgnoredError)
      val future2 = cb.withCircuitBreaker(Future.failed(exception1))
      whenReady(future2.failed) { ex =>
        ex shouldBe a[StatusRuntimeException]
        cb.isClosed shouldBe true
      }

      val exception2 = ErrorCode.asGrpcError(nonIgnoredError)
      val future3 = cb.withCircuitBreaker(Future.failed(exception2))
      loggerFactory.suppressWarnings {
        whenReady(future3.failed) { ex =>
          ex shouldBe a[StatusRuntimeException]
          cb.isOpen shouldBe true
        }
      }

      val future4 = cb.withCircuitBreaker(Future.successful("should not reach here"))
      whenReady(future4.failed) { ex =>
        ex shouldBe a[CircuitBreakerOpenException]
        cb.isOpen shouldBe true
      }
    }

    "do not close circuit breaker when ignored failures occur" in {
      val cb = createCircuitBreaker()

      val nonIgnoredError =
        SubmissionErrors.SequencerBackpressure.Rejection("Sequencer is overloaded")
      val nonIgnoredException = ErrorCode.asGrpcError(nonIgnoredError)

      loggerFactory.suppressWarnings {
        for (_ <- 1 to 2) {
          val future = cb.withCircuitBreaker(Future.failed(nonIgnoredException))
          whenReady(future.failed) { ex =>
            ex shouldBe a[StatusRuntimeException]
          }
        }
      }

      cb.isOpen shouldBe true

      eventually() {
        cb.isHalfOpen shouldBe true
      }

      val ignoredError = SubmissionErrors.PackageNotVettedByRecipients.Error(Seq.empty)
      val ignoredException = ErrorCode.asGrpcError(ignoredError)

      val ignoredFuture = cb.withCircuitBreaker(Future.failed(ignoredException))
      whenReady(ignoredFuture.failed) { ex =>
        ex shouldBe a[StatusRuntimeException]
      }

      cb.isHalfOpen shouldBe true

      val successFuture = cb.withCircuitBreaker(Future.successful("success"))
      whenReady(successFuture) { result =>
        result shouldBe "success"
      }

      cb.isClosed shouldBe true
    }
  }
}
