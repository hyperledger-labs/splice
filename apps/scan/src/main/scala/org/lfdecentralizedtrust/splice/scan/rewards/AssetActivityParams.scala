// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.RewardConfig

import scala.jdk.OptionConverters.*

/** The amulet config parameters used to split the extra burn among featured assets.
  * See CIP-104 for details.
  */
final case class AssetActivityParams(
    extraFeaturedAssetActivityWeight: BigDecimal,
    maxExtraFeaturedAssetActivityWeightPerAsset: BigDecimal,
    dsoAppActivityWeight: BigDecimal,
)

object AssetActivityParams {

  /** Get the params from the reward config of a round.
    * Returns `Some` only if all of the params are set.
    */
  def fromRewardConfig(rewardConfig: RewardConfig): Option[AssetActivityParams] =
    for {
      extraFeaturedAssetActivityWeight <- rewardConfig.extraFeaturedAssetActivityWeight.toScala
      maxExtraFeaturedAssetActivityWeightPerAsset <-
        rewardConfig.maxExtraFeaturedAssetActivityWeightPerAsset.toScala
      dsoAppActivityWeight <- rewardConfig.dsoAppActivityWeight.toScala
    } yield AssetActivityParams(
      extraFeaturedAssetActivityWeight = BigDecimal(extraFeaturedAssetActivityWeight),
      maxExtraFeaturedAssetActivityWeightPerAsset =
        BigDecimal(maxExtraFeaturedAssetActivityWeightPerAsset),
      dsoAppActivityWeight = BigDecimal(dsoAppActivityWeight),
    )
}
