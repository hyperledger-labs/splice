package com.daml.network.util

import com.digitalasset.canton.config.RequireTypes.{NonNegativeNumeric, PositiveNumeric}
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.{BaseTestWordSpec, HasExecutionContext}
import org.scalatest.prop.TableFor2

import java.time.Duration
import scala.annotation.tailrec

class RateLimiterWithExtraTrafficTest extends BaseTestWordSpec with HasExecutionContext {

  lazy val testCases: TableFor2[Double, Int] = Table(
    ("maxTasksPerSecond", "initialBurst"),
    (0.5, 1),
    (1, 1),
    (9, 5),
    (10, 1),
    (100, 10),
    (200, 20),
  )

  @tailrec
  private def go(
      limiter: RateLimiterWithExtraTraffic,
      extraTrafficLimit: Double,
      clock: SimClock,
      deltaNanos: Long,
      total: Int,
      success: Int,
  ): Int = {
    def submitAndReturnOneOnSuccess(limiter: RateLimiterWithExtraTraffic): Int =
      if (limiter.checkAndUpdate(NonNegativeNumeric.tryCreate(extraTrafficLimit))) 1 else 0
    if (total == 0) {
      success
    } else {
      clock.advance(duration = Duration.ofNanos(deltaNanos))
      go(
        limiter,
        extraTrafficLimit,
        clock,
        deltaNanos,
        total - 1,
        success + submitAndReturnOneOnSuccess(limiter),
      )
    }
  }

  private def deltaNanos(ratePerSecond: Double): Long = {
    ((1.0 / ratePerSecond) * 1e9).toLong
  }

  private def testRun(
      maxTasksPerSecond: Double,
      throttle: Double,
      initialBurst: Int,
      extraTrafficLimit: Double,
      simTimeInSecs: Int = 100,
  ): (Int, Int) = {
    val taskRate = maxTasksPerSecond / throttle
    val clock = new SimClock(loggerFactory = loggerFactory)
    val limiter = new RateLimiterWithExtraTraffic(
      NonNegativeNumeric.tryCreate(maxTasksPerSecond),
      PositiveNumeric.tryCreate(Math.max(initialBurst.toDouble / maxTasksPerSecond, 1e-6)),
      PositiveNumeric.tryCreate(1.0),
      clock,
    )
    val total = (simTimeInSecs * taskRate).toInt
    val deltaT = deltaNanos(taskRate)
    val burst = (1 to initialBurst).count(_ =>
      limiter.checkAndUpdate(NonNegativeNumeric.tryCreate(extraTrafficLimit))
    )
    burst shouldBe initialBurst
    val res = go(limiter, extraTrafficLimit, clock, deltaT, total, 0)
    (total, res)
  }

  "A decay rate limiter" when {
    testCases.forEvery { case (maxTasksPerSecond, initialBurst) =>
      s"the maximum rate is $maxTasksPerSecond" must {
        "submission below max will never be rejected" in {
          val (total, res) = testRun(maxTasksPerSecond, 2, initialBurst, 0)
          res shouldBe total
        }

        "submission above max will be throttled to max if no extra traffic is available" in {
          val (total, res) = testRun(maxTasksPerSecond, 0.25, initialBurst, 0)
          res.toDouble should be > (total * 0.24)
          res.toDouble should be < (total * 0.26)
        }

        "submission above max will not be throttled if enough extra traffic is available" in {
          val throttle = 0.25
          val taskRate = maxTasksPerSecond / throttle
          val simTimeInSecs = 100
          val extraTraffic = (1.01 - throttle) * taskRate * simTimeInSecs
          val (total, res) =
            testRun(maxTasksPerSecond, throttle, initialBurst, extraTraffic, simTimeInSecs)
          res shouldBe total
        }

        "submission above max will be throttled after extra traffic gets consumed" in {
          val throttle = 0.25
          val taskRate = maxTasksPerSecond / throttle
          val simTimeInSecs = 100
          val extraTraffic = 0.25 * taskRate * simTimeInSecs
          val (total, res) =
            testRun(maxTasksPerSecond, throttle, initialBurst, extraTraffic, simTimeInSecs)
          res.toDouble should be > (total * 0.49)
          res.toDouble should be < (total * 0.51)
        }
      }
    }
  }

  "zero is zero" in {
    val limiter = new RateLimiterWithExtraTraffic(
      NonNegativeNumeric.tryCreate(0.0),
      PositiveNumeric.tryCreate(1.0),
      PositiveNumeric.tryCreate(1.0),
      new SimClock(loggerFactory = loggerFactory),
    )
    assert(!limiter.checkAndUpdate(NonNegativeNumeric.tryCreate(0.0)))
  }

}
