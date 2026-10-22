// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.daml.lf.data.Numeric
import com.digitalasset.daml.lf.data.{assertRight as damlRight}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import scala.jdk.OptionConverters.RichOptional

/** Derived parameters passed to computeRewardTotals.
  *
  * Fields are BigDecimal because they flow into SQL queries.
  *
  * Under TBAR v1.1 (CIP-104 amendment) `unclaimedAppRewardAmount` carries the
  * round-wide unclaimed amount — app-side's unclaimed cascades into asset-side's
  * budget before any asset allocation, so there is no separately-unclaimed
  * app or asset portion.
  */
final case class RewardIssuanceParams(
    // Common
    threshold_CC: BigDecimal,
    unclaimedAppRewardAmount: BigDecimal,
    // App side
    totalIssuanceForFeaturedAppRewards: BigDecimal,
    issuancePerFeaturedAppTraffic_CCperMB: BigDecimal,
    issuancePerFeaturedAppBurnCC: BigDecimal,
    // Asset side
    totalIssuanceForFeaturedAssetRewards: BigDecimal,
    issuancePerFeaturedAssetBurn_CCperMB: BigDecimal,
    issuancePerFeaturedAssetBurnCC: BigDecimal,
)

/** Raw contract inputs for app reward CC computation.
  *
  * Built by mirroring the code from `Splice.Issuance.daml` into Scala with the
  * same scale (Numeric 10) and rounding mode (HALF_EVEN).
  *
  * All fields use `Numeric` to enforce Daml-compatible arithmetic throughout
  *
  * @see https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md#app-reward-computation-details
  */
final case class RewardComputationInputs(
    amuletToIssuePerYear: Numeric,
    appRewardPercentage: Numeric,
    featuredAppRewardCap: Numeric,
    unfeaturedAppRewardCap: Numeric,
    developmentFundPercentage: Numeric,
    tickDurationMicros: Long,
    amuletPrice: Numeric,
    trafficPrice: Numeric,
    appRewardCouponThreshold: Numeric,
) {
  import RewardComputationInputs.*

  private val microsPerYear: Long = 365L * 24 * 3600 * 1000000L
  private val roundsPerYear: Numeric = div(fromLong(microsPerYear), fromLong(tickDurationMicros))
  private val amuletsToIssueInRound_beforeDevFund: Numeric =
    div(amuletToIssuePerYear, roundsPerYear)
  private val amuletsToIssueInRound: Numeric =
    sub(
      amuletsToIssueInRound_beforeDevFund,
      mul(amuletsToIssueInRound_beforeDevFund, developmentFundPercentage),
    )
  private[scan] val trafficPriceInCCperMB: Numeric = div(trafficPrice, amuletPrice)

  def deriveIssuanceParams(
      totalRoundAppActivityWeight: Long,
      totalFeaturedAssetWeight: Long,
  ): RewardIssuanceParams = {
    val totalCouponsCC = bytesToCC(totalRoundAppActivityWeight)
    val rewardsToIssue = mul(amuletsToIssueInRound, appRewardPercentage)
    val (issuancePerCoupon, appMintingAllowanceRemainder) = computeIssuanceTranche(
      rewardsToIssue = rewardsToIssue,
      capPerCoupon = featuredAppRewardCap,
      totalCoupons = totalCouponsCC,
    )
    val featuredAppMintingAllowanceCC = sub(rewardsToIssue, appMintingAllowanceRemainder)

    // Asset side (CIP-104 amendment): same tranche formula with capPerCoupon=1,
    // consuming the app side's remainder as the asset budget.
    val totalFeaturedAssetBurnCC = bytesToCC(totalFeaturedAssetWeight)
    val (issuancePerFeaturedAssetBurnCC, featuredAssetMintingAllowanceCC, unclaimed) =
      if (totalFeaturedAssetBurnCC.compareTo(zero) <= 0) {
        (zero, zero, appMintingAllowanceRemainder)
      } else {
        val (rate, remainder) = computeIssuanceTranche(
          rewardsToIssue = appMintingAllowanceRemainder,
          capPerCoupon = one,
          totalCoupons = totalFeaturedAssetBurnCC,
        )
        (rate, sub(appMintingAllowanceRemainder, remainder), remainder)
      }

    // Convert Numeric → BigDecimal at the output boundary for SQL.
    // Under TBAR v1.1, totalIssuanceForFeaturedAppRewards carries the app-source
    // allocation (parallel to totalIssuanceForFeaturedAssetRewards for the asset
    // side); the shared pot is implicit as app + asset + unclaimed.
    RewardIssuanceParams(
      threshold_CC = BigDecimal(div(appRewardCouponThreshold, amuletPrice)),
      unclaimedAppRewardAmount = BigDecimal(unclaimed),
      totalIssuanceForFeaturedAppRewards = BigDecimal(featuredAppMintingAllowanceCC),
      issuancePerFeaturedAppTraffic_CCperMB =
        BigDecimal(mul(trafficPriceInCCperMB, issuancePerCoupon)),
      issuancePerFeaturedAppBurnCC = BigDecimal(issuancePerCoupon),
      totalIssuanceForFeaturedAssetRewards = BigDecimal(featuredAssetMintingAllowanceCC),
      issuancePerFeaturedAssetBurn_CCperMB =
        BigDecimal(mul(trafficPriceInCCperMB, issuancePerFeaturedAssetBurnCC)),
      issuancePerFeaturedAssetBurnCC = BigDecimal(issuancePerFeaturedAssetBurnCC),
    )
  }

  /** Convert a bytes-weight to CC via `trafficPriceInCCperMB`.
    * Used symmetrically for app traffic and asset burn.
    */
  private def bytesToCC(bytesWeight: Long): Numeric =
    mul(div(fromLong(bytesWeight), fromLong(1000000L)), trafficPriceInCCperMB)

  /** Replicates Daml `computeIssuanceTranche` for
    * `totalUnfeaturedAppRewardCoupons = 0`.
    *
    * Returns `(issuancePerCoupon, remainingBudget)` where
    * `remainingBudget = rewardsToIssue - cappedRewardsToIssue` is what's left
    * for the next tranche. The multiply-before-divide order
    * `(cappedRewardsToIssue * capPerCoupon) / scaledTotalCoupons` matches the
    * Daml source.
    */
  private def computeIssuanceTranche(
      rewardsToIssue: Numeric,
      capPerCoupon: Numeric,
      totalCoupons: Numeric,
  ): (Numeric, Numeric) = {
    if (totalCoupons.compareTo(zero) <= 0) (capPerCoupon, rewardsToIssue)
    else if (capPerCoupon.compareTo(zero) <= 0) (zero, rewardsToIssue)
    else {
      val scaledTotalCoupons = mul(capPerCoupon, totalCoupons)
      val cappedRewardsToIssue =
        if (rewardsToIssue.compareTo(scaledTotalCoupons) < 0) rewardsToIssue
        else scaledTotalCoupons
      val issuancePerCoupon = div(mul(cappedRewardsToIssue, capPerCoupon), scaledTotalCoupons)
      val remainingBudget = sub(rewardsToIssue, cappedRewardsToIssue)
      (issuancePerCoupon, remainingBudget)
    }
  }
}

object RewardComputationInputs {
  private val scale: Numeric.Scale = Numeric.Scale.assertFromInt(10)
  private[rewards] val zero: Numeric = fromLong(0L)
  private[rewards] val one: Numeric = fromLong(1L)
  // Daml defaults for optional fields on IssuanceConfig.
  private val defaultDevelopmentFundPercentage: BigDecimal = BigDecimal("0.05")

  private[rewards] def fromLong(x: Long): Numeric =
    damlRight(Numeric.fromLong(scale, x))

  private[scan] def fromBigDecimal(x: BigDecimal): Numeric =
    Numeric.assertFromBigDecimal(scale, x)

  /** Build RewardComputationInputs and batchSize from an OpenMiningRound contract.
    *
    * Returns `None` for pre-CIP-104 rounds where `trafficPrice` or `rewardConfig` is absent.
    */
  def fromOpenMiningRound(
      round: OpenMiningRound
  ): Option[(RewardComputationInputs, Int)] =
    for {
      rewardConfig <- round.rewardConfig.toScala
      trafficPrice <- round.trafficPrice.toScala
    } yield {
      val issuance = round.issuanceConfig
      val devFundPct: BigDecimal = issuance.optDevelopmentFundPercentage.toScala
        .fold(defaultDevelopmentFundPercentage)(BigDecimal(_))

      val inputs = RewardComputationInputs(
        amuletToIssuePerYear = fromBigDecimal(issuance.amuletToIssuePerYear),
        appRewardPercentage = fromBigDecimal(issuance.appRewardPercentage),
        featuredAppRewardCap = fromBigDecimal(issuance.featuredAppRewardCap),
        unfeaturedAppRewardCap = fromBigDecimal(issuance.unfeaturedAppRewardCap),
        developmentFundPercentage = fromBigDecimal(devFundPct),
        tickDurationMicros = round.tickDuration.microseconds,
        amuletPrice = fromBigDecimal(round.amuletPrice),
        trafficPrice = fromBigDecimal(trafficPrice),
        appRewardCouponThreshold = fromBigDecimal(rewardConfig.appRewardCouponThreshold),
      )
      (inputs, rewardConfig.batchSize.toInt)
    }

  private def div(a: Numeric, b: Numeric): Numeric =
    damlRight(Numeric.divide(scale, a, b))

  private def mul(a: Numeric, b: Numeric): Numeric =
    damlRight(Numeric.multiply(scale, a, b))

  private def sub(a: Numeric, b: Numeric): Numeric =
    damlRight(Numeric.subtract(a, b))
}
