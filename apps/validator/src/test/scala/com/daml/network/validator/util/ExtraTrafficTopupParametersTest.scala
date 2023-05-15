package com.daml.network.validator.util

import com.daml.network.util.CNNodeUtil.domainFeesConfig
import com.daml.network.validator.config.BuyExtraTrafficConfig
import com.digitalasset.canton.BaseTestWordSpec
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric

class ExtraTrafficTopupParametersTest extends BaseTestWordSpec {
  private def mkExtraTrafficTopupParameters(
      baseRate: BigDecimal = BigDecimal(333),
      baseRateBurstWindow: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
      minTopupAmount: Long = 1_000_000,
      targetRate: BigDecimal = BigDecimal(20_000),
      minTopupInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
      triggerPollingInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(2),
  ) = {
    ExtraTrafficTopupParameters(
      domainFeesConfig(baseRate, baseRateBurstWindow, minTopupAmount),
      BuyExtraTrafficConfig(NonNegativeNumeric.tryCreate(targetRate), minTopupInterval),
      triggerPollingInterval,
    )
  }

  "Extra traffic top-up parameters" when {

    "the min top-up interval is configured to be less than the trigger polling interval" should {
      "behave as if the min top-up interval equals the polling interval" in {
        val topupParams1 = mkExtraTrafficTopupParameters(
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(10),
          triggerPollingInterval = NonNegativeFiniteDuration.ofMinutes(15),
        )
        val topupParams2 = mkExtraTrafficTopupParameters(
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(15),
          triggerPollingInterval = NonNegativeFiniteDuration.ofMinutes(15),
        )
        topupParams1.topupAmount should be > 0L
        topupParams1.minTopupInterval.duration.toNanos should be > 0L
        topupParams1 shouldBe topupParams2
      }
    }

    "target rate is less than or equal to the base rate" should {
      "not require any top-up at all" in {
        val topupParams1 = mkExtraTrafficTopupParameters(
          baseRate = BigDecimal(1_000),
          targetRate = BigDecimal(500),
        )
        topupParams1.topupAmount shouldBe 0
        val topupParams2 = mkExtraTrafficTopupParameters(
          baseRate = BigDecimal(1_000),
          targetRate = BigDecimal(1_000),
        )
        topupParams2.topupAmount shouldBe 0
      }
    }

    "min top-up amount is relatively low" should {
      "provide enough extra traffic to last till next top-up" in {
        val minTopupAmount = 4_000_000L
        val baseRate = BigDecimal(1_000)
        val targetRate = BigDecimal(10_000)
        val triggerPollingInterval = NonNegativeFiniteDuration.ofMinutes(2)

        // Note that the next top-up will occur the first time the trigger runs after
        // 1 min top-up interval has elapsed.

        // min top-up interval is an exact multiple of trigger polling interval
        // next top-up will happen after 4 polling intervals = 8 mins
        val topupParams1 = mkExtraTrafficTopupParameters(
          minTopupAmount = minTopupAmount,
          baseRate = baseRate,
          targetRate = targetRate,
          triggerPollingInterval = triggerPollingInterval,
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(7),
        )
        // min top-up interval is not an exact mutliple of trigger polling interval
        // here too, next top-up will occur after 4 polling intervals = 8 mins
        val topupParams2 = mkExtraTrafficTopupParameters(
          minTopupAmount = minTopupAmount,
          baseRate = baseRate,
          targetRate = targetRate,
          triggerPollingInterval = triggerPollingInterval,
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(8),
        )
        val topupIntervalSecs = NonNegativeFiniteDuration.ofMinutes(8).duration.toSeconds
        // Note that by min top-up amount being "relatively low", we mean
        // minTopupAmount <= (targetRate - baseRate) * topupInterval
        //                   = (10_000 - 1_000) * 8 * 60 = 4.32MB
        topupParams1.topupAmount shouldBe (targetRate - baseRate) * topupIntervalSecs
        topupParams2.minTopupInterval.duration.toSeconds shouldBe topupIntervalSecs
        topupParams1 shouldBe topupParams2
      }
    }

    "min top-up amount is relatively high" should {
      "adjust top-up interval to deliver configured target rate" in {
        // Set minTopupAmount in this case to be > 4.32MB
        val minTopupAmount = 5_000_000L
        val baseRate = BigDecimal(1_000)
        val targetRate = BigDecimal(10_000)
        val triggerPollingInterval = NonNegativeFiniteDuration.ofMinutes(2)

        val topupParams = mkExtraTrafficTopupParameters(
          minTopupAmount = minTopupAmount,
          baseRate = baseRate,
          targetRate = targetRate,
          triggerPollingInterval = triggerPollingInterval,
          minTopupInterval = NonNegativeFiniteDuration.ofMinutes(7),
        )
        // topupInterval in this case would be derived from the minTopupAmount as
        // minTopupAmount / (targetRate - baseRate) ~= 9.3 mins
        // the actual topupInterval would then be this value rounded up to the nearest
        // multiple of the polling interval = 10 mins
        val topupIntervalSecs = NonNegativeFiniteDuration.ofMinutes(10).duration.toSeconds
        topupParams.topupAmount shouldBe (targetRate - baseRate) * topupIntervalSecs
        topupParams.minTopupInterval.duration.toSeconds shouldBe topupIntervalSecs
      }
    }
  }
}
