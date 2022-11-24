package com.daml.network.svc.config

import com.daml.network.config.{AutomationConfig, LocalCoinConfig, RemoteCoinConfig}
import com.digitalasset.canton.config.*
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalSvcAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String,
    remoteParticipant: RemoteParticipantConfig,
    automation: AutomationConfig = AutomationConfig(),
) extends LocalCoinConfig {
  override val nodeTypeName: String = "SVC"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteSvcAppConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
