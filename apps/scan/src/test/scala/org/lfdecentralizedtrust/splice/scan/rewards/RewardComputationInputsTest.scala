// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.util.BigDecimalMatchers
import org.scalatest.wordspec.AnyWordSpec

class RewardComputationInputsTest extends AnyWordSpec with BaseTest with BigDecimalMatchers {

  import RewardComputationInputsTest.*

  "RewardComputationInputs.deriveIssuanceParams" should {

    cases.foreach { tc =>
      tc.label in {
        val result = tc.inputs.deriveIssuanceParams(tc.totalRoundAppActivityWeight)
        result.issuancePerFeaturedAppTraffic_CCperMB should beEqualUpTo(
          tc.expected.issuancePerFeaturedAppTraffic_CCperMB,
          10,
        )
        result.threshold_CC should beEqualUpTo(tc.expected.threshold_CC, 10)
        result.totalIssuanceForFeaturedAppRewards should beEqualUpTo(
          tc.expected.totalIssuanceForFeaturedAppRewards,
          10,
        )
        result.unclaimedAppRewardAmount should beEqualUpTo(
          tc.expected.unclaimedAppRewardAmount,
          10,
        )
      }
    }
  }
}

object RewardComputationInputsTest {

  import RewardComputationInputs.{fromBigDecimal as n}

  // 600s tick → 52560 rounds/year
  private val tickDurationMicros: Long = 600L * 1000000L
  private val microsPerYear: Long = 365L * 24 * 3600 * 1000000L
  private val roundsPerYear: BigDecimal = BigDecimal(microsPerYear) / BigDecimal(tickDurationMicros)

  /** MainNet-calibrated inputs from live DSO config (2026-03-31, round 89682).
    *
    * Source: https://docs.global.canton.network.sync.global/dso
    *   amuletToIssuePerYear = 10e9, appRewardPercentage = 0.62
    *   featuredAppRewardCap = 1.5, unfeaturedAppRewardCap = 0.6
    *   optDevelopmentFundPercentage = None → default 0.05
    *   trafficPrice = 60 $/MB, amuletPrice = 0.14877 $/CC
    *
    * Derived: amuletsToIssueInRound ≈ 190258.75
    *          adjustedAmuletsToIssueInRound ≈ 180745.81 (after 5% devfund)
    *          rewardsToIssue ≈ 112062.40, trafficPriceInCCperMB ≈ 403.30, threshold_CC ≈ 3.36
    */
  val mainNet = RewardComputationInputs(
    amuletToIssuePerYear = n(BigDecimal("10000000000")),
    appRewardPercentage = n(BigDecimal("0.62")),
    featuredAppRewardCap = n(BigDecimal("1.5")),
    unfeaturedAppRewardCap = n(BigDecimal("0.6")),
    developmentFundPercentage = n(BigDecimal("0.05")),
    tickDurationMicros = tickDurationMicros,
    amuletPrice = n(BigDecimal("0.14877")),
    trafficPrice = n(BigDecimal("60")),
    appRewardCouponThreshold = n(BigDecimal("0.5")),
  )

  /** Baseline inputs: simple round numbers for easy manual verification.
    *
    * amuletToIssuePerYear = 52560 → amuletsToIssueInRound = 1.0
    * developmentFundPercentage = 0.1 → adjustedAmuletsToIssueInRound = 0.9
    * appRewardPercentage = 0.5 → rewardsToIssue = 0.45
    * amuletPrice = 1.0, trafficPrice = 1.0 → trafficPriceInCCperMB = 1.0
    * featuredAppRewardCap = 100 (high, so cap doesn't bind)
    * appRewardCouponThreshold = 0.5 → threshold_CC = 0.5
    */
  private val simpleArithmetic = RewardComputationInputs(
    amuletToIssuePerYear = n(roundsPerYear), // yields exactly 1.0 per round
    appRewardPercentage = n(BigDecimal("0.5")),
    featuredAppRewardCap = n(BigDecimal("100")),
    unfeaturedAppRewardCap = n(BigDecimal("0")),
    developmentFundPercentage = n(BigDecimal("0.1")),
    tickDurationMicros = tickDurationMicros,
    amuletPrice = n(BigDecimal("1.0")),
    trafficPrice = n(BigDecimal("1.0")),
    appRewardCouponThreshold = n(BigDecimal("0.5")),
  )

  case class TestCase(
      label: String,
      inputs: RewardComputationInputs,
      totalRoundAppActivityWeight: Long,
      expected: RewardIssuanceParams,
  )

  // Test cases are grouped in pairs: MainNet-calibrated first (realistic values
  // matching cantonscan.com), then a simple-arithmetic equivalent (round numbers
  // for easy manual verification of the same concept).

  val cases: Seq[TestCase] = Seq(
    // --- 1. Current MainNet traffic, cap binds ---

    // MainNet: BME = 0.8 (~95 MB traffic). With cap = 1.5, cap binds because
    // issuancePerCoupon = 112062.40 / 38340.89 ≈ 2.92 > 1.5.
    // ≈ 605 CC/MB ≈ $90/MB in rewards — within expected $60-90/MB range
    TestCase(
      label = "MainNet: BME=0.8, cap binds, unclaimed rewards",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("604.9606775559"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("54512.8731101940"),
      ),
    ),
    // Simple: 1 MB traffic, no cap binding
    // issuancePerCoupon = 0.45/1.0 = 0.45, unclaimed = 0
    TestCase(
      label = "simple: 1 MB traffic, no cap binding",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.45"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
      ),
    ),

    // --- 2. More traffic dilutes issuance per MB ---

    // MainNet: doubled traffic price $120/MB → enough coupons that cap no longer binds.
    // trafficPriceInCCperMB = 120/0.14877 ≈ 806.60, totalCoupons ≈ 76681.78
    // issuancePerCoupon = 112062.40 / 76681.78 ≈ 1.46 < 1.5 (cap does not bind)
    TestCase(
      label = "MainNet: doubled traffic price $120/MB, cap does not bind",
      inputs = mainNet.copy(trafficPrice = n(BigDecimal("120"))),
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("1177.9999994354"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("0E-10"),
      ),
    ),
    // Simple: 10 MB traffic dilutes issuance per MB
    // issuancePerCoupon = 0.45/10.0 = 0.045
    TestCase(
      label = "simple: more traffic dilutes issuance per MB",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 10000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.045"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
      ),
    ),

    // --- 3. Cap binds with very low traffic ---

    // MainNet: 0.5 MB traffic, cap binds even more strongly.
    // totalCoupons_CC = 0.5 * 403.30 ≈ 201.65
    // issuancePerCoupon = min(112062.40/201.65, 1.5) = 1.5 (cap binds)
    // unclaimed = 112062.40 - 1.5 * 201.65 ≈ 111759.92
    TestCase(
      label = "MainNet: very low traffic, cap binds, mostly unclaimed",
      inputs = mainNet,
      totalRoundAppActivityWeight = 500000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("604.9606775559"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("111759.9245318460"),
      ),
    ),
    // Simple: cap = 0.1, 1 MB → cap binds, unclaimed = 0.35
    TestCase(
      label = "simple: cap binds, producing unclaimed rewards",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.1"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0.35"),
      ),
    ),

    // --- 4. Cap binds with higher total traffic ---

    // Simple: cap = 0.2, 2 MB → cap binds, unclaimed = 0.05
    TestCase(
      label = "simple: lower cap with 2 MB total traffic produces unclaimed rewards",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.2"))),
      totalRoundAppActivityWeight = 2000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.2"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0.05"),
      ),
    ),

    // --- 5. Coin price affects threshold and conversion ---

    // MainNet: higher coin price $1.00/CC
    // trafficPriceInCCperMB = 60/1.0 = 60, threshold_CC = 0.5/1.0 = 0.5
    // totalCoupons = 95.13 * 60 = 5707.76, issuancePerCoupon = 112062.40/5707.76 ≈ 19.63
    // cap = 1.5 binds → issuancePerCoupon = 1.5
    TestCase(
      label = "MainNet: higher coin price $1.00/CC",
      inputs = mainNet.copy(amuletPrice = n(BigDecimal("1.00"))),
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("90"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("103500.7610306240"),
      ),
    ),
    // Simple: amuletPrice = 2.0 → trafficPriceInCCperMB = 0.5, threshold_CC = 0.25
    TestCase(
      label = "simple: amuletPrice affects threshold and traffic conversion",
      inputs = simpleArithmetic.copy(amuletPrice = n(BigDecimal("2.0"))),
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.45"),
        threshold_CC = BigDecimal("0.25"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
      ),
    ),

    // --- 6. Zero traffic: all rewards unclaimed ---

    // MainNet: rate = trafficPriceInCCperMB * cap ≈ 403.30 * 1.5 ≈ 604.96
    TestCase(
      label = "MainNet: zero traffic, all unclaimed",
      inputs = mainNet,
      totalRoundAppActivityWeight = 0L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("604.9606775559"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("112062.4048706240"),
      ),
    ),
    // Simple: rate = 1.0 * 100 = 100, all 0.45 unclaimed
    TestCase(
      label = "simple: zero traffic, all rewards unclaimed",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 0L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("100"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0.45"),
      ),
    ),
  )
}
