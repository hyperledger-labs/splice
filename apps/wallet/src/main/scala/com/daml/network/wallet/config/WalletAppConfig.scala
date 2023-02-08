package com.daml.network.wallet.config

import com.digitalasset.canton.DomainAlias
import com.daml.network.auth.AuthConfig
import com.daml.network.config.{
  AuthTokenSourceConfig,
  AutomationConfig,
  CoinHttpClientConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class WalletDomainConfig(
    global: DomainAlias
)

case class WalletAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    serviceUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    validator: WalletRemoteValidatorAppConfig,
    validatorAuth: AuthTokenSourceConfig,
    auth: AuthConfig,
    automation: AutomationConfig = AutomationConfig(),
    treasury: TreasuryConfig = TreasuryConfig(),
    domains: WalletDomainConfig,
) extends LocalCoinConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "wallet"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

// Inlined to avoid a dependency
case class WalletRemoteValidatorAppConfig(
    adminApi: CoinHttpClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class WalletAppClientConfig(
    adminApi: CoinHttpClientConfig,
    ledgerApiUser: String,
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
