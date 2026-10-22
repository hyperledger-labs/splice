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

  val DefaultExtraFeaturedAssetActivityWeight: BigDecimal = BigDecimal("0.2")
  val DefaultMaxExtraFeaturedAssetActivityWeightPerAsset: BigDecimal = BigDecimal("0.1")
  val DefaultDsoAppActivityWeight: BigDecimal = BigDecimal("0.1")

  /** Get the params from the reward config of a round.
    * The defaults are used for any of the params that are not set,
    * or for all of them if the round has no reward config.
    */
  def fromRewardConfig(rewardConfig: Option[RewardConfig]): AssetActivityParams =
    AssetActivityParams(
      extraFeaturedAssetActivityWeight = rewardConfig
        .flatMap(_.extraFeaturedAssetActivityWeight.toScala)
        .fold(DefaultExtraFeaturedAssetActivityWeight)(BigDecimal(_)),
      maxExtraFeaturedAssetActivityWeightPerAsset = rewardConfig
        .flatMap(_.maxExtraFeaturedAssetActivityWeightPerAsset.toScala)
        .fold(DefaultMaxExtraFeaturedAssetActivityWeightPerAsset)(BigDecimal(_)),
      dsoAppActivityWeight = rewardConfig
        .flatMap(_.dsoAppActivityWeight.toScala)
        .fold(DefaultDsoAppActivityWeight)(BigDecimal(_)),
    )
}
