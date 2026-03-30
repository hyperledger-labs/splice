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

  /** Baseline inputs: simple round numbers for easy manual verification.
    *
    * amuletToIssuePerYear = 52560 → amuletsToIssueInRound = 1.0
    * developmentFundPercentage = 0.1 → adjustedAmuletsToIssueInRound = 0.9
    * appRewardPercentage = 0.5 → rewardsToIssue = 0.45
    * amuletPrice = 1.0, trafficPrice = 1.0 → trafficPriceInCCperMB = 1.0
    * featuredAppRewardCap = 100 (high, so cap doesn't bind)
    * appRewardCouponThreshold = 0.5 → threshold_CC = 0.5
    */
  private val baseline = RewardComputationInputs(
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

  // With baseline: rewardsToIssue = 0.45, trafficPriceInCCperMB = 1.0
  // totalCoupons_CC = weight / 1e6 * 1.0
  // issuancePerCoupon = min(0.45 / totalCoupons_CC, 100)
  // issuancePerFeaturedAppTraffic_CCperMB = 1.0 * issuancePerCoupon
  // unclaimed = 0.45 - issuancePerCoupon * totalCoupons_CC

  val cases: Seq[TestCase] = Seq(
    // 1 MB of traffic → totalCoupons_CC = 1.0
    // issuancePerCoupon = min(0.45 / 1.0, 100) = 0.45
    // rate = 1.0 * 0.45 = 0.45
    // unclaimed = 0.45 - 0.45 * 1.0 = 0.0
    TestCase(
      label = "basic: 1 MB traffic, no cap binding",
      inputs = baseline,
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.45"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
      ),
    ),
    // 10 MB of traffic → totalCoupons_CC = 10.0
    // issuancePerCoupon = min(0.45 / 10.0, 100) = 0.045
    // rate = 1.0 * 0.045 = 0.045
    // unclaimed = 0.45 - 0.045 * 10.0 = 0.0
    TestCase(
      label = "more traffic dilutes issuance rate",
      inputs = baseline,
      totalRoundAppActivityWeight = 10000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.045"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
      ),
    ),
    // Cap = 0.1 (binds), 1 MB traffic → totalCoupons_CC = 1.0
    // issuancePerCoupon = min(0.45 / 1.0, 0.1) = 0.1
    // rate = 1.0 * 0.1 = 0.1
    // unclaimed = 0.45 - 0.1 * 1.0 = 0.35
    TestCase(
      label = "cap binds, producing unclaimed rewards",
      inputs = baseline.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.1"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0.35"),
      ),
    ),
    // Zero traffic → totalCoupons_CC = 0
    // issuancePerCoupon = capPerCoupon (Daml returns cap when no coupons)
    // rate = trafficPriceInCCperMB * capPerCoupon = 1.0 * 100 = 100
    // unclaimed = 0.45 - 100 * 0 = 0.45
    TestCase(
      label = "zero traffic: all rewards unclaimed",
      inputs = baseline,
      totalRoundAppActivityWeight = 0L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("100"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0.45"),
      ),
    ),
    // amuletPrice = 2.0 → trafficPriceInCCperMB = 0.5, threshold_CC = 0.25
    // 1 MB → totalCoupons_CC = 0.5
    // issuancePerCoupon = min(0.45 / 0.5, 100) = 0.9
    // rate = 0.5 * 0.9 = 0.45
    // unclaimed = 0.45 - 0.9 * 0.5 = 0.0
    TestCase(
      label = "amuletPrice affects threshold and traffic conversion",
      inputs = baseline.copy(amuletPrice = n(BigDecimal("2.0"))),
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.45"),
        threshold_CC = BigDecimal("0.25"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
      ),
    ),
    // Cap = 0.2, 2 MB → totalCoupons_CC = 2.0
    // scaledTotalCoupons = 0.2 * 2.0 = 0.4
    // cappedRewardsToIssue = min(0.45, 0.4) = 0.4
    // issuancePerCoupon = (0.4 * 0.2) / 0.4 = 0.2
    // rate = 1.0 * 0.2 = 0.2
    // unclaimed = 0.45 - 0.2 * 2.0 = 0.05
    TestCase(
      label = "cap binds with multiple MB of traffic",
      inputs = baseline.copy(featuredAppRewardCap = n(BigDecimal("0.2"))),
      totalRoundAppActivityWeight = 2000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.2"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0.05"),
      ),
    ),
  )
}
