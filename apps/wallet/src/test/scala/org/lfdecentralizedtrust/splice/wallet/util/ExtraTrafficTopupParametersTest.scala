package org.lfdecentralizedtrust.splice.wallet.util

import com.digitalasset.canton.BaseTestWordSpec
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import org.scalatest.prop.TableFor2

import scala.math.BigDecimal.RoundingMode

class ExtraTrafficTopupParametersTest extends BaseTestWordSpec {
  private lazy val defaultMinTopupAmount = 1_000_000L
  private lazy val defaultTargetRate = BigDecimal(20_000)
  private lazy val defaultMinTopupInterval = NonNegativeFiniteDuration.ofMinutes(10)
  private lazy val defaultTriggerPollingInterval = NonNegativeFiniteDuration.ofMinutes(2)

  // check that the rounding of values works correctly with different orders of magnitude
  // of the trigger polling interval from milliseconds to minutes.
  private lazy val intervals: TableFor2[NonNegativeFiniteDuration, NonNegativeFiniteDuration] =
    Table(
      ("triggerPollingInterval", "minTopupInterval"),
      (NonNegativeFiniteDuration.ofMillis(200), NonNegativeFiniteDuration.ofMillis(500)),
      (NonNegativeFiniteDuration.ofMillis(200), NonNegativeFiniteDuration.ofSeconds(5)),
      (NonNegativeFiniteDuration.ofMillis(200), NonNegativeFiniteDuration.ofMinutes(5)),
      (NonNegativeFiniteDuration.ofSeconds(2), NonNegativeFiniteDuration.ofSeconds(5)),
      (NonNegativeFiniteDuration.ofSeconds(2), NonNegativeFiniteDuration.ofMinutes(5)),
      (NonNegativeFiniteDuration.ofMinutes(2), NonNegativeFiniteDuration.ofMinutes(5)),
    )

  private def mkExtraTrafficTopupParameters(
      minTopupInterval: NonNegativeFiniteDuration = defaultMinTopupInterval,
      triggerPollingInterval: NonNegativeFiniteDuration = defaultTriggerPollingInterval,
      minTopupAmount: Long = defaultMinTopupAmount,
      targetRate: BigDecimal = defaultTargetRate,
  ) = {
    ExtraTrafficTopupParameters(
      NonNegativeNumeric.tryCreate(targetRate),
      minTopupInterval,
      minTopupAmount,
      triggerPollingInterval,
    )
  }

  private def checkTopupParameters(
      topupParameters: ExtraTrafficTopupParameters,
      minTopupInterval: NonNegativeFiniteDuration,
      triggerPollingInterval: NonNegativeFiniteDuration = defaultTriggerPollingInterval,
      targetRate: BigDecimal = defaultTargetRate,
      minTopupAmount: Long = defaultMinTopupAmount,
  ) = {
    topupParameters.topupAmount should be >= minTopupAmount
    topupParameters.topupAmount should be >= (targetRate / 1e3 * topupParameters.minTopupInterval.duration.toMillis)
      .setScale(0, RoundingMode.CEILING)
      .toLong
    topupParameters.minTopupInterval.duration.toMillis >= triggerPollingInterval.duration.toMillis
    topupParameters.minTopupInterval.duration.toMillis >= minTopupInterval.duration.toMillis
  }

  "Extra traffic top-up parameters" when {
    "the min top-up interval is configured to be less than the trigger polling interval" should {
      "behave as if the min top-up interval equals the polling interval" in {
        val topupParams1 = mkExtraTrafficTopupParameters(
          minTopupInterval = defaultTriggerPollingInterval * 0.5
        )
        val topupParams2 = mkExtraTrafficTopupParameters(
          minTopupInterval = defaultTriggerPollingInterval
        )
        checkTopupParameters(
          topupParams1,
          minTopupInterval = defaultTriggerPollingInterval,
        )
        topupParams1 shouldBe topupParams2
      }
    }

    "target rate of zero" should {
      "not require any top-up at all" in {
        val topupParams1 = mkExtraTrafficTopupParameters(targetRate = 0)
        topupParams1.topupAmount shouldBe 0
      }
    }

    "min top-up amount is relatively low" should {
      "provide enough extra traffic to last till next top-up" in {
        val minTopupAmount = 4_000_000L
        val targetRate = BigDecimal(10_000)
        val triggerPollingInterval = NonNegativeFiniteDuration.ofMinutes(2)

        // Note that the next top-up will occur the first time the trigger runs after
        // 1 min top-up interval has elapsed.

        // min top-up interval is an exact multiple of trigger polling interval
        // next top-up will happen after 4 polling intervals = 8 mins
        val topupParams1 = mkExtraTrafficTopupParameters(
          minTopupAmount = minTopupAmount,
          targetRate = targetRate,
          triggerPollingInterval = triggerPollingInterval,
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(7),
        )
        // min top-up interval is not an exact mutliple of trigger polling interval
        // here too, next top-up will occur after 4 polling intervals = 8 mins
        val topupParams2 = mkExtraTrafficTopupParameters(
          minTopupAmount = minTopupAmount,
          targetRate = targetRate,
          triggerPollingInterval = triggerPollingInterval,
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(8),
        )
        val topupIntervalSecs = NonNegativeFiniteDuration.ofMinutes(8).duration.toSeconds
        // Note that by min top-up amount being "relatively low", we mean
        // minTopupAmount <= targetRate * topupInterval
        //                   = 10_000 * 8 * 60 = 4.8MB
        topupParams1.topupAmount shouldBe targetRate * topupIntervalSecs
        topupParams1.minTopupInterval.duration.toSeconds shouldBe topupIntervalSecs
        checkTopupParameters(
          topupParams1,
          targetRate = targetRate,
          minTopupAmount = minTopupAmount,
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(8),
          triggerPollingInterval = triggerPollingInterval,
        )
        topupParams1 shouldBe topupParams2
      }
    }

    "min top-up amount is relatively high" should {
      "adjust top-up interval to deliver configured target rate" in {
        // Set minTopupAmount in this case to be > 4.8MB
        val minTopupAmount = 5_000_000L
        val targetRate = BigDecimal(10_000)
        val minTopupInterval = NonNegativeFiniteDuration.ofMinutes(7)
        val triggerPollingInterval = NonNegativeFiniteDuration.ofMinutes(2)

        val topupParams = mkExtraTrafficTopupParameters(
          minTopupAmount = minTopupAmount,
          targetRate = targetRate,
          triggerPollingInterval = triggerPollingInterval,
          minTopupInterval = minTopupInterval,
        )
        // topupInterval in this case would be derived from the minTopupAmount as
        // minTopupAmount / targetRate ~= 8.3 mins
        // the actual topupInterval would then be this value rounded up to the nearest
        // multiple of the polling interval = 10 mins
        val topupIntervalSecs = NonNegativeFiniteDuration.ofMinutes(10).duration.toSeconds
        topupParams.topupAmount shouldBe targetRate * topupIntervalSecs
        topupParams.minTopupInterval.duration.toSeconds shouldBe topupIntervalSecs
        checkTopupParameters(
          topupParams,
          targetRate = targetRate,
          minTopupAmount = minTopupAmount,
          minTopupInterval = minTopupInterval,
          triggerPollingInterval = triggerPollingInterval,
        )
      }
    }

    intervals.forEvery { case (triggerPollingInterval, minTopupInterval) =>
      s"trigger polling interval is $triggerPollingInterval and min topup interval is $minTopupInterval" should {
        "round times and amounts correctly" in {
          val topupParams = mkExtraTrafficTopupParameters(
            minTopupInterval = minTopupInterval,
            triggerPollingInterval = triggerPollingInterval,
          )
          checkTopupParameters(
            topupParams,
            minTopupInterval = minTopupInterval,
            triggerPollingInterval = triggerPollingInterval,
          )
        }
      }
    }
  }
}
