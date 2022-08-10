package com.daml.network.svc.config

import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalSvcAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String = "svc",
    remoteParticipant: RemoteParticipantConfig,
) extends LocalCoinConfig {
  override val nodeTypeName: String = "SVC"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteSvcAppConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
