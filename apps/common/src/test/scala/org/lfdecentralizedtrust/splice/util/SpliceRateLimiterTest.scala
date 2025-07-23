package org.lfdecentralizedtrust.splice.util

import com.daml.metrics.api.testing.{InMemoryMetricsFactory, MetricValues}
import com.digitalasset.canton.BaseTest
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.testkit.StreamSpec

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
      accepted.length should (be > 88 and be < 92)

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

  private def runThroughRateLimiter(rateLimiter: SpliceRateLimiter, runRate: Int) = {
    Source
      .repeat(())
      .throttle(runRate, 1.second)
      .take(elementsToRun.longValue())
      .mapAsync(50)(_ =>
        rateLimiter
          .runWithLimit(Future.successful(true))
          .recover {
            case rejection: StatusRuntimeException
                if rejection.getStatus.getCode == Status.Code.RESOURCE_EXHAUSTED =>
              false
          }(system.dispatcher)
      )
      .runWith(Sink.seq)
      .futureValue
  }

  private def newRateLimiter = {
    val metricsFactory = new InMemoryMetricsFactory()
    val rateLimitMetrics = SpliceRateLimitMetrics(metricsFactory)
    val rateLimiter = new SpliceRateLimiter(
      "test",
      SpliceRateLimitConfig(10),
      rateLimitMetrics,
    )
    rateLimitMetrics -> rateLimiter
  }
}
