package com.daml.network.directory.config

import com.daml.network.config.{AutomationConfig, LocalCoinConfig, RemoteCoinConfig}
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config.*
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalDirectoryAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String,
    override val remoteParticipant: RemoteParticipantConfig,
    remoteScan: RemoteScanAppConfig,
    automation: AutomationConfig = AutomationConfig(),
) extends LocalCoinConfig {
  override val nodeTypeName: String = "directory"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteDirectoryAppConfig(
    damlUser: String,
    adminApi: ClientConfig,
    ledgerApi: ClientConfig,
    ledgerApiToken: Option[String],
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
