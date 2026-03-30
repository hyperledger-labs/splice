// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class RewardComputationInputsTest extends AnyWordSpec with BaseTest {

  import RewardComputationInputsTest.*

  "RewardComputationInputs.deriveIssuanceParams" should {

    cases.foreach { tc =>
      tc.label in {
        val result = tc.inputs.deriveIssuanceParams(tc.totalRoundAppActivityWeight)
        assertClose(
          "issuancePerFeaturedAppTraffic_CCperMB",
          result.issuancePerFeaturedAppTraffic_CCperMB,
          tc.expected.issuancePerFeaturedAppTraffic_CCperMB,
        )
        assertClose(
          "threshold_CC",
          result.threshold_CC,
          tc.expected.threshold_CC,
        )
        assertClose(
          "totalIssuanceForFeaturedAppRewards",
          result.totalIssuanceForFeaturedAppRewards,
          tc.expected.totalIssuanceForFeaturedAppRewards,
        )
        assertClose(
          "unclaimedAppRewardAmount",
          result.unclaimedAppRewardAmount,
          tc.expected.unclaimedAppRewardAmount,
        )
      }
    }
  }

  private def assertClose(
      label: String,
      actual: BigDecimal,
      expected: BigDecimal,
      tolerance: BigDecimal = BigDecimal("1e-10"),
  ): Unit =
    assert(
      (actual - expected).abs < tolerance,
      s"$label: expected $expected but got $actual (diff ${(actual - expected).abs})",
    )
}

object RewardComputationInputsTest {

  import RewardComputationInputs.{fromBigDecimal as n}

  // 600s tick → 52560 rounds/year
  private val tickDurationMicros: Long = 600L * 1000000L
  private val microsPerYear: Long = 365L * 24 * 3600 * 1000000L
  private val roundsPerYear: BigDecimal = BigDecimal(microsPerYear) / BigDecimal(tickDurationMicros)

  /** MainNet-calibrated inputs
    *
    * Uses the 10+ year issuance curve segment:
    *   amuletToIssuePerYear = 2.5e9, appRewardPercentage = 0.75
    *   featuredAppRewardCap = 100, developmentFundPercentage = 0
    *   trafficPrice = 60 $/MB, amuletPrice = 0.15 $/CC
    *
    * Derived: amuletsToIssueInRound ≈ 47564.69, rewardsToIssue ≈ 35673.52
    *          trafficPriceInCCperMB = 400, threshold_CC ≈ 3.33
    */
  val mainNet = RewardComputationInputs(
    amuletToIssuePerYear = n(BigDecimal("2500000000")),
    appRewardPercentage = n(BigDecimal("0.75")),
    featuredAppRewardCap = n(BigDecimal("100")),
    unfeaturedAppRewardCap = n(BigDecimal("0.6")),
    developmentFundPercentage = n(BigDecimal("0")),
    tickDurationMicros = tickDurationMicros,
    amuletPrice = n(BigDecimal("0.15")),
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
    // --- 1. Normal traffic, cap does not bind ---

    // MainNet: BME = 0.8 (~95 MB traffic), cap does not bind (0.8 > 1/1.5 ≈ 0.66)
    // ≈ 375 CC/MB ≈ $56/MB in rewards — within expected $60-90/MB range
    TestCase(
      label = "MainNet: BME=0.8, cap does not bind",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("374.9999998"),
        threshold_CC = BigDecimal("3.3333333333"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("35673.5159817352"),
        unclaimedAppRewardAmount = BigDecimal("0.0000007611"),
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

    // --- 2. More traffic dilutes rate ---

    // MainNet: doubled traffic price $120/MB → more coupons → lower rate
    TestCase(
      label = "MainNet: doubled traffic price $120/MB dilutes rate",
      inputs = mainNet.copy(trafficPrice = n(BigDecimal("120"))),
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("374.99999984"),
        threshold_CC = BigDecimal("3.3333333333"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("35673.5159817352"),
        unclaimedAppRewardAmount = BigDecimal("0E-10"),
      ),
    ),
    // Simple: 10 MB traffic dilutes issuance rate
    // issuancePerCoupon = 0.45/10.0 = 0.045
    TestCase(
      label = "simple: more traffic dilutes issuance rate",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 10000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.045"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
      ),
    ),

    // --- 3. Cap binds, producing unclaimed rewards ---

    // MainNet: very low traffic so cap binds.
    // Cap binds when rewardsToIssue / totalCoupons_CC > featuredAppRewardCap (100).
    // That requires totalCoupons_CC < 35673.52 / 100 = 356.74 CC,
    // i.e., traffic < 356.74 / 400 ≈ 0.89 MB.
    // Using 0.5 MB = 500000 bytes:
    //   totalCoupons_CC = 0.5 * 400 = 200
    //   issuancePerCoupon = min(35673.52/200, 100) = 100 (cap binds)
    //   unclaimed = 35673.52 - 100 * 200 = 15673.52
    TestCase(
      label = "MainNet: very low traffic, cap binds, unclaimed rewards",
      inputs = mainNet,
      totalRoundAppActivityWeight = 500000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("40000"),
        threshold_CC = BigDecimal("3.3333333333"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("35673.5159817352"),
        unclaimedAppRewardAmount = BigDecimal("15673.5159817352"),
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

    // --- 4. Cap binds with multiple providers ---

    // Simple: cap = 0.2, 2 MB → cap binds, unclaimed = 0.05
    TestCase(
      label = "simple: cap binds with multiple MB of traffic",
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
    TestCase(
      label = "MainNet: higher coin price $1.00/CC",
      inputs = mainNet.copy(amuletPrice = n(BigDecimal("1.00"))),
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("374.999999808"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("35673.5159817352"),
        unclaimedAppRewardAmount = BigDecimal("0E-10"),
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

    // MainNet: rate = trafficPriceInCCperMB * cap = 400 * 100 = 40000
    TestCase(
      label = "MainNet: zero traffic, all unclaimed",
      inputs = mainNet,
      totalRoundAppActivityWeight = 0L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("40000"),
        threshold_CC = BigDecimal("3.3333333333"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("35673.5159817352"),
        unclaimedAppRewardAmount = BigDecimal("35673.5159817352"),
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
