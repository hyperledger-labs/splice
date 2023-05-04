package com.daml.network.directory.config

import com.daml.network.config.{
  AutomationConfig,
  CNLedgerApiClientConfig,
  CNParticipantClientConfig,
  CNNodeBackendConfig,
  CNNodeClientConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class DirectoryAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    override val participantClient: CNParticipantClientConfig,
    scanClient: ScanAppClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: DirectoryDomainConfig,
) extends CNNodeBackendConfig {
  override val nodeTypeName: String = "directory"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class DirectoryAppClientConfig(
    ledgerApiUser: String,
    adminApi: ClientConfig,
    ledgerApi: CNLedgerApiClientConfig,
) extends CNNodeClientConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
