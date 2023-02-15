package com.daml.network.splitwell.config

import com.digitalasset.canton.DomainAlias
import com.daml.network.config.{
  AutomationConfig,
  CoinLedgerApiClientConfig,
  CoinRemoteParticipantConfig,
  LocalCNNodeConfig,
  RemoteCNNodeConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class SplitwellDomainConfig(
    global: DomainAlias,
    splitwell: DomainAlias,
)

case class SplitwellAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    providerUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    automation: AutomationConfig = AutomationConfig(),
    domains: SplitwellDomainConfig,
) extends LocalCNNodeConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "splitwell"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class SplitwellAppClientConfig(
    // Admin API for reads.
    adminApi: ClientConfig,
    // Ledger API for writes.
    ledgerApi: CoinLedgerApiClientConfig,
    remoteScan: ScanAppClientConfig,
    ledgerApiUser: String,
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
