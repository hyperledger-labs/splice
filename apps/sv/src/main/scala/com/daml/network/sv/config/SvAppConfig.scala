package com.daml.network.sv.config

import com.daml.network.config.{
  AutomationConfig,
  CoinHttpClientConfig,
  CoinRemoteParticipantConfig,
  LocalCoinConfig,
  RemoteCoinConfig,
}
import com.daml.network.svc.config.SvcAppClientConfig
import com.digitalasset.canton.config.*
import com.digitalasset.canton.time.NonNegativeFiniteDuration as NonNegativeFiniteDurationT

case class LocalSvAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    remoteSvc: SvcAppClientConfig,
    automation: AutomationConfig = AutomationConfig(),
    domains: SvDomainConfig,
    // TODO(#2241): consider grouping below options into some form of `SvBootstrapConfig`
    foundConsortium: Boolean = false,
    initialTickDuration: NonNegativeFiniteDurationT = NonNegativeFiniteDurationT.ofSeconds(150),
    // TODO(#2168): test edge cases.
    initialMaxNumInputs: Int = 100,
    // TODO(M3-07): use price from SvcRules
    // TODO(M3-46): use this also for mining rounds automation, not just init
    coinPrice: BigDecimal = 1.0,
) extends LocalCoinConfig {
  override val nodeTypeName: String = "SV"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class RemoteSvAppConfig(
    adminApi: CoinHttpClientConfig
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
