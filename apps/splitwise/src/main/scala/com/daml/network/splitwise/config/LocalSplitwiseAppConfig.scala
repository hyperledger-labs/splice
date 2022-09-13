package com.daml.network.splitwise.config

import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalSplitwiseAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // TODO(#661) Rename this to provider user once we finished this.
    damlUser: String,
    remoteParticipant: RemoteParticipantConfig,
    remoteScan: RemoteScanAppConfig,
) extends LocalCoinConfig // TODO(i736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "splitwise"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteSplitwiseAppConfig(
    // Admin API for reads.
    adminApi: ClientConfig,
    // Ledger API for writes.
    ledgerApi: ClientConfig,
    remoteScan: RemoteScanAppConfig,
    damlUser: String,
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
