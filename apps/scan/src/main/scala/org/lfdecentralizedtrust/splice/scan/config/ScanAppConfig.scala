// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.config.*
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
  ParticipantClientConfig,
  SpliceBackendConfig,
  SpliceInstanceNamesConfig,
  SpliceParametersConfig,
}

trait BaseScanAppConfig {}

final case class ScanSynchronizerConfig(
    sequencer: FullClientConfig,
    mediator: FullClientConfig,
)

final case class MediatorVerdictIngestionConfig(
    /** Max verdicts items for DB insert batch. */
    batchSize: Int = 50,
    /** Max time window to wait for DB insert batch. */
    batchMaxWait: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(1),
    /** Delay before restart on stream failure. */
    restartDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(5),
)

final case class SequencerTrafficIngestionConfig(
    /** Whether sequencer traffic ingestion is enabled. */
    enabled: Boolean = false,
    /** Max traffic summary items for DB insert batch. */
    batchSize: Int = 50,
    /** Max time window to wait for DB insert batch. */
    batchMaxWait: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(1),
    /** Delay before restart on stream failure. */
    restartDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(5),
)

/** @param miningRoundsCacheTimeToLiveOverride Intended only for testing!
  *                                            By default depends on the `tickDuration` of rounds. This setting overrides that.
  */
case class ScanAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    svUser: String,
    override val participantClient: ParticipantClientConfig,
    sequencerAdminClient: FullClientConfig,
    mediatorAdminClient: FullClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    mediatorVerdictIngestion: MediatorVerdictIngestionConfig = MediatorVerdictIngestionConfig(),
    sequencerTrafficIngestion: SequencerTrafficIngestionConfig = SequencerTrafficIngestionConfig(),
    isFirstSv: Boolean = false,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration] = None,
    enableForcedAcsSnapshots: Boolean = false,
    // TODO(DACH-NY/canton-network-node#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    parameters: SpliceParametersConfig = SpliceParametersConfig(),
    spliceInstanceNames: SpliceInstanceNamesConfig,
    updateHistoryBackfillEnabled: Boolean = true,
    updateHistoryBackfillBatchSize: Int = 100,
    updateHistoryBackfillImportUpdatesEnabled: Boolean = true,
    txLogBackfillEnabled: Boolean = true,
    txLogBackfillBatchSize: Int = 100,
    bftSequencers: Seq[BftSequencerConfig] = Seq.empty,
    cache: ScanCacheConfig = ScanCacheConfig(),
    acsStoreDescriptorUserVersion: Option[Long] = None,
    txLogStoreDescriptorUserVersion: Option[Long] = None,
) extends SpliceBackendConfig
    with BaseScanAppConfig // TODO(DACH-NY/canton-network-node#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

final case class ScanCacheConfig(
    svNodeState: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 100,
    ),
    openMiningRounds: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    amuletRules: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    ansRules: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    totalRewardsCollected: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(2),
      maxSize = 1,
    ),
    rewardsCollectedInRound: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    amuletConfigForRound: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    roundOfLatestData: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    topProvidersByAppRewards: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(2),
      maxSize = 2000,
    ),
    topValidators: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(2),
      maxSize = 2000,
    ),
    validatorLicenseByValidator: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    totalPurchasedMemberTraffic: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 2000,
    ),
    cachedByParty: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 2000,
    ),
    aggregatedRounds: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofSeconds(30),
      maxSize = 1,
    ),
    roundTotals: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    voteRequests: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
)

final case class CacheConfig(
    ttl: NonNegativeFiniteDuration,
    maxSize: Long,
)

case class ScanAppClientConfig(
    adminApi: NetworkAppClientConfig,

    /** Configures how long clients cache the AmuletRules they receive from the ScanApp
      * before rehydrating their cached value. In general, clients have a mechanism to invalidate
      * their AmuletRules cache if it becomes outdated, however, as a safety-layer we
      * invalidate it periodically because no CC transactions on a node could go through
      * if its AmuletRules cache is outdated and the client never notices and rehydrates it.
      */
    amuletRulesCacheTimeToLive: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
) extends HttpClientConfig
    with BaseScanAppConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

object ScanAppClientConfig {
  val DefaultAmuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)

  val DefaultScansRefreshInterval: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.ofMinutes(10)
}
