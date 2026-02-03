package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.config.DefaultProcessingTimeouts
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.util.DelayUtil
import com.digitalasset.canton.util.retry.AllExceptionRetryPolicy
import com.digitalasset.canton.util.retry.{Jitter, Success, Backoff}
import com.digitalasset.canton.{BaseTest, HasExecutorService}
import org.scalatest.Assertion
import org.scalatest.funspec.AsyncFunSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.Future

class PolicyTest extends AsyncFunSpec with BaseTest with HasExecutorService {
  val flagCloseable: FlagCloseable = FlagCloseable(logger, DefaultProcessingTimeouts.testing)
  describe("retry.Backoff") {
    val resetRetriesAfter = 250.millis
    val maxRetries = 3
    val exNumRuns = maxRetries * 2
    implicit val success: Success[org.scalatest.Assertion] = Success(Function const true)

    def testBackoff(atSpecial: Int, doAssert: => Assertion) = {
      val retried = new AtomicInteger(0)

      def run() = retried.incrementAndGet() match {
        case `maxRetries` =>
          DelayUtil
            .delay(resetRetriesAfter + 50.millis)
            .map(_ => fail("delayed to reset retries, but failed instead"))
        case `atSpecial` => Future(doAssert)
        case runs => Future(fail(s"made $runs runs instead of reaching $atSpecial runs"))
      }

      val maxDelay = 250.millis
      implicit val jitter: Jitter = Jitter.none(maxDelay)
      val policy =
        Backoff(
          logger,
          flagCloseable,
          maxRetries,
          maxDelay,
          maxDelay,
          "op",
          Some(resetRetriesAfter),
        )
      policy(run(), AllExceptionRetryPolicy)
    }

    it("should reset the retry counter after executing for resetRetriesAfter") {
      testBackoff(exNumRuns, succeed)
    }

    it("should still fail after reset if there was no further reset") {
      object ExpectedFailure extends RuntimeException("failed successfully")
      testBackoff(maxRetries * 2 + 1, throw ExpectedFailure).failed
        .map(_ shouldBe ExpectedFailure)
    }
  }
}
