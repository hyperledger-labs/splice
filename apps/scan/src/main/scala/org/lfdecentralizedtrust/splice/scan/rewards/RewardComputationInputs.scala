// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.daml.lf.data.Numeric
import com.digitalasset.daml.lf.data.{assertRight as damlRight}

/** Derived parameters passed to computeRewardTotals.
  *
  * Fields are BigDecimal because they flow into SQL queries.
  */
final case class RewardIssuanceParams(
    issuancePerFeaturedAppTraffic_CCperMB: BigDecimal,
    threshold_CC: BigDecimal,
    totalIssuanceForFeaturedAppRewards: BigDecimal,
    unclaimedAppRewardAmount: BigDecimal,
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
  private val amuletsToIssueInRound: Numeric = div(amuletToIssuePerYear, roundsPerYear)
  private val adjustedAmuletsToIssueInRound: Numeric =
    sub(amuletsToIssueInRound, mul(amuletsToIssueInRound, developmentFundPercentage))
  private val trafficPriceInCCperMB: Numeric = div(trafficPrice, amuletPrice)

  def deriveIssuanceParams(totalRoundAppActivityWeight: Long): RewardIssuanceParams = {
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
    val unclaimed = sub(rewardsToIssue, mul(issuancePerCoupon, totalCoupons_CC))

    // Convert Numeric → BigDecimal at the output boundary for SQL
    RewardIssuanceParams(
      issuancePerFeaturedAppTraffic_CCperMB =
        BigDecimal(mul(trafficPriceInCCperMB, issuancePerCoupon)),
      threshold_CC = BigDecimal(div(appRewardCouponThreshold, amuletPrice)),
      totalIssuanceForFeaturedAppRewards = BigDecimal(rewardsToIssue),
      unclaimedAppRewardAmount = BigDecimal(if (unclaimed.compareTo(zero) < 0) zero else unclaimed),
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
}

object RewardComputationInputs {
  private val scale: Numeric.Scale = Numeric.Scale.assertFromInt(10)
  private[rewards] val zero: Numeric = fromLong(0L)

  private[rewards] def fromLong(x: Long): Numeric =
    damlRight(Numeric.fromLong(scale, x))

  private[scan] def fromBigDecimal(x: BigDecimal): Numeric =
    Numeric.assertFromBigDecimal(scale, x)

  private def div(a: Numeric, b: Numeric): Numeric =
    damlRight(Numeric.divide(scale, a, b))

  private def mul(a: Numeric, b: Numeric): Numeric =
    damlRight(Numeric.multiply(scale, a, b))

  private def sub(a: Numeric, b: Numeric): Numeric =
    damlRight(Numeric.subtract(a, b))
}
