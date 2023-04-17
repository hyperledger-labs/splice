package com.daml.network.directory.config

import com.daml.network.config.{
  AutomationConfig,
  CNLedgerApiClientConfig,
  CNRemoteParticipantConfig,
  LocalCNNodeConfig,
  RemoteCNNodeConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class LocalDirectoryAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    override val remoteParticipant: CNRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: DirectoryDomainConfig,
) extends LocalCNNodeConfig {
  override val nodeTypeName: String = "directory"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class RemoteDirectoryAppConfig(
    ledgerApiUser: String,
    adminApi: ClientConfig,
    ledgerApi: CNLedgerApiClientConfig,
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
