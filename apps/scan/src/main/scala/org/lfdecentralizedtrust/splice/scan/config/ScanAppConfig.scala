// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.data.CantonTimestamp
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
  ParticipantClientConfig,
  S3Config,
  SpliceBackendConfig,
  SpliceInstanceNamesConfig,
  SpliceParametersConfig,
  SplicePostgresConfig,
}

import org.lfdecentralizedtrust.splice.store.Limit

import java.time.Instant

trait BaseScanAppConfig {}

final case class ScanSynchronizerConfig(
    sequencer: FullClientConfig,
    mediator: FullClientConfig,
    cantonBft: Option[CantonBftPeerConfig],
)

final case class MediatorVerdictIngestionConfig(
    /** Max verdicts items for DB insert batch. */
    batchSize: Int = 50
)

final case class BulkStorageBackfillingConfig(
    enabled: Boolean = false,
    pollingInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    noPeerWillHoldRetryInterval: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofMinutes(10),
    downloadParallelism: Int = 1,
    pageSize: Int = Limit.DefaultMaxPageSize,
)

final case class BulkStorageConfig(
    /** When new snapshot is not yet available, how long to wait for a new one. */
    snapshotPollingInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    // When more updates are not yet available, how long to wait for more.
    updatesPollingInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    // When BFT was not reached on objects, how long to wait before retrying.
    bftRetryInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    // The maximum parallelization for uploading multiple parts of the same object
    maxParallelPartUploads: Int = 4,
    staging: Option[S3Config] = None,
    committed: Option[S3Config] = None,
    bftCheckEnabled: Boolean = true,
    // temporary buffer size for zstd compression, safe to modify locally
    tmpBufferSize: Int = 1024 * 1024 * 10,
    // Number of elements to read from the DB at once when reading updates or snapshots for bulk storage. Safe to modify locally.
    dbReadChunkSize: Int = 100,
    /** When enabled, the app will reset all progress markers thus force recomputing data from genesis.
      * Note that this does not delete any existing data, you usually would want to do that before setting
      * this flag. Also, after restarting the app once with this flag enabled, you'd want to disable it back
      * to avoid having the markers reset on every restart.
      * TODO(#6251): this makes sense for initial stages of testing&deploying bulk storage, in case of
      *   encountered issues, but will not make sense when we start pruning the data from scan. We should remove
      *   this before starting to prune data.
      */
    debugForceStartFromGenesis: Boolean = false,
    /** When enabled, the app forgets its recorded history start on startup, so it is determined again from the
      * current data (founding SV, history backfilled from genesis, or the DSO party hosting time). The history start
      * decides where bulk storage backfilling stops and which times this Scan answers as backfilling. If bulk
      * storage progress was already built on a wrong history start, also set debugForceStartFromGenesis.
      * As with that flag, restart once with it enabled and then disable it again.
      */
    debugReresolveHistoryStart: Boolean = false,
    /** A list of S3 object keys that this instance should not save to the committed bucket, and instead only
      * delete from staging. To be used only in extreme cases where we decide to accept a BFT disagreement,
      * and have the (minority of) disagreeing instances simply skip the broken objects.
      * Should typically be used in test environments only.
      */
    debugObjectsToNotCommit: Seq[String] = Seq.empty,
    backfilling: BulkStorageBackfillingConfig = BulkStorageBackfillingConfig(),
)

/** @param miningRoundsCacheTimeToLiveOverride Intended only for testing!
  *                                            By default depends on the `tickDuration` of rounds. This setting overrides that.
  */
case class ScanAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    postgres: SplicePostgresConfig = SplicePostgresConfig(),
    svUser: String,
    override val participantClient: ParticipantClientConfig,
    synchronizerNodes: ScanSynchronizerNodesConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    mediatorVerdictIngestion: MediatorVerdictIngestionConfig = MediatorVerdictIngestionConfig(),
    isFirstSv: Boolean = false,
    // Max rounding error tolerated wrt actual total of minting allowances
    // and the per-round minting allowance from the CC whitepaper.
    rewardMintingAllowanceTolerance: BigDecimal = BigDecimal(0.1),
    // Reward-accounting data is retained for this duration and may get pruned afterwards.
    rewardAccountingRetentionPeriod: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofDays(7),
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration] = None,
    enableForcedAcsSnapshots: Boolean = false,
    // Whether each ACS snapshot should be stored in its own table
    perAcsSnapshotTablesEnabled: Boolean = false,
    analyzableTimeWindow: AnalyzableTimeWindowConfig = AnalyzableTimeWindowConfig(),
    // The migration id is normally read from the DB (the highest known migration id in the
    // update history). It only needs to be resolved from a sponsor to bootstrap a node that does
    // not yet have any migration id in its DB (e.g. a freshly joining scan). In that case, the
    // migration id is fetched from the sponsor scan configured via `sponsorScanUrl`, the same way
    // a joining SV fetches it from its sponsoring SV.
    sponsorScanUrl: Option[NetworkAppClientConfig] = None,
    parameters: SpliceParametersConfig = SpliceParametersConfig(),
    spliceInstanceNames: SpliceInstanceNamesConfig,
    updateHistoryBackfillEnabled: Boolean = true,
    updateHistoryBackfillBatchSize: Int = 100,
    updateHistoryBackfillImportUpdatesEnabled: Boolean = true,
    updateHistoryMaxPageSize: Int = Limit.DefaultMaxPageSize,
    txLogBackfillEnabled: Boolean = true,
    txLogBackfillBatchSize: Int = 100,
    cache: ScanCacheConfig = ScanCacheConfig(),
    acsStoreDescriptorUserVersion: Option[Long] = None,
    txLogStoreDescriptorUserVersion: Option[Long] = None,
    activityIngestionUserVersion: Option[Long] = None,
    bulkStorage: BulkStorageConfig = BulkStorageConfig(),
    tokenStandardSettlement: TokenStandardConfig.SettlementConfig =
      TokenStandardConfig.SettlementConfig(),
    publicUrl: Option[Uri] = None,
    // The thresholdDate from which external transaction hashes are included in the updates from internal ScanAPIs.
    // TODO(#4249): use on-ledger synchronization for switching record times
    externalTransactionHashThresholdTime: Option[Instant] =
      ScanAppBackendConfig.DefaultExternalTransactionHashThresholdTime,
    globalSynchronizerAlias: SynchronizerAlias = SynchronizerAlias.tryCreate("global"),
    rollForwardLsu: Option[ScanRollForwardLsuConfig] = None,
    // Set to false to disable the DB-level exclusive lock that prevents two scan instances
    // from running concurrently against the same database.  Only disable for migration scenarios
    // where intentional overlap is required.
    instanceLockEnabled: Boolean = true,
) extends SpliceBackendConfig
    with BaseScanAppConfig // TODO(DACH-NY/canton-network-node#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class AnalyzableTimeWindowConfig(
    duration: NonNegativeDuration = AnalyzableTimeWindowConfig.UnlimitedAtw
) {
  require(
    duration.duration > AnalyzableTimeWindowConfig.MinimumAtw.duration,
    s"The analyzable time window must be at least ${AnalyzableTimeWindowConfig.MinimumAtw}",
  )
}
object AnalyzableTimeWindowConfig {
  private val MinimumAtw = NonNegativeDuration.tryFromJavaDuration(java.time.Duration.ofDays(7L))
  val UnlimitedAtw: NonNegativeDuration =
    NonNegativeDuration.tryFromDuration(scala.concurrent.duration.Duration.Inf)

}

final case class ScanRollForwardLsuConfig(
    upgradeTime: Option[
      CantonTimestamp
    ] // If not set, we assume that there is an LsuAnnouncement on the legacy synchronizer.
)

object ScanAppBackendConfig {
  val DefaultExternalTransactionHashThresholdTime: Option[Instant] =
    Some(java.time.Instant.parse("2030-01-01T00:00:00Z"))
}

final case class ScanSynchronizerNodesConfig(
    current: ScanSynchronizerConfig,
    successor: Option[ScanSynchronizerConfig],
    legacy: Option[ScanSynchronizerConfig],
)

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
    voteRequests: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofMinutes(1),
      maxSize = 1000,
    ),
    internedStrings: CacheConfig = CacheConfig(
      ttl = NonNegativeFiniteDuration.ofDays(365L),
      maxSize = 10000,
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
