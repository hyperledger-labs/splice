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
    key: String,
)

sealed trait SvBootstrapConfig {
  // TODO(#3188): make sure this ends up in the `SvcRules`
  val name: String // the human-readable name we want others to use for us
}
object SvBootstrapConfig {
  case class FoundConsortium(
      name: String,
      initialTickDuration: NonNegativeFiniteDurationT = NonNegativeFiniteDurationT.ofSeconds(150),
      // TODO(#2168): test edge cases.
      initialMaxNumInputs: Int = 100,
      initialCoinPrice: BigDecimal = 1.0,
  ) extends SvBootstrapConfig

  // TODO(#2241): mock; remove once not needed anymore
  case class JoinViaSvcApp(name: String = "not yet used") extends SvBootstrapConfig

  case class JoinWithKey(
      name: String,
      remoteSv: RemoteSvAppConfig, // an SV that we'll contact to start our onboarding
      publicKey: String, // the key that identifies us together with our name
      privateKey: String, // the private key we use for authenticating ourselves
  ) extends SvBootstrapConfig

  // TODO(#3232) Consider adding `JoinWithToken` based on an already signed token instead of the raw keys

  def hideConfidential(config: SvBootstrapConfig): SvBootstrapConfig = {
    val hidden = "****"
    config match {
      case JoinWithKey(name, remoteSv, publicKey, _) =>
        JoinWithKey(name, remoteSv, publicKey, hidden)
      case other => other
    }
  }
}

case class LocalSvAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    remoteSvc: SvcAppClientConfig,
    automation: AutomationConfig = AutomationConfig(),
    domains: SvDomainConfig,
    isDevNet: Boolean = false,
    // TODO(#2241): consider renaming this to `expectedValidatorOnboardings` once naming has stabilized
    expectedOnboardings: List[ExpectedOnboardingConfig] = Nil,
    approvedSvIdentities: List[ApprovedSvIdentityConfig] = Nil,
    bootstrap: SvBootstrapConfig = SvBootstrapConfig.JoinViaSvcApp(),
) extends LocalCNNodeConfig {
  override val nodeTypeName: String = "SV"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class RemoteSvAppConfig(
    adminApi: CoinHttpClientConfig
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
