// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.RewardConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.IssuanceConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.util.{BigDecimalMatchers, SpliceUtil}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional

class RewardComputationInputsTest extends AnyWordSpec with BaseTest with BigDecimalMatchers {

  import RewardComputationInputsTest.*

  "RewardComputationInputs.fromOpenMiningRound" should {

    "extract all fields from an OpenMiningRound with CIP-104 config" in {
      RewardComputationInputs.fromOpenMiningRound(mainNetRound) shouldBe Some((mainNet, 100))
    }

    "use default developmentFundPercentage when optDevelopmentFundPercentage is None" in {
      val round = mkOpenMiningRound(optDevelopmentFundPercentage = Optional.empty())
      RewardComputationInputs.fromOpenMiningRound(round).map(_._1) shouldBe Some(mainNet)
    }

    "return None when rewardConfig is None" in {
      val round = mkOpenMiningRound(rewardConfig = Optional.empty())
      RewardComputationInputs.fromOpenMiningRound(round) shouldBe None
    }

    "return None when trafficPrice is None" in {
      val round = mkOpenMiningRound(trafficPrice = Optional.empty())
      RewardComputationInputs.fromOpenMiningRound(round) shouldBe None
    }
  }

  "RewardComputationInputs.deriveIssuanceParams" should {

    cases.foreach { tc =>
      tc.label in {
        val result =
          tc.inputs.deriveIssuanceParams(
            tc.totalRoundAppActivityWeight,
            tc.totalFeaturedAssetWeight,
          )
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
        result.issuancePerFeaturedAssetBurnCC should beEqualUpTo(
          tc.expected.issuancePerFeaturedAssetBurnCC,
          10,
        )
        result.issuancePerFeaturedAssetBurn_CCperMB should beEqualUpTo(
          tc.expected.issuancePerFeaturedAssetBurn_CCperMB,
          10,
        )
        result.totalIssuanceForFeaturedAssetRewards should beEqualUpTo(
          tc.expected.totalIssuanceForFeaturedAssetRewards,
          10,
        )
        result.issuancePerFeaturedAppBurnCC should beEqualUpTo(
          tc.expected.issuancePerFeaturedAppBurnCC,
          10,
        )
        result.featuredAppMintingAllowanceCC should beEqualUpTo(
          tc.expected.featuredAppMintingAllowanceCC,
          10,
        )
      }
    }
  }
}

object RewardComputationInputsTest {

  import RewardComputationInputs.{fromBigDecimal as n}

  private val defaultRewardConfig = new RewardConfig(
    REWARDVERSION_TRAFFICBASEDAPPREWARDS,
    Optional.empty(),
    100L,
    new RelTime(36L * 3600L * 1000000L),
    SpliceUtil.damlDecimal(0.5),
    Optional.empty(),
    Optional.empty(),
    Optional.empty(),
  )

  private def mkOpenMiningRound(
      trafficPrice: Optional[java.math.BigDecimal] = Optional.of(SpliceUtil.damlDecimal(60.0)),
      rewardConfig: Optional[RewardConfig] = Optional.of(defaultRewardConfig),
      optDevelopmentFundPercentage: Optional[java.math.BigDecimal] =
        Optional.of(SpliceUtil.damlDecimal(0.05)),
  ): OpenMiningRound = {
    val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    new OpenMiningRound(
      "dso-party",
      new Round(42L),
      SpliceUtil.damlDecimal(0.14877),
      now,
      now.plusSeconds(600),
      new RelTime(600L * 1000000L),
      SpliceUtil.defaultTransferConfig(10, SpliceUtil.damlDecimal(1.0)),
      new IssuanceConfig(
        SpliceUtil.damlDecimal(10000000000.0),
        SpliceUtil.damlDecimal(0.0),
        SpliceUtil.damlDecimal(0.62),
        SpliceUtil.damlDecimal(0.0),
        SpliceUtil.damlDecimal(1.5),
        SpliceUtil.damlDecimal(0.6),
        Optional.empty(),
        optDevelopmentFundPercentage,
      ),
      new RelTime(600L * 1000000L),
      trafficPrice,
      rewardConfig,
    )
  }

  private val mainNetRound: OpenMiningRound = mkOpenMiningRound()

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
    * Derived: amuletsToIssueInRound_beforeDevFund ≈ 190258.75
    *          amuletsToIssueInRound ≈ 180745.81 (after 5% devfund)
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
    * amuletToIssuePerYear = 52560 → amuletsToIssueInRound_beforeDevFund = 1.0
    * developmentFundPercentage = 0.1 → amuletsToIssueInRound = 0.9
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
      totalFeaturedAssetWeight: Long = 0L,
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
        issuancePerFeaturedAppBurnCC = BigDecimal("1.5"),
        featuredAppMintingAllowanceCC = BigDecimal("57549.5317604300"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("0.45"),
        featuredAppMintingAllowanceCC = BigDecimal("0.45"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("1.4604254993"),
        featuredAppMintingAllowanceCC = BigDecimal("112062.4048706240"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
      ),
    ),
    // Simple: double traffic (2 MB) → half the issuance per featured app activity
    // issuancePerCoupon = 0.45/2.0 = 0.225 (half of 0.45 from 1 MB case)
    TestCase(
      label = "simple: double traffic halves issuance per featured app activity",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 2000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.225"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
        issuancePerFeaturedAppBurnCC = BigDecimal("0.225"),
        featuredAppMintingAllowanceCC = BigDecimal("0.45"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("0.045"),
        featuredAppMintingAllowanceCC = BigDecimal("0.45"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("1.5"),
        featuredAppMintingAllowanceCC = BigDecimal("302.4803387780"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("0.1"),
        featuredAppMintingAllowanceCC = BigDecimal("0.1"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("0.2"),
        featuredAppMintingAllowanceCC = BigDecimal("0.4"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("1.5"),
        featuredAppMintingAllowanceCC = BigDecimal("8561.6438400000"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("0.9"),
        featuredAppMintingAllowanceCC = BigDecimal("0.45"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("1.5"),
        featuredAppMintingAllowanceCC = BigDecimal("0"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
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
        issuancePerFeaturedAppBurnCC = BigDecimal("100"),
        featuredAppMintingAllowanceCC = BigDecimal("0"),
        issuancePerFeaturedAssetBurnCC = BigDecimal(0),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal(0),
      ),
    ),

    // --- 7. TBAR v1.1 asset side ---

    // 7a. Zero burn → zero-guard
    TestCase(
      label = "MainNet+asset: zero burn exercises zero-guard",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 0L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("604.9606775559"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("54512.8731101940"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("0"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("0"),
        issuancePerFeaturedAppBurnCC = BigDecimal("1.5"),
        featuredAppMintingAllowanceCC = BigDecimal("57549.5317604300"),
      ),
    ),
    // Simple: cap doesn't bind, exact zero budget.
    TestCase(
      label = "simple+asset: zero burn exercises zero-guard",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 0L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.45"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("0"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("0"),
        issuancePerFeaturedAppBurnCC = BigDecimal("0.45"),
        featuredAppMintingAllowanceCC = BigDecimal("0.45"),
      ),
    ),

    // 7b. Burn exceeds leftover → asset prorated

    // MainNet: burn=100000 » leftover ≈ 54512.87 → rate ≈ 0.5451.
    TestCase(
      label = "MainNet+asset: burn exceeds leftover, asset prorated",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 250000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("604.9606775559"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("0"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("0.5406586755"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal("218.0514924380"),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("54512.8731101940"),
        issuancePerFeaturedAppBurnCC = BigDecimal("1.5"),
        featuredAppMintingAllowanceCC = BigDecimal("57549.5317604300"),
      ),
    ),
    // Simple: cap=0.1 binds; burn=1.0 » leftover=0.35 → rate = 0.35.
    TestCase(
      label = "simple+asset: burn exceeds leftover, asset prorated",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.1"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("0.35"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal("0.35"),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("0.35"),
        issuancePerFeaturedAppBurnCC = BigDecimal("0.1"),
        featuredAppMintingAllowanceCC = BigDecimal("0.1"),
      ),
    ),

    // 7c. Cap not binding → asset arm vanishes

    // MainNet: doubled traffic $120/MB — cap doesn't bind, asset arm vanishes.
    TestCase(
      label = "MainNet+asset: cap not binding, asset arm vanishes",
      inputs = mainNet.copy(trafficPrice = n(BigDecimal("120"))),
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 1000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("1177.9999994354"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("0E-10"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("0"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("0"),
        issuancePerFeaturedAppBurnCC = BigDecimal("1.4604254993"),
        featuredAppMintingAllowanceCC = BigDecimal("112062.4048706240"),
      ),
    ),
    // Simple: cap=100 doesn't bind; assetBudget = 0.
    TestCase(
      label = "simple+asset: cap not binding, asset arm vanishes",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 500000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.45"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("0"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("0"),
        issuancePerFeaturedAppBurnCC = BigDecimal("0.45"),
        featuredAppMintingAllowanceCC = BigDecimal("0.45"),
      ),
    ),

    // 7d. Non-terminating issuance at simple magnitudes
    // 7 MB → 45/700 = 0.0642857143 (non-terminating in scale-10); the allowance
    // equals rewardsToIssue exactly.
    TestCase(
      label = "simple+asset: non-terminating issuance (7 MB, cap not binding)",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 7000000L,
      totalFeaturedAssetWeight = 100000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.0642857143"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("0"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal(0),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("0"),
        issuancePerFeaturedAppBurnCC = BigDecimal("0.0642857143"),
        featuredAppMintingAllowanceCC = BigDecimal("0.45"),
      ),
    ),

    // 7e. Cap binds + asset burn fits within leftover

    // MainNet: 1000 CC burn fits within 54512.87 leftover → 1:1 rate.
    TestCase(
      label = "MainNet+asset: cap binds, asset fits within leftover",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 10000000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("604.9606775559"),
        threshold_CC = BigDecimal("3.3608926531"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("112062.4048706240"),
        unclaimedAppRewardAmount = BigDecimal("50479.8019264880"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("1"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal("403.3071183706"),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("4033.0711837060"),
        issuancePerFeaturedAppBurnCC = BigDecimal("1.5"),
        featuredAppMintingAllowanceCC = BigDecimal("57549.5317604300"),
      ),
    ),
    // Simple: cap=0.1 binds; 0.1 CC burn fits within 0.35 leftover → 1:1 rate.
    TestCase(
      label = "simple+asset: cap binds, asset fits within leftover",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 100000L,
      expected = RewardIssuanceParams(
        issuancePerFeaturedAppTraffic_CCperMB = BigDecimal("0.1"),
        threshold_CC = BigDecimal("0.5"),
        totalIssuanceForFeaturedAppRewards = BigDecimal("0.45"),
        unclaimedAppRewardAmount = BigDecimal("0.25"),
        issuancePerFeaturedAssetBurnCC = BigDecimal("1"),
        issuancePerFeaturedAssetBurn_CCperMB = BigDecimal("1"),
        totalIssuanceForFeaturedAssetRewards = BigDecimal("0.1"),
        issuancePerFeaturedAppBurnCC = BigDecimal("0.1"),
        featuredAppMintingAllowanceCC = BigDecimal("0.1"),
      ),
    ),
  )
}
