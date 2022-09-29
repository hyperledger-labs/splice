package com.daml.network.validator.config

import com.daml.network.config.{LocalCoinConfig, RemoteCoinConfig}
import com.daml.network.scan.config.RemoteScanAppConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import java.nio.file.Path

case class AppInstance(
    serviceUser: String,
    dars: Seq[Path],
)

case class LocalValidatorAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String,
    walletServiceUser: String,
    appInstances: Map[String, AppInstance],
    remoteParticipant: RemoteParticipantConfig,
    remoteScan: RemoteScanAppConfig,
) extends LocalCoinConfig // TODO(i736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "validator"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteValidatorAppConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
