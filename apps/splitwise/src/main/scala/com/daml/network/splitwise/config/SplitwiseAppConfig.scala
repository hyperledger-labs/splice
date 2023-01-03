package com.daml.network.splitwise.config

import com.daml.network.config.{
  AutomationConfig,
  CoinLedgerApiClientConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class SplitwiseAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    providerUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    automation: AutomationConfig = AutomationConfig(),
) extends LocalCoinConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "splitwise"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class SplitwiseAppClientConfig(
    // Admin API for reads.
    adminApi: ClientConfig,
    // Ledger API for writes.
    ledgerApi: CoinLedgerApiClientConfig,
    remoteScan: ScanAppClientConfig,
    damlUser: String,
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
