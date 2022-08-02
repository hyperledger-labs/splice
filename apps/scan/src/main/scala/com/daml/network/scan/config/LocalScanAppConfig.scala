package com.daml.network.scan.config

import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

trait BaseScanAppConfig {
  def remoteParticipant: RemoteParticipantConfig
}

case class LocalScanAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // temporary. We likely want only an email here eventually once we are using Oauth2 with wallets
    svcUser: String = "scan",
    override val remoteParticipant: RemoteParticipantConfig,
) extends LocalCoinConfig
    with BaseScanAppConfig // TODO(142): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteScanAppConfig(
    adminApi: ClientConfig,
    override val remoteParticipant: RemoteParticipantConfig,
) extends RemoteCoinConfig
    with BaseScanAppConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
