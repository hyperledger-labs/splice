package com.daml.network.validator.config

import com.daml.network.auth.AuthConfig
import com.daml.network.config.{
  AutomationConfig,
  CoinHttpClientConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

import java.nio.file.Path

case class AppInstance(
    serviceUser: String,
    dars: Seq[Path],
)

case class ValidatorAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String,
    walletServiceUser: String,
    auth: AuthConfig,
    appInstances: Map[String, AppInstance],
    remoteParticipant: CoinRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    automation: AutomationConfig = AutomationConfig(),
) extends LocalCoinConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "validator"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class ValidatorAppClientConfig(
    adminApi: CoinHttpClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
