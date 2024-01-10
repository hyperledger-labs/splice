package com.daml.network.sv.config

import com.daml.network.auth.AuthConfig
import com.daml.network.config.{
  AutomationConfig,
  BackupDumpConfig,
  CNDbConfig,
  CNNodeBackendConfig,
  CNNodeParametersConfig,
  CNParticipantClientConfig,
  GcpBucketConfig,
  HttpCNNodeClientConfig,
  NetworkAppClientConfig,
  ParticipantBootstrapDumpConfig,
}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.{
  NonNegativeLong,
  NonNegativeNumeric,
  PositiveNumeric,
}
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
import org.apache.pekko.http.scaladsl.model.Uri

import java.nio.file.Path

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

sealed abstract class SvBootstrapDumpConfig {
  def description: String
}

object SvBootstrapDumpConfig {
  final case class File(file: Path) extends SvBootstrapDumpConfig {
    override val description = s"Local file $file"
  }
  final case class Gcp(
      bucket: GcpBucketConfig,
      path: Path,
  ) extends SvBootstrapDumpConfig {
    override val description = s"Path $path in ${bucket.description}"
  }
}

object SvOnboardingConfig {
  case class FoundCollective(
      name: String,
      founderSvRewardWeight: Long = 10,
      svcPartyHint: String = "SVC",
      initialTickDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(150),
      initialMaxNumInputs: Int = 100,
      initialCoinPrice: BigDecimal = 1.0,
      initialCnsConfig: InitialCnsConfig = InitialCnsConfig(),
      initialTrafficControlConfig: TrafficControlConfig = TrafficControlConfig(),
      isDevNet: Boolean = false,
      bootstrappingDump: Option[SvBootstrapDumpConfig] = None,
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

final case class InitialCnsConfig(
    renewalDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(30),
    entryLifetime: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(90),
    entryFee: Double = 1.0,
)

final case class TrafficControlConfig(
    baseRateBurstAmount: NonNegativeLong =
      NonNegativeLong.tryCreate(100 * 20 * 1000L), // 100 txs of 20KB each (over the burst window)
    baseRateBurstWindow: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
    readVsWriteScalingFactor: PositiveNumeric[Int] =
      PositiveNumeric.tryCreate(200), // charge 200 per 10,000, i.e., 2% of write cost for every read
)

final case class SvGlobalDomainConfig(
    alias: DomainAlias,
    url: String,

    /** amount of extra traffic reserved for transactions required to do traffic topups
      *
      * Note that this value MUST be smaller or euqal to the value provided in SV's validator config; and ideally it SHOULD be equal to it.
      * Also an SV's validator must always be configured to do top-ups
      */
    trafficReservedForTopups: NonNegativeNumeric[Long] = NonNegativeNumeric.tryCreate(100_000L),

    /** The SV's ledger client compares its remaining traffic balance against the reserved amount
      * on every command submission. This setting controls how long the traffic balance is cached before
      * being rehydrated by querying its participant.
      */
    trafficBalanceCacheTimeToLive: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(1),
)

final case class SvDomainConfig(
    global: SvGlobalDomainConfig
)

case class SvAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CNDbConfig,
    ledgerApiUser: String,
    // The SV app shares the primary party with the validator app. To discover it we query the
    // validator user. Additionally, the founding SV app is expected to create that user,
    // so it needs to know the expected user name.
    validatorLedgerApiUser: String,
    auth: AuthConfig,
    participantClient: CNParticipantClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: SvDomainConfig,
    expectedValidatorOnboardings: List[ExpectedValidatorOnboardingConfig] = Nil,
    approvedSvIdentities: List[ApprovedSvIdentityConfig] = Nil,
    // If not set the onboarding name is used. We set this in our tests
    // because this one can be suffixed per test while we keep the onboarding name stable.
    svPartyHint: Option[String] = None,
    onboarding: Option[SvOnboardingConfig] = None,
    initialCoinPriceVote: Option[BigDecimal] = None,
    cometBftConfig: Option[CometBftConfig] = None,
    localDomainNode: Option[SvDomainNodeConfig],
    scan: Option[SvScanConfig],
    participantBootstrappingDump: Option[ParticipantBootstrapDumpConfig] = None,
    acsStoreDump: Option[BackupDumpConfig] = None,
    prevetDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(6),
    parameters: CNNodeParametersConfig = CNNodeParametersConfig(batching = BatchingConfig()),
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
    connectionUri: String = "",
)

// Removes unnecessary data from the Sequencer that is earlier than the configured retention period
final case class SequencerPruningConfig(
    // this defines how frequent the prune command is being called to the sequencer
    pruningInterval: NonNegativeFiniteDuration,
    // data within the retention period preceding the current time will not be removed during the pruning process
    retentionPeriod: NonNegativeFiniteDuration,
    // retention period for unauthenticated Members before they are disabled
    // the default value of 1 hour is copied from canton `RetentionPeriodDefaults`
    unauthenticatedMembersRetentionPeriod: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofHours(1),
)

final case class SvSequencerConfig(
    adminApi: ClientConfig,
    internalApi: ClientConfig,
    externalPublicApiUrl: String,
    // The default value of 60 seconds is based on https://github.com/DACH-NY/canton-network-node/issues/5938#issuecomment-1677165109
    // TODO (#8282): consider reading config value from participant instead of configuring here
    sequencerAvailabilityDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(60),
    pruning: Option[SequencerPruningConfig] = None,
)

final case class SvMediatorConfig(
    adminApi: ClientConfig
)

final case class SvScanConfig(
    publicUrl: Uri
)

final case class SvDomainNodeConfig(
    sequencer: SvSequencerConfig,
    mediator: SvMediatorConfig,
    parameters: DomainParametersConfig = DomainParametersConfig(
      protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
      devVersionSupport = true,
      uniqueContractKeys = false,
    ),
)
