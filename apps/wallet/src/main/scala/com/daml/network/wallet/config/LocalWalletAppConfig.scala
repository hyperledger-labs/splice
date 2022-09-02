package com.daml.network.wallet.config

import com.daml.network.config.LocalCoinConfig
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalWalletAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // temporary. We likely want only an email here eventually once we are using Oauth2 with wallets
    damlUser: String = "wallet",
    remoteParticipant: RemoteParticipantConfig,
    remoteScan: RemoteScanAppConfig,
) extends LocalCoinConfig // TODO(Arne): fork or generalize this trait.
    {
  override val nodeTypeName: String = "wallet"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

  def toRemoteConfig: RemoteWalletAppConfig = RemoteWalletAppConfig(adminApi.clientConfig)

}
