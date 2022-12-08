package com.daml.network.svc.config

import com.daml.network.config.{
  AutomationConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.digitalasset.canton.config.*

// Canton currently has <..>.time.NonNegativeFiniteDuration and <..>.config.NonNegativeFiniteDuration, and
// confusingly they only have defined a [[ConfigReader]] for time.NonNegativeFiniteDuration
import com.digitalasset.canton.time.NonNegativeFiniteDuration as NonNegativeFiniteDurationT

case class LocalSvcAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    automation: AutomationConfig = AutomationConfig(),
    initialTickDuration: NonNegativeFiniteDurationT = NonNegativeFiniteDurationT.ofSeconds(150),
) extends LocalCoinConfig {
  override val nodeTypeName: String = "SVC"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteSvcAppConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
