package com.daml.network.scan.config

import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

trait BaseScanAppConfig {}

case class LocalScanAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    svcUser: String = "svc",
    override val remoteParticipant: RemoteParticipantConfig,
) extends LocalCoinConfig
    with BaseScanAppConfig // TODO(142): fork or generalize this trait.
    {
  override val nodeTypeName: String = "scan"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteScanAppConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig
    with BaseScanAppConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
