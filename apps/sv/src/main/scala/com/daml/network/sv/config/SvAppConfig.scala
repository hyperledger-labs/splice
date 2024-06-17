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
  ParticipantBootstrapDumpConfig,
}
import com.daml.network.sv.SvAppClientConfig
import com.daml.network.util.CNNodeUtil
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.{
  NonNegativeLong,
  NonNegativeNumeric,
  PositiveNumeric,
}
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.topology.PartyId
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
    rewardWeightBps: Long,
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
      founderSvRewardWeightBps: Long,
      dsoPartyHint: String = "DSO",
      initialTickDuration: NonNegativeFiniteDuration = CNNodeUtil.defaultInitialTickDuration,
      // We use the tickDuration as the default bootstrapping duration to ensure our tests focus on the steady state.
      roundZeroDuration: Option[NonNegativeFiniteDuration] = None,
      initialMaxNumInputs: Int = 100,
      initialAmuletPrice: BigDecimal = 0.005,
      initialHoldingFee: BigDecimal = CNNodeUtil.defaultHoldingFee.rate,
      initialAnsConfig: InitialAnsConfig = InitialAnsConfig(),
      initialSynchronizerFeesConfig: SynchronizerFeesConfig = SynchronizerFeesConfig(),
      isDevNet: Boolean = false,
      bootstrappingDump: Option[SvBootstrapDumpConfig] = None,
  ) extends SvOnboardingConfig

  case class JoinWithKey(
      name: String,
      svClient: SvAppClientConfig, // an SV that we'll contact to start our onboarding
      publicKey: String, // the key that identifies us together with our name
      privateKey: String, // the private key we use for authenticating ourselves
  ) extends SvOnboardingConfig

  object JoinWithKey

  // TODO(#3232) Consider adding `JoinWithToken` based on an already signed token instead of the raw keys

  case class DomainMigration(
      name: String,
      dumpFilePath: Path,
  ) extends SvOnboardingConfig

  def hideConfidential(config: SvOnboardingConfig): SvOnboardingConfig = {
    val hidden = "****"
    config match {
      case JoinWithKey(name, svClient, publicKey, _) =>
        JoinWithKey(name, svClient, publicKey, hidden)
      case other => other
    }
  }
}

final case class InitialAnsConfig(
    renewalDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(30),
    entryLifetime: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(90),
    entryFee: Double = 1.0,
)

final case class SynchronizerFeesConfig(
    extraTrafficPrice: NonNegativeNumeric[BigDecimal] =
      NonNegativeNumeric.tryCreate(BigDecimal(16.67)),
    minTopupAmount: NonNegativeLong = NonNegativeLong.tryCreate(200_000L),
    baseRateBurstAmount: NonNegativeLong = NonNegativeLong.tryCreate(400_000L),
    baseRateBurstWindow: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(20),
    // charge 4 per 10,000, i.e., 0.04% of write cost for every read.
    // See https://digitalasset.atlassian.net/wiki/spaces/CN/pages/3305570314/Lab+Log+-+Read+Scaling+Factor+Tuning
    readVsWriteScalingFactor: PositiveNumeric[Int] = PositiveNumeric.tryCreate(4),
)

final case class SvDecentralizedSynchronizerConfig(
    alias: DomainAlias,
    url: String,

    /** amount of extra traffic reserved for high priority transactions
      *
      * Note that this value MUST be smaller or euqal to the value provided in SV's validator config; and ideally it SHOULD be equal to it.
      * Also an SV's validator must always be configured to do top-ups
      */
    reservedTraffic: NonNegativeNumeric[Long] = NonNegativeNumeric.tryCreate(200_000L),

    /** The SV's ledger client compares its remaining traffic balance against the reserved amount
      * on every command submission. This setting controls how long the traffic balance is cached before
      * being rehydrated by querying its participant.
      */
    trafficBalanceCacheTimeToLive: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(1),
)

final case class SvSynchronizerConfig(
    global: SvDecentralizedSynchronizerConfig
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
    domains: SvSynchronizerConfig,
    expectedValidatorOnboardings: List[ExpectedValidatorOnboardingConfig] = Nil,
    approvedSvIdentities: List[ApprovedSvIdentityConfig] = Nil,
    // If not set the onboarding name is used. We set this in our tests
    // because this one can be suffixed per test while we keep the onboarding name stable.
    svPartyHint: Option[String] = None,
    onboarding: Option[SvOnboardingConfig] = None,
    initialAmuletPriceVote: Option[BigDecimal] = None,
    cometBftConfig: Option[CometBftConfig] = None,
    localSynchronizerNode: Option[SvSynchronizerNodeConfig],
    scan: Option[SvScanConfig],
    participantBootstrappingDump: Option[ParticipantBootstrapDumpConfig] = None,
    identitiesDump: Option[BackupDumpConfig] = None,
    domainMigrationDumpPath: Option[Path] = None,
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    prevetDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(6),
    onLedgerStatusReportInterval: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofMinutes(1),
    parameters: CNNodeParametersConfig = CNNodeParametersConfig(batching = BatchingConfig()),
    ingestFromParticipantBegin: Boolean = true,
    ingestUpdateHistoryFromParticipantBegin: Boolean = true,
    extraBeneficiaries: Map[PartyId, BigDecimal] = Map.empty,
    enableOnboardingParticipantPromotionDelay: Boolean = true,
    onboardingPollingInterval: Option[NonNegativeFiniteDuration],
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(10),
) extends CNNodeBackendConfig {
  override val nodeTypeName: String = "SV"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

  def rewardWeightBpsOf(name: String): Option[Long] = approvedSvIdentities
    .collectFirst { case ApprovedSvIdentityConfig(`name`, _, weightBps) =>
      weightBps
    }
    .orElse {
      onboarding match {
        case Some(founder: SvOnboardingConfig.FoundCollective) if founder.name == name =>
          Some(founder.founderSvRewardWeightBps)
        case _ => None
      }
    }
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
)

final case class SvSequencerConfig(
    adminApi: ClientConfig,
    internalApi: ClientConfig,
    externalPublicApiUrl: String,
    // This needs to be participantResponseTimeout + mediatorResponseTimeout to make sure that the sequencer
    // does not have to serve requests that have been in flight before the sequencer's signing keys became valid.
    // See also https://github.com/DACH-NY/canton-network-node/issues/5938#issuecomment-1677165109
    // The default value of 60 seconds is based on Canton defaulting to 30s for each of those.
    // TODO (#8282): consider reading config value from participant instead of configuring here
    sequencerAvailabilityDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(60),
    pruning: Option[SequencerPruningConfig] = None,
)

final case class SvMediatorConfig(
    adminApi: ClientConfig
)

final case class SvScanConfig(
    publicUrl: Uri,
    internalUrl: Uri,
)

final case class SvSynchronizerNodeConfig(
    sequencer: SvSequencerConfig,
    mediator: SvMediatorConfig,
    parameters: DomainParametersConfig = DomainParametersConfig(
      protocolVersion = DomainProtocolVersion(ProtocolVersion.v30)
    ),
)
