package com.daml.network.scan.config

import com.daml.network.config.{
  AutomationConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.digitalasset.canton.config.*
import com.daml.network.config.CoinHttpClientConfig

trait BaseScanAppConfig {}

case class ScanAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    svcUser: String,
    override val remoteParticipant: CoinRemoteParticipantConfig,
    domains: ScanDomainConfig,
    automation: AutomationConfig = AutomationConfig(),
) extends LocalCoinConfig
    with BaseScanAppConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class ScanAppClientConfig(
    adminApi: CoinHttpClientConfig
) extends RemoteCoinConfig
    with BaseScanAppConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
