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
    issuancePerFeaturedAppTraffic_CCperMB: BigDecimal,
    threshold_CC: BigDecimal,
    totalIssuanceForFeaturedAppRewards: BigDecimal,
    unclaimedAppRewardAmount: BigDecimal,
    issuancePerFeaturedAssetBurnCC: BigDecimal = BigDecimal(0),
    totalIssuanceForFeaturedAssetRewards: BigDecimal = BigDecimal(0),
    issuancePerFeaturedAppBurnCC: BigDecimal = BigDecimal(0),
    featuredAppMintingAllowanceCC: BigDecimal = BigDecimal(0),
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
    extraFeaturedAssetActivityWeight: Numeric,
    maxExtraFeaturedAssetActivityWeightPerAsset: Numeric,
    dsoAppActivityWeight: Numeric,
) {
  import RewardComputationInputs.*

  private val microsPerYear: Long = 365L * 24 * 3600 * 1000000L
  private val roundsPerYear: Numeric = div(fromLong(microsPerYear), fromLong(tickDurationMicros))
  private val amuletsToIssueInRound: Numeric = div(amuletToIssuePerYear, roundsPerYear)
  private val adjustedAmuletsToIssueInRound: Numeric =
    sub(amuletsToIssueInRound, mul(amuletsToIssueInRound, developmentFundPercentage))
  private[scan] val trafficPriceInCCperMB: Numeric = div(trafficPrice, amuletPrice)

  def deriveIssuanceParams(
      totalRoundAppActivityWeight: Long,
      totalFeaturedAssetBurnCc: BigDecimal,
  ): RewardIssuanceParams = {
    val totalCoupons_CC = mul(
      div(fromLong(totalRoundAppActivityWeight), fromLong(1000000L)),
      trafficPriceInCCperMB,
    )
    val rewardsToIssue = mul(adjustedAmuletsToIssueInRound, appRewardPercentage)
    val issuancePerCoupon = computeIssuanceTranche(
      rewardsToIssue = rewardsToIssue,
      capPerCoupon = featuredAppRewardCap,
      totalCoupons = totalCoupons_CC,
    )
    val featuredAppMintingAllowanceCC = mul(issuancePerCoupon, totalCoupons_CC)

    // Asset side (CIP-104 amendment): asset gets the leftover after app
    // allocation, capped at the actual asset-side CC burn. Clamp the leftover
    // to zero to guard against negative drift from the computeIssuanceTranche
    // mul-then-div chain when the cap does not bind.
    val totalFeaturedAssetBurnCC_num = fromBigDecimal(totalFeaturedAssetBurnCc)
    val assetBudgetRaw = sub(rewardsToIssue, featuredAppMintingAllowanceCC)
    val assetBudgetCC = if (assetBudgetRaw.compareTo(zero) < 0) zero else assetBudgetRaw
    val featuredAssetMintingAllowanceCC =
      if (totalFeaturedAssetBurnCC_num.compareTo(assetBudgetCC) < 0) totalFeaturedAssetBurnCC_num
      else assetBudgetCC
    val issuancePerFeaturedAssetBurnCC_num =
      computeAssetIssuanceRate(featuredAssetMintingAllowanceCC, totalFeaturedAssetBurnCC_num)

    val unclaimed =
      sub(sub(rewardsToIssue, featuredAppMintingAllowanceCC), featuredAssetMintingAllowanceCC)

    // Convert Numeric → BigDecimal at the output boundary for SQL
    RewardIssuanceParams(
      issuancePerFeaturedAppTraffic_CCperMB =
        BigDecimal(mul(trafficPriceInCCperMB, issuancePerCoupon)),
      threshold_CC = BigDecimal(div(appRewardCouponThreshold, amuletPrice)),
      totalIssuanceForFeaturedAppRewards = BigDecimal(rewardsToIssue),
      unclaimedAppRewardAmount = BigDecimal(if (unclaimed.compareTo(zero) < 0) zero else unclaimed),
      issuancePerFeaturedAssetBurnCC = BigDecimal(issuancePerFeaturedAssetBurnCC_num),
      totalIssuanceForFeaturedAssetRewards = BigDecimal(featuredAssetMintingAllowanceCC),
      issuancePerFeaturedAppBurnCC = BigDecimal(issuancePerCoupon),
      featuredAppMintingAllowanceCC = BigDecimal(featuredAppMintingAllowanceCC),
    )
  }

  /** Replicates Daml computeIssuanceTranche (Splice/Issuance.daml:164-189) for
    * totalUnfeaturedAppRewardCoupons = 0.
    *
    * The multiply-before-divide order `(cappedRewardsToIssue * capPerCoupon) / scaledTotalCoupons`
    * matches the Daml source.
    */
  private def computeIssuanceTranche(
      rewardsToIssue: Numeric,
      capPerCoupon: Numeric,
      totalCoupons: Numeric,
  ): Numeric = {
    if (totalCoupons.compareTo(zero) <= 0) capPerCoupon
    else if (capPerCoupon.compareTo(zero) <= 0) zero
    else {
      val scaledTotalCoupons = mul(capPerCoupon, totalCoupons)
      val cappedRewardsToIssue =
        if (rewardsToIssue.compareTo(scaledTotalCoupons) < 0) rewardsToIssue
        else scaledTotalCoupons
      div(mul(cappedRewardsToIssue, capPerCoupon), scaledTotalCoupons)
    }
  }

  /** Zero-guarded CC-per-CC-burned rate for featured-asset issuance.
    * Matches the inline zero-guard precedent of computeIssuanceTranche.
    */
  private def computeAssetIssuanceRate(
      assetMintingAllowance: Numeric,
      totalAssetBurn: Numeric,
  ): Numeric =
    if (totalAssetBurn.compareTo(zero) <= 0) zero
    else div(assetMintingAllowance, totalAssetBurn)
}

object RewardComputationInputs {
  private val scale: Numeric.Scale = Numeric.Scale.assertFromInt(10)
  private[scan] val zero: Numeric = fromLong(0L)
  // Daml defaults for optional fields on RewardConfig and IssuanceConfig.
  private val defaultDevelopmentFundPercentage: BigDecimal = BigDecimal("0.05")
  private val defaultExtraFeaturedAssetActivityWeight: BigDecimal = BigDecimal("0.2")
  private val defaultMaxExtraFeaturedAssetActivityWeightPerAsset: BigDecimal = BigDecimal("0.1")
  private val defaultDsoAppActivityWeight: BigDecimal = BigDecimal("0.1")

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
        extraFeaturedAssetActivityWeight = fromBigDecimal(
          rewardConfig.extraFeaturedAssetActivityWeight.toScala
            .fold(defaultExtraFeaturedAssetActivityWeight)(BigDecimal(_))
        ),
        maxExtraFeaturedAssetActivityWeightPerAsset = fromBigDecimal(
          rewardConfig.maxExtraFeaturedAssetActivityWeightPerAsset.toScala
            .fold(defaultMaxExtraFeaturedAssetActivityWeightPerAsset)(BigDecimal(_))
        ),
        dsoAppActivityWeight = fromBigDecimal(
          rewardConfig.dsoAppActivityWeight.toScala
            .fold(defaultDsoAppActivityWeight)(BigDecimal(_))
        ),
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
