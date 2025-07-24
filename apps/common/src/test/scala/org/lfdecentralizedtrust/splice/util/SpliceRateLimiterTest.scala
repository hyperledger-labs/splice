package org.lfdecentralizedtrust.splice.util

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.testing.{InMemoryMetricsFactory, MetricValues}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.console.CommandFailure
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.testkit.StreamSpec
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.util.SpliceRateLimiterTest.runRateLimited

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SpliceRateLimiterTest extends StreamSpec with BaseTest with MetricValues {

  override val patience: PatienceConfig = defaultPatience

  private val elementsToRun = 100

  "the rate limiter" should {

    "accept requests under limit" in {
      val (rateLimitMetrics, rateLimiter) = newRateLimiter

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

    "reject requests that are over the limit" in {
      val (rateLimitMetrics, rateLimiter) = newRateLimiter

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

  private def runThroughRateLimiter(rateLimiter: SpliceRateLimiter, runsPerSecond: Int) = {
    runRateLimited(
      runsPerSecond,
      elementsToRun,
    ) {
      rateLimiter
        .runWithLimit(Future.successful(true))
    } futureValue
  }

  private def newRateLimiter = {
    val metricsFactory = new InMemoryMetricsFactory()
    val rateLimitMetrics = SpliceRateLimitMetrics(metricsFactory)(MetricsContext.Empty)
    val rateLimiter = new SpliceRateLimiter(
      "test",
      SpliceRateLimitConfig(10),
      rateLimitMetrics,
    )
    rateLimitMetrics -> rateLimiter
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
      .runWith(Sink.seq)
  }

}
