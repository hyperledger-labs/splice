package com.daml.network.scan.config

import com.daml.network.config.{AutomationConfig, LocalCoinConfig, RemoteCoinConfig}
import com.digitalasset.canton.config.*
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

trait BaseScanAppConfig {}

case class LocalScanAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    svcUser: String,
    override val remoteParticipant: RemoteParticipantConfig,
    automation: AutomationConfig = AutomationConfig(),
) extends LocalCoinConfig
    with BaseScanAppConfig // TODO(i736): fork or generalize this trait.
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
