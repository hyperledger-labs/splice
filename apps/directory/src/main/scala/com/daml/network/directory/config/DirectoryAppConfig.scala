package com.daml.network.directory.config

import com.daml.network.config.{
  AutomationConfig,
  CoinHttpClientConfig,
  CoinLedgerApiClientConfig,
  CoinRemoteParticipantConfig,
  LocalCNNodeConfig,
  RemoteCNNodeConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class LocalDirectoryAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    override val remoteParticipant: CoinRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    automation: AutomationConfig = AutomationConfig(),
    domains: DirectoryDomainConfig,
) extends LocalCNNodeConfig {
  override val nodeTypeName: String = "directory"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class RemoteDirectoryAppConfig(
    ledgerApiUser: String,
    adminApi: CoinHttpClientConfig,
    ledgerApi: CoinLedgerApiClientConfig,
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
