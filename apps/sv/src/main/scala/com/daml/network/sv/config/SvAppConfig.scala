package com.daml.network.sv.config

import com.daml.network.config.{
  AutomationConfig,
  CoinHttpClientConfig,
  CoinRemoteParticipantConfig,
  LocalCNNodeConfig,
  RemoteCNNodeConfig,
}
import com.daml.network.svc.config.SvcAppClientConfig
import com.digitalasset.canton.config.*
import com.digitalasset.canton.time.NonNegativeFiniteDuration as NonNegativeFiniteDurationT

case class ExpectedOnboardingConfig(
    secret: String,
    expiresIn: NonNegativeFiniteDurationT = NonNegativeFiniteDurationT.ofHours(1),
)
object ExpectedOnboardingConfig {
  def hideConfidential(config: ExpectedOnboardingConfig): ExpectedOnboardingConfig = {
    val hidden = "****"
    config.copy(secret = hidden)
  }
}

case class ApprovedSvIdentityConfig(
    name: String,
    // TODO(#3106) Once signature algorithm decided: add early check that this holds a valid key.
    key: String,
)

case class LocalSvAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    remoteSvc: SvcAppClientConfig,
    automation: AutomationConfig = AutomationConfig(),
    domains: SvDomainConfig,
    // TODO(#2241): consider grouping below options into some form of `SvBootstrapConfig`
    isDevNet: Boolean = false,
    foundConsortium: Boolean = false,
    // TODO(#2241): consider renaming this to `expectedValidatorOnboardings` once naming has stabilized
    expectedOnboardings: List[ExpectedOnboardingConfig] = Nil,
    approvedSvIdentities: List[ApprovedSvIdentityConfig] = Nil,
    initialTickDuration: NonNegativeFiniteDurationT = NonNegativeFiniteDurationT.ofSeconds(150),
    // TODO(#2168): test edge cases.
    initialMaxNumInputs: Int = 100,
    // TODO(M3-07): use price from SvcRules
    // TODO(M3-46): use this also for mining rounds automation, not just init
    coinPrice: BigDecimal = 1.0,
) extends LocalCNNodeConfig {
  override val nodeTypeName: String = "SV"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class RemoteSvAppConfig(
    adminApi: CoinHttpClientConfig
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
