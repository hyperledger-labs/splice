// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.config

import com.daml.network.config.{
  AutomationConfig,
  SpliceDbConfig,
  SpliceBackendConfig,
  SpliceParametersConfig,
  SpliceInstanceNamesConfig,
  ParticipantClientConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
}
import com.digitalasset.canton.config.*

trait BaseScanAppConfig {}

final case class ScanSynchronizerConfig(
    sequencer: ClientConfig,
    mediator: ClientConfig,
)

/** @param miningRoundsCacheTimeToLiveOverride Intended only for testing!
  *                                            By default depends on the `tickDuration` of rounds. This setting overrides that.
  */
case class ScanAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: SpliceDbConfig,
    svUser: String,
    override val participantClient: ParticipantClientConfig,
    sequencerAdminClient: ClientConfig,
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
}
