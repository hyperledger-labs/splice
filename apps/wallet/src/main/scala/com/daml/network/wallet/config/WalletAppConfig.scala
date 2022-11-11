package com.daml.network.wallet.config

import com.daml.network.auth.AuthConfig
import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config.*
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalWalletAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    serviceUser: String,
    remoteParticipant: RemoteParticipantConfig,
    remoteParticipantToken: Option[String] = None,
    remoteScan: RemoteScanAppConfig,
    validator: WalletRemoteValidatorAppConfig,
    auth: AuthConfig,
) extends LocalCoinConfig // TODO(i736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "wallet"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

// Inlined to avoid a dependency
case class WalletRemoteValidatorAppConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}

case class RemoteWalletAppConfig(
    adminApi: ClientConfig,
    damlUser: String,
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
