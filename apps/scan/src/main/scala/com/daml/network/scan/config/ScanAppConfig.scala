package com.daml.network.scan.config

import com.daml.network.config.{
  AutomationConfig,
  CNDbConfig,
  CNNodeBackendConfig,
  CNNodeParametersConfig,
  CNParticipantClientConfig,
  HttpCNNodeClientConfig,
  NetworkAppClientConfig,
}
import com.digitalasset.canton.config.*

trait BaseScanAppConfig {}

/** @param miningRoundsCacheTimeToLiveOverride Intended only for testing!
  *                                            By default depends on the `tickDuration` of rounds. This setting overrides that.
  */
case class ScanAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CNDbConfig,
    svUser: String,
    override val participantClient: CNParticipantClientConfig,
    sequencerAdminClient: ClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    ingestFromParticipantBegin: Boolean = false,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration] = None,
    parameters: CNNodeParametersConfig = CNNodeParametersConfig(batching = BatchingConfig()),
) extends CNNodeBackendConfig
    with BaseScanAppConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class ScanAppClientConfig(
    adminApi: NetworkAppClientConfig,

    /** Configures how long clients cache the CoinRules they receive from the ScanApp
      * before rehydrating their cached value. In general, clients have a mechanism to invalidate
      * their CoinRules cache if it becomes outdated, however, as a safety-layer we
      * invalidate it periodically because no CC transactions on a node could go through
      * if its CoinRules cache is outdated and the client never notices and rehydrates it.
      */
    coinRulesCacheTimeToLive: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
) extends HttpCNNodeClientConfig
    with BaseScanAppConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
