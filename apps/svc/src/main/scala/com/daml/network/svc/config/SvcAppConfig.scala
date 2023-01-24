package com.daml.network.svc.config

import com.daml.network.config.{
  AutomationConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.digitalasset.canton.config.*
import com.digitalasset.canton.time.NonNegativeFiniteDuration as NonNegativeFiniteDurationT

case class SvcAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    automation: AutomationConfig = AutomationConfig(),
    initialTickDuration: NonNegativeFiniteDurationT = NonNegativeFiniteDurationT.ofSeconds(150),
    // TODO(#2168): test edge cases.
    initialMaxNumInputs: Int = 100,
    // TODO(M3-07): use price from SvcRules
    coinPrice: BigDecimal = 1.0,
) extends LocalCoinConfig {
  override val nodeTypeName: String = "SVC"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class SvcAppClientConfig(
    adminApi: ClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
