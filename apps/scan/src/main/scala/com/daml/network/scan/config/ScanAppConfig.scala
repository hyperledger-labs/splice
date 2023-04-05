package com.daml.network.scan.config

import com.daml.network.config.{
  AutomationConfig,
  CNRemoteParticipantConfig,
  LocalCNNodeConfig,
  RemoteCNNodeConfig,
}
import com.digitalasset.canton.config.*
import com.daml.network.config.CNHttpClientConfig

trait BaseScanAppConfig {}

case class ScanAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    svcUser: String,
    override val remoteParticipant: CNRemoteParticipantConfig,
    domains: ScanDomainConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    enableCoinRulesUpgrade: Boolean = false,
) extends LocalCNNodeConfig
    with BaseScanAppConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class ScanAppClientConfig(
    adminApi: CNHttpClientConfig,

    /** Configures how long clients cache the CoinRules they receive from the ScanApp
      * before rehydrating their cached value. In general, clients have a mechanism to invalidate
      * their CoinRules cache if it becomes outdated, however, as a safety-layer we
      * invalidate it periodically because no CC transactions on a node could go through
      * if its CoinRules cache is outdated and the client never notices and rehydrates it.
      */
    coinRulesCacheTimeToLive: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
) extends RemoteCNNodeConfig
    with BaseScanAppConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
