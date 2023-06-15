package com.daml.network.sv.config

import com.daml.network.auth.AuthConfig
import com.daml.network.config.{
  AutomationConfig,
  CNNodeBackendConfig,
  CNParticipantClientConfig,
  HttpCNNodeClientConfig,
  NetworkAppClientConfig,
}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}

case class ExpectedValidatorOnboardingConfig(
    secret: String,
    expiresIn: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(1),
)
object ExpectedValidatorOnboardingConfig {
  def hideConfidential(
      config: ExpectedValidatorOnboardingConfig
  ): ExpectedValidatorOnboardingConfig = {
    val hidden = "****"
    config.copy(secret = hidden)
  }
}

case class ApprovedSvIdentityConfig(
    name: String,
    publicKey: String,
)

sealed trait SvOnboardingConfig {
  val name: String // the human-readable name we want others to use for us
}
object SvOnboardingConfig {
  case class FoundCollective(
      name: String,
      svcPartyHint: String = "svc",
      initialTickDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(150),
      initialMaxNumInputs: Int = 100,
      initialCoinPrice: BigDecimal = 1.0,
  ) extends SvOnboardingConfig

  case class JoinWithKey(
      name: String,
      svClient: SvAppClientConfig, // an SV that we'll contact to start our onboarding
      publicKey: String, // the key that identifies us together with our name
      privateKey: String, // the private key we use for authenticating ourselves
  ) extends SvOnboardingConfig

  // TODO(#3232) Consider adding `JoinWithToken` based on an already signed token instead of the raw keys

  def hideConfidential(config: SvOnboardingConfig): SvOnboardingConfig = {
    val hidden = "****"
    config match {
      case JoinWithKey(name, svClient, publicKey, _) =>
        JoinWithKey(name, svClient, publicKey, hidden)
      case other => other
    }
  }
}

final case class SvGlobalDomainConfig(
    alias: DomainAlias,
    url: String,
)

final case class SvDomainConfig(
    global: SvGlobalDomainConfig
)

case class SvAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    // The SV app shares the primary party with the validator app. To discover it we query the
    // validator user. Additionally, the founding SV app is expected to create that user,
    // so it needs to know the expected user name.
    validatorLedgerApiUser: String,
    auth: AuthConfig,
    participantClient: CNParticipantClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: SvDomainConfig,
    isDevNet: Boolean = false,
    expectedValidatorOnboardings: List[ExpectedValidatorOnboardingConfig] = Nil,
    approvedSvIdentities: List[ApprovedSvIdentityConfig] = Nil,
    // If not set the onboarding name is used. We set this in our tests
    // because this one can be suffixed per test while we keep the onboarding name stable.
    svPartyHint: Option[String] = None,
    onboarding: Option[SvOnboardingConfig] = None,
    initialCoinPriceVote: Option[BigDecimal] = None,
    enableCoinRulesUpgrade: Boolean = false,
    cometBftConfig: Option[CometBftConfig] = None,
    xNodes: Option[SvXNodesConfig] = None,
) extends CNNodeBackendConfig {
  override val nodeTypeName: String = "SV"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class SvAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
case class CometBftConfig(
    enabled: Boolean = false,
    // TODO(#5740): Remove the option to disable automation once we're ready to pull that trigger
    automationEnabled: Boolean = false,
    connectionUri: String = "",
)

final case class SvSequencerConfig(
    adminApi: ClientConfig,
    publicApi: ClientConfig,
)

final case class SvMediatorConfig(
    adminApi: ClientConfig
)

final case class SvXNodesConfig(
    // Optional to support multiple SVs without a distributed domain.
    // TODO(#5195) Consider making this mandatory once we only support
    // decentralized domains in production setups.
    domain: Option[SvXNodesDomainConfig]
)

final case class SvXNodesDomainConfig(
    sequencer: SvSequencerConfig,
    mediator: SvMediatorConfig,
    parameters: DomainParametersConfig = DomainParametersConfig(
      protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
      devVersionSupport = true,
      uniqueContractKeys = false,
    ),
)
