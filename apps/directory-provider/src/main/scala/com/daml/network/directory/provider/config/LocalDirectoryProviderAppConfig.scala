package com.daml.network.directory.provider.config

import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

trait BaseDirectoryProviderAppConfig {
  def remoteParticipant: RemoteParticipantConfig
}

case class LocalDirectoryProviderAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // temporary. We likely want only an email here eventually once we are using Oauth2 with wallets
    damlUser: String = "directoryProvider",
    override val remoteParticipant: RemoteParticipantConfig,
    remoteScan: RemoteScanAppConfig,
) extends LocalCoinConfig
    with BaseDirectoryProviderAppConfig // TODO(i736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "directoryProvider"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteDirectoryProviderAppConfig(
    adminApi: ClientConfig,
    override val remoteParticipant: RemoteParticipantConfig,
) extends RemoteCoinConfig
    with BaseDirectoryProviderAppConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
