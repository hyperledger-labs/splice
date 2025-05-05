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
    // Map from domain id prefix to sequencer/mediator config
    // This is for the Poc from #13301
    synchronizers: Map[String, ScanSynchronizerConfig] = Map.empty,
    override val automation: AutomationConfig = AutomationConfig(),
    isFirstSv: Boolean = false,
    ingestFromParticipantBegin: Boolean = true,
    ingestUpdateHistoryFromParticipantBegin: Boolean = true,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration] = None,
    acsSnapshotPeriodHours: Int = 3,
    enableForcedAcsSnapshots: Boolean = false,
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    parameters: SpliceParametersConfig = SpliceParametersConfig(batching = BatchingConfig()),
    // TODO(#13301) Remove this flag
    supportsSoftDomainMigrationPoc: Boolean = false,
    spliceInstanceNames: SpliceInstanceNamesConfig,
    updateHistoryBackfillEnabled: Boolean = true,
    updateHistoryBackfillBatchSize: Int = 100,
) extends SpliceBackendConfig
    with BaseScanAppConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

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
