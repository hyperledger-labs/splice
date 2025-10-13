package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.environment.SpliceMetrics.MetricsPrefix
import org.lfdecentralizedtrust.splice.util.AmuletConfigUtil
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{Codec, TriggerTestUtil}

import com.digitalasset.canton.metrics.MetricValue

import scala.jdk.OptionConverters.*

class SvTimeBasedAmuletPriceIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment
    with AmuletConfigUtil {

  "amulet price votes and metrics" in { implicit env =>
    initDso()

    advanceTimeToRoundOpen
    advanceRoundsByOneTick

    val svParties =
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map {
        _.getDsoInfo().svParty.toProtoPrimitive
      }

    clue("initially only sv1 and sv2 have set the AmuletPriceVote") {
      // sv1 because it initialized the DSO and sv2 because we configured it to do so
      eventually() {
        checkPrices(svParties, Seq(Some(0.005), Some(0.005), None, None))
      }
    }

    clue("svs 2-4 vote on new prices") {
      sv2Backend.updateAmuletPriceVote(BigDecimal(0.3))
      sv3Backend.updateAmuletPriceVote(BigDecimal(0.1))
      sv4Backend.updateAmuletPriceVote(BigDecimal(0.2))
      advanceRoundsByOneTick
    }

    clue("Metrics are updated") {
      eventually() {
        checkPrices(svParties, Seq(Some(0.005), Some(0.3), Some(0.1), Some(0.2)))
      }
    }
  }

  private def checkPrices(svParties: Seq[String], prices: Seq[Option[Double]])(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    clue("Check individual SV votes") {
      svParties.zip(prices).foreach {
        case (sv, price) if price.isDefined =>
//          println(s"metric for $sv should exist:")
//          sv1Backend.metrics.list(
//            s"$MetricsPrefix.amulet_price.voted_price"
//          ).foreach{case (a, b) => println(s"Have a metric for $a: $b")}

          sv1Backend.metrics.list(
            s"$MetricsPrefix.amulet_price.voted_price",
            Map("sv" -> sv),
          ) should not be empty

//          println(s"metric for $sv should be $price:")
          sv1Backend.metrics
            .get(
              s"$MetricsPrefix.amulet_price.voted_price",
              Map("sv" -> sv),
            )
            .select[MetricValue.DoublePoint]
            .value
            .value shouldBe price.value

//          println("good")
        case (sv, _) =>
          sv1Backend.metrics.list(
            s"$MetricsPrefix.amulet_price.voted_price",
            Map("sv" -> sv),
          ) shouldBe empty
      }
    }

    clue("Check price in latest round") {
      val expectedRoundPrice = median(prices.filter(_.isDefined).map(p => BigDecimal(p.value)))
      sv1Backend.metrics
        .get(
          s"$MetricsPrefix.amulet_price.latest_open_round_price"
        )
        .select[MetricValue.DoublePoint]
        .value
        .value shouldBe expectedRoundPrice.value
    }
  }
}
