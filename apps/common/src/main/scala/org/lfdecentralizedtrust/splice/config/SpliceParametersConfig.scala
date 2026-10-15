// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.config.{RateLimitersConfig as _, *}
import org.lfdecentralizedtrust.splice.store.{ChoiceContextContractFetcher, HardLimit, Limit}

final case class SpliceParametersConfig(
    batching: BatchingConfig = BatchingConfig(),
    caching: CachingConfigs = CachingConfigs(),
    spliceCachingConfigs: SpliceCachingConfigs = SpliceCachingConfigs(),
    contractFetchLedgerFallbackConfig: ChoiceContextContractFetcher.StoreContractFetcherWithLedgerFallbackConfig =
      ChoiceContextContractFetcher.StoreContractFetcherWithLedgerFallbackConfig(),
    // Do not define any defaults on the class containing the `SpliceParametersConfig` as they'll be overwritten.
    // Do it instead on the app.conf file in `cluster/images/${the_app}/app.conf`
    customTimeouts: Map[String, NonNegativeFiniteDuration] = Map.empty,
    rateLimiting: RateLimitersConfig = RateLimitersConfig(),
    // Configuration for the circuit breaker for ledger API command submissions.
    circuitBreakers: CircuitBreakersConfig = CircuitBreakersConfig(),
    enabledFeatures: EnabledFeaturesConfig = EnabledFeaturesConfig(),
    databaseDefaultLimit: NonNegativeInt = NonNegativeInt.tryCreate(Limit.DefaultMaxPageSize),
) extends LocalNodeParametersConfig {
  override def alphaVersionSupport: Boolean = false
  override def devVersionSupport = false

  override def watchdog: Option[WatchdogConfig] = None

  val defaultLimit: Limit =
    HardLimit.tryCreate(databaseDefaultLimit.unwrap, databaseDefaultLimit.unwrap)
}
