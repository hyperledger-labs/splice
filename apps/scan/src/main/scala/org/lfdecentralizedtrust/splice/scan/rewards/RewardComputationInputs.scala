// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

/** Derived parameters passed to computeRewardTotals. */
final case class RewardIssuanceParams(
    issuancePerFeaturedAppTraffic_CCperMB: BigDecimal,
    threshold_CC: BigDecimal,
    totalIssuanceForFeaturedAppRewards: BigDecimal,
    unclaimedAppRewardAmount: BigDecimal,
)

/** Raw contract inputs for app reward CC computation.
  *
  * @see https://github.com/canton-foundation/cips/blob/main/cip-0104/cip-0104.md#app-reward-computation-details
  */
final case class RewardComputationInputs(
    amuletToIssuePerYear: BigDecimal,
    appRewardPercentage: BigDecimal,
    featuredAppRewardCap: BigDecimal,
    unfeaturedAppRewardCap: BigDecimal,
    developmentFundPercentage: BigDecimal,
    tickDurationMicros: Long,
    amuletPrice: BigDecimal,
    trafficPrice: BigDecimal,
    appRewardCouponThreshold: BigDecimal,
) {
  private val microsPerYear: Long = 365L * 24 * 3600 * 1000000L
  private val roundsPerYear: BigDecimal = BigDecimal(microsPerYear) / BigDecimal(tickDurationMicros)
  private val amuletsToIssueInRound: BigDecimal = amuletToIssuePerYear / roundsPerYear
  private val adjustedAmuletsToIssueInRound: BigDecimal =
    amuletsToIssueInRound * (1 - developmentFundPercentage)
  private val trafficPriceInCCperMB: BigDecimal = trafficPrice / amuletPrice

  def deriveIssuanceParams(totalRoundAppActivityWeight: Long): RewardIssuanceParams = {
    val totalCoupons_CC =
      BigDecimal(totalRoundAppActivityWeight) / BigDecimal(1000000L) * trafficPriceInCCperMB
    val rewardsToIssue = adjustedAmuletsToIssueInRound * appRewardPercentage
    val issuancePerCoupon = computeIssuanceTranche(
      rewardsToIssue = rewardsToIssue,
      capPerCoupon = featuredAppRewardCap,
      totalCoupons = totalCoupons_CC,
    )
    RewardIssuanceParams(
      issuancePerFeaturedAppTraffic_CCperMB = trafficPriceInCCperMB * issuancePerCoupon,
      threshold_CC = appRewardCouponThreshold / amuletPrice,
      totalIssuanceForFeaturedAppRewards = rewardsToIssue,
      unclaimedAppRewardAmount = rewardsToIssue - issuancePerCoupon * totalCoupons_CC,
    )
  }

  /** Replicates Daml computeIssuanceTranche (Splice/Issuance.daml:142-153) for
    * totalUnfeaturedAppRewardCoupons = 0.
    */
  private def computeIssuanceTranche(
      rewardsToIssue: BigDecimal,
      capPerCoupon: BigDecimal,
      totalCoupons: BigDecimal,
  ): BigDecimal = {
    if (totalCoupons == 0) BigDecimal(0)
    else (rewardsToIssue / totalCoupons) min capPerCoupon
  }
}
