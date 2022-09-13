package com.daml.network.directory.user.config

import com.daml.network.config.LocalCoinConfig
import com.daml.network.directory.provider.config.RemoteDirectoryProviderAppConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalDirectoryUserAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // temporary. We likely want only an email here eventually once we are using Oauth2 with wallets
    damlUser: String = "directoryUser",
    remoteParticipant: RemoteParticipantConfig,
    remoteDirectoryProvider: RemoteDirectoryProviderAppConfig,
) extends LocalCoinConfig // TODO(i736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "directoryUser"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}
