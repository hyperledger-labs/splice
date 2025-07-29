// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.{
  BatchingConfig,
  CachingConfigs,
  LocalNodeParametersConfig,
  NonNegativeFiniteDuration,
  SessionSigningKeysConfig,
  WatchdogConfig,
}
import org.lfdecentralizedtrust.splice.util.SpliceRateLimitConfig

final case class SpliceParametersConfig(
    batching: BatchingConfig = BatchingConfig(),
    caching: CachingConfigs = CachingConfigs(),
    customTimeouts: Map[String, NonNegativeFiniteDuration] = Map.empty,
    rateLimiting: RateLimitersConfig =
      RateLimitersConfig(SpliceRateLimitConfig(enabled = true, ratePerSecond = 200), Map.empty),
) extends LocalNodeParametersConfig {
  override def alphaVersionSupport: Boolean = false

  override def watchdog: Option[WatchdogConfig] = None

  override def sessionSigningKeys: SessionSigningKeysConfig = ???
}
