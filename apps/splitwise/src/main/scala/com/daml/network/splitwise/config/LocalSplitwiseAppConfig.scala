package com.daml.network.splitwise.config

import com.daml.network.config.LocalCoinConfig
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalSplitwiseAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // temporary. We likely want only an email here eventually once we are using Oauth2 with wallets
    damlUser: String = "splitwise",
    remoteParticipant: RemoteParticipantConfig,
    remoteScan: RemoteScanAppConfig,
) extends LocalCoinConfig // TODO(Arne): fork or generalize this trait.
    {
  override val nodeTypeName: String = "splitwise"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}
