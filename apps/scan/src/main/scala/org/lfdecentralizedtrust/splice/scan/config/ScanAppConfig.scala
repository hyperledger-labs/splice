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

/** @param miningRoundsCacheTimeToLiveOverride Intended only for testing!
  *                                            By default depends on the `tickDuration` of rounds. This setting overrides that.
  */
case class ScanAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    svUser: String,
    override val participantClient: ParticipantClientConfig,
    sequencerAdminClient: FullClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    isFirstSv: Boolean = false,
    ingestFromParticipantBegin: Boolean = true,
    ingestUpdateHistoryFromParticipantBegin: Boolean = true,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration] = None,
    acsSnapshotPeriodHours: Int = 3,
    enableForcedAcsSnapshots: Boolean = false,
    // TODO(DACH-NY/canton-network-node#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    parameters: SpliceParametersConfig = SpliceParametersConfig(batching = BatchingConfig()),
    spliceInstanceNames: SpliceInstanceNamesConfig,
    updateHistoryBackfillEnabled: Boolean = true,
    updateHistoryBackfillBatchSize: Int = 100,
    updateHistoryBackfillImportUpdatesEnabled: Boolean = false,
    txLogBackfillEnabled: Boolean = true,
    txLogBackfillBatchSize: Int = 100,
    bftSequencers: Seq[BftSequencerConfig] = Seq.empty,
    cache: ScanCacheConfig = ScanCacheConfig(),
    initialRound: Long = 0L,
    // TODO(#1164): Enable by default
) extends SpliceBackendConfig
    with BaseScanAppConfig // TODO(DACH-NY/canton-network-node#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

final case class ScanCacheConfig(
    svNodeStateTtl: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30)
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
