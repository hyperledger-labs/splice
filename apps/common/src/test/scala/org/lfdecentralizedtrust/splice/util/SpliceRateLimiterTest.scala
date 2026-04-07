package org.lfdecentralizedtrust.splice.util

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.testing.{InMemoryMetricsFactory, MetricValues}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.util.SpliceRateLimiterTest.runRateLimited
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SpliceRateLimiterTest
    extends BaseTest
    with AnyWordSpecLike
    with HasActorSystem
    with HasExecutionContext
    with MetricValues {

  private val elementsToRun = 100

  "the rate limiter" should {

    "accept requests under limit" in {
      withRateLimiter { case (rateLimitMetrics, rateLimiter) =>
        runThroughRateLimiter(rateLimiter, 9).reduce(_ && _) shouldBe true

        rateLimitMetrics.meter.valueFilteredOnLabels(
          LabelFilter(
            "result",
            "accepted",
          ),
          LabelFilter(
            "limiter",
            "test",
          ),
        ) shouldBe elementsToRun
      }
    }

    "reject requests that are over the limit" in {
      withRateLimiter { case (rateLimitMetrics, rateLimiter) =>
        val results = runThroughRateLimiter(rateLimiter, 11)

        val (accepted, rejected) = results.partition(identity)

        // estimate for running 9 seconds
        accepted.length should (be > 85 and be < 95)

        rateLimitMetrics.meter.valueFilteredOnLabels(
          LabelFilter(
            "result",
            "accepted",
          ),
          LabelFilter(
            "limiter",
            "test",
          ),
        ) should be(accepted.length)

        rateLimitMetrics.meter.valueFilteredOnLabels(
          LabelFilter(
            "result",
            "rejected",
          ),
          LabelFilter(
            "limiter",
            "test",
          ),
        ) should be(rejected.length)
      }

    }

  }

  private def runThroughRateLimiter(rateLimiter: SpliceRateLimiter, runsPerSecond: Int) = {
    runRateLimited(
      runsPerSecond,
      elementsToRun,
    ) {
      rateLimiter
        .runWithLimit(Future.successful(true))
    } futureValue
  }

  private def withRateLimiter[A](f: (SpliceRateLimitMetrics, SpliceRateLimiter) => A): A = {
    val metricsFactory = new InMemoryMetricsFactory()
    val rateLimitMetrics = SpliceRateLimitMetrics(metricsFactory, logger)(MetricsContext.Empty)
    val rateLimiter = new SpliceRateLimiter(
      "test",
      SpliceRateLimitConfig(enabled = true, 10),
      rateLimitMetrics,
    )
    try {
      f(rateLimitMetrics, rateLimiter)
    } finally {
      rateLimitMetrics.close()
    }
  }
}

object SpliceRateLimiterTest {

  def runRateLimited(runRate: Int, elementsToRun: Int)(
      run: => Future[?]
  )(implicit
      mat: Materializer
  ): Future[Seq[Boolean]] = {
    import mat.executionContext
    Source
      .repeat(())
      .take(elementsToRun.longValue())
      .throttle(runRate, 1.second)
      .mapAsync(elementsToRun)(_ =>
        run
          .map(_ => true)
          .recover {
            case rejection: StatusRuntimeException
                if rejection.getStatus.getCode == Status.Code.RESOURCE_EXHAUSTED =>
              false
            case failure: HttpCommandException if failure.status == StatusCodes.TooManyRequests =>
              false
            // match the raw command failure because it hides the root cause
            // should be enough because we assert on the number of successes vs failures
            case _: CommandFailure =>
              false
          }
      )
      // throttle after as well to ensure that even for runs that take a while to execute we still keep the rate
      .throttle(runRate, 1.second)
      .runWith(Sink.seq)
  }

}
