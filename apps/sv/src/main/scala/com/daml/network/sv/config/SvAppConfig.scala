package com.daml.network.sv.config

import com.daml.network.config.{
  AutomationConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.digitalasset.canton.config.*
import com.digitalasset.canton.time.NonNegativeFiniteDuration as NonNegativeFiniteDurationT

case class LocalSvAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    automation: AutomationConfig = AutomationConfig(),
    initialTickDuration: NonNegativeFiniteDurationT = NonNegativeFiniteDurationT.ofSeconds(150),
    // TODO(M3-07): use price from SvRules
    coinPrice: BigDecimal = 1.0,
) extends LocalCoinConfig {
  override val nodeTypeName: String = "SV"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class RemoteSvAppConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
