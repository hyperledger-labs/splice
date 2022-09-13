package com.daml.network.directory.provider.config

import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalDirectoryProviderAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String,
    override val remoteParticipant: RemoteParticipantConfig,
    remoteScan: RemoteScanAppConfig,
) extends LocalCoinConfig {
  override val nodeTypeName: String = "directoryProvider"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteDirectoryProviderAppConfig(
    damlUser: String,
    adminApi: ClientConfig,
    ledgerApi: ClientConfig,
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
