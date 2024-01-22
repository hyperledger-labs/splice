package com.daml.network.config

import org.apache.pekko.http.scaladsl.model.Uri
import cats.data.Validated
import cats.syntax.either.*
import cats.syntax.functor.*
import com.daml.network.auth.AuthConfig
import com.daml.network.http.v0.definitions.{
  AppConfiguration,
  Domain,
  ReleaseConfiguration,
  Timespan,
}
import com.daml.network.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import com.daml.network.splitwell.config.{
  SplitwellAppBackendConfig,
  SplitwellAppClientConfig,
  SplitwellDomainConfig,
  SplitwellDomains,
}
import com.daml.network.sv.config.*
import com.daml.network.validator.config.*
import com.daml.network.wallet.config.{
  TreasuryConfig,
  WalletAppClientConfig,
  WalletDomainConfig,
  WalletValidatorAppClientConfig,
}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.CantonCommunityConfig.CantonDeprecationImplicits
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.domain.config.{CommunityDomainConfig, RemoteDomainConfig}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.participant.config.{
  CommunityParticipantConfig,
  RemoteParticipantConfig,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import com.typesafe.config.{Config, ConfigRenderOptions}
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.generic.FieldCoproductHint
import pureconfig.{ConfigReader, ConfigWriter}
import pureconfig.error.FailureReason
import pureconfig.module.cats.{nonEmptyListReader, nonEmptyListWriter}

import scala.concurrent.duration.*
import java.io.File
import scala.annotation.nowarn
import scala.util.Try
import scala.util.control.NoStackTrace
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.domain.mediator.{CommunityMediatorNodeXConfig, RemoteMediatorConfig}
import com.digitalasset.canton.domain.sequencing.config.{
  CommunitySequencerNodeXConfig,
  RemoteSequencerConfig,
}

case class CNNodeConfig(
    override val name: Option[String] = None,
    validatorApps: Map[InstanceName, ValidatorAppBackendConfig] = Map.empty,
    validatorAppClients: Map[InstanceName, ValidatorAppClientConfig] = Map.empty,
    svApps: Map[InstanceName, SvAppBackendConfig] = Map.empty,
    svAppClients: Map[InstanceName, SvAppClientConfig] = Map.empty,
    scanApps: Map[InstanceName, ScanAppBackendConfig] = Map.empty,
    scanAppClients: Map[InstanceName, ScanAppClientConfig] = Map.empty,
    walletAppClients: Map[InstanceName, WalletAppClientConfig] = Map.empty,
    appManagerAppClients: Map[InstanceName, AppManagerAppClientConfig] = Map.empty,
    cnsAppExternalClients: Map[InstanceName, CnsAppExternalClientConfig] = Map.empty,
    splitwellApps: Map[InstanceName, SplitwellAppBackendConfig] = Map.empty,
    splitwellAppClients: Map[InstanceName, SplitwellAppClientConfig] = Map.empty,
    // TODO(#736): we want to remove all of the configurations options below:
    domains: Map[InstanceName, CommunityDomainConfig] = Map.empty,
    participants: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteDomains: Map[InstanceName, RemoteDomainConfig] = Map.empty,
    remoteParticipants: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    participantsX: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteParticipantsX: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    monitoring: MonitoringConfig = MonitoringConfig(),
    parameters: CantonParameters = CantonParameters(
      timeouts = TimeoutSettings(
        console = ConsoleCommandTimeout(
          bounded = NonNegativeDuration.tryFromDuration(2.minutes),
          requestTimeout = NonNegativeDuration.tryFromDuration(40.seconds),
        )
      )
    ),
    features: CantonFeatures = CantonFeatures(),
    override val pekkoConfig: Option[Config] = None,
) extends CantonConfig // TODO(#736): generalize or fork this trait.
    with ConfigDefaults[DefaultPorts, CNNodeConfig] {

  override type DomainConfigType = CommunityDomainConfig
  override type ParticipantConfigType = CommunityParticipantConfig

  override def validate: Validated[NonEmpty[Seq[String]], Unit] = Validated.valid(())

  private lazy val validatorAppParameters_ : Map[InstanceName, SharedCNNodeAppParameters] =
    validatorApps.fmap { validatorConfig =>
      SharedCNNodeAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        validatorConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        validatorConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
        dbMigrateAndStart = true,
        skipTopologyManagerSignatureValidation = false,
        batchingConfig = validatorConfig.parameters.batching,
      )
    }

  private[network] def validatorAppParameters(
      participant: InstanceName
  ): SharedCNNodeAppParameters =
    nodeParametersFor(validatorAppParameters_, "participant", participant)

  /** Use `validatorAppParameters`` instead!
    */
  def tryValidatorAppParametersByString(name: String): SharedCNNodeAppParameters =
    validatorAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `validators` instead!
    */
  def validatorsByString: Map[String, ValidatorAppBackendConfig] = validatorApps.map {
    case (n, c) =>
      n.unwrap -> c
  }

  private lazy val svAppParameters_ : Map[InstanceName, SharedCNNodeAppParameters] =
    svApps.fmap { svConfig =>
      SharedCNNodeAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        svConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        svConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
        dbMigrateAndStart = true,
        skipTopologyManagerSignatureValidation = false,
        batchingConfig = new BatchingConfig(),
      )
    }

  private[network] def svAppParameters(
      appName: InstanceName
  ): SharedCNNodeAppParameters =
    nodeParametersFor(svAppParameters_, "sv-app-backend", appName)

  /** Use `svAppParameters` instead!
    */
  def trySvAppParametersByString(name: String): SharedCNNodeAppParameters =
    svAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `svs` instead!
    */
  def svsByString: Map[String, SvAppBackendConfig] = svApps.map { case (n, c) =>
    n.unwrap -> c
  }

  private lazy val scanAppParameters_ : Map[InstanceName, SharedCNNodeAppParameters] =
    scanApps.fmap { scanConfig =>
      SharedCNNodeAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        scanConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        scanConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
        dbMigrateAndStart = true,
        skipTopologyManagerSignatureValidation = false,
        batchingConfig = new BatchingConfig(),
      )
    }

  private[network] def scanAppParameters(
      appName: InstanceName
  ): SharedCNNodeAppParameters =
    nodeParametersFor(scanAppParameters_, "scan-app", appName)

  /** Use `scanAppParameters` instead!
    */
  def tryScanAppParametersByString(name: String): SharedCNNodeAppParameters =
    scanAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `scans` instead!
    */
  def scansByString: Map[String, ScanAppBackendConfig] = scanApps.map { case (n, c) =>
    n.unwrap -> c
  }

  private lazy val splitwellAppParameters_ : Map[InstanceName, SharedCNNodeAppParameters] =
    splitwellApps.fmap { splitwellConfig =>
      SharedCNNodeAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        splitwellConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        splitwellConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
        dbMigrateAndStart = true,
        skipTopologyManagerSignatureValidation = false,
        batchingConfig = new BatchingConfig(),
      )
    }

  private[network] def splitwellAppParameters(
      appName: InstanceName
  ): SharedCNNodeAppParameters =
    nodeParametersFor(splitwellAppParameters_, "splitwell-app", appName)

  /** Use `splitwellAppParameters` instead!
    */
  def trySplitwellAppParametersByString(name: String): SharedCNNodeAppParameters =
    splitwellAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `splitwells` instead!
    */
  def splitwellsByString: Map[String, SplitwellAppBackendConfig] =
    splitwellApps.map { case (n, c) =>
      n.unwrap -> c
    }

  def splitwellClientsByString: Map[String, SplitwellAppClientConfig] =
    splitwellAppClients.map { case (n, c) =>
      n.unwrap -> c
    }

  override def dumpString: String = {
    val writers = new CNNodeConfig.ConfigWriters(confidential = true)
    import writers.*
    ConfigWriter[CNNodeConfig].to(this).render(CNNodeConfig.defaultConfigRenderer)
  }

  override def withDefaults(ports: DefaultPorts): CNNodeConfig =
    this // TODO(#736): CantonCommunityConfig does more here. Do we want to copy that?
  // NOTE(Simon): in particular it handles default ports derived from the ports object introduced in https://github.com/DACH-NY/canton/commit/ccff59fccf349893cc68413a7859e8ef748a94fa

  // TODO(#736): we want to remove these mediator configs

  override type MediatorNodeXConfigType = CommunityMediatorNodeXConfig

  override def mediatorsX: Map[InstanceName, MediatorNodeXConfigType] = Map.empty

  override def remoteMediatorsX: Map[InstanceName, RemoteMediatorConfig] = Map.empty

  override type SequencerNodeXConfigType = CommunitySequencerNodeXConfig

  override def sequencersX: Map[InstanceName, SequencerNodeXConfigType] = Map.empty

  override def remoteSequencersX: Map[InstanceName, RemoteSequencerConfig] = Map.empty
}

// NOTE: the below is patterned after CantonCommunityConfig.
// In case of changes, recopy from there.
@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
object CNNodeConfig {

  final case class ConfigValidationFailed(reason: String) extends FailureReason {
    override def description: String = s"Config validation failed: $reason"
  }

  lazy val empty: CNNodeConfig = CNNodeConfig()

  private val logger: Logger = LoggerFactory.getLogger(classOf[CNNodeConfig])
  private[config] val elc = ErrorLoggingContext(
    TracedLogger(logger),
    NamedLoggerFactory.root.properties,
    TraceContext.empty,
  )

  import CantonConfig.*
  import pureconfig.generic.semiauto.*

  class ConfigReaders(implicit
      private val
      elc: ErrorLoggingContext
  ) {
    val configReaders: CantonConfig.ConfigReaders = new CantonConfig.ConfigReaders()
    import configReaders.*
    import DeprecatedConfigUtils.*
    import CantonDeprecationImplicits.*

    implicit val nonNegativeBigDecimalReader: ConfigReader[NonNegativeNumeric[BigDecimal]] =
      NonNegativeNumeric.nonNegativeNumericReader[BigDecimal]

    implicit val authConfigHint: FieldCoproductHint[AuthConfig] =
      new FieldCoproductHint[AuthConfig]("algorithm")

    implicit val hs256UnsafeConfig: ConfigReader[AuthConfig.Hs256Unsafe] =
      deriveReader[AuthConfig.Hs256Unsafe]
    implicit val rs256Config: ConfigReader[AuthConfig.Rs256] =
      deriveReader[AuthConfig.Rs256]
    implicit val authConfig: ConfigReader[AuthConfig] =
      deriveReader[AuthConfig]

    implicit val authTokenSourceConfigHint: FieldCoproductHint[AuthTokenSourceConfig] =
      new FieldCoproductHint[AuthTokenSourceConfig]("type")
    implicit val authTokenSourceNoneReader: ConfigReader[AuthTokenSourceConfig.None] =
      deriveReader[AuthTokenSourceConfig.None]
    implicit val authTokenSourceStaticReader: ConfigReader[AuthTokenSourceConfig.Static] =
      deriveReader[AuthTokenSourceConfig.Static]
    implicit val authTokenSourceSelfSignedReader: ConfigReader[AuthTokenSourceConfig.SelfSigned] =
      deriveReader[AuthTokenSourceConfig.SelfSigned]
    implicit val authTokenSourceCCReader: ConfigReader[AuthTokenSourceConfig.ClientCredentials] =
      deriveReader[AuthTokenSourceConfig.ClientCredentials]
    implicit val authTokenSourceConfigReader: ConfigReader[AuthTokenSourceConfig] =
      deriveReader[AuthTokenSourceConfig]

    implicit val uriReader: ConfigReader[Uri] =
      ConfigReader.fromNonEmptyStringTry(s => Try(Uri.parseAbsolute(s)))
    implicit val networkAppClientConfigReader: ConfigReader[NetworkAppClientConfig] =
      deriveReader[NetworkAppClientConfig]

    implicit val cnNodeParametersConfig: ConfigReader[CNNodeParametersConfig] =
      deriveReader[CNNodeParametersConfig]

    implicit val postgresCNDbConfigReader: ConfigReader[CNDbConfig.Postgres] =
      deriveReader[CNDbConfig.Postgres]
    implicit val memoryCNDbConfigReader: ConfigReader[CNDbConfig.Memory] =
      deriveReader[CNDbConfig.Memory]
    implicit val cnDbConfigReader: ConfigReader[CNDbConfig] = deriveReader[CNDbConfig]

    implicit val automationConfig: ConfigReader[AutomationConfig] =
      deriveReader[AutomationConfig]
    implicit val cnNodeLedgerApiClientConfigReader: ConfigReader[CNLedgerApiClientConfig] =
      deriveReader[CNLedgerApiClientConfig]
    implicit val cnNodeParticipantClientConfigReader: ConfigReader[CNParticipantClientConfig] =
      deriveReader[CNParticipantClientConfig]
    implicit val appInstanceReader: ConfigReader[AppInstance] =
      deriveReader[AppInstance]
    implicit val scanClientConfigConfigHint: FieldCoproductHint[BftScanClientConfig] =
      new FieldCoproductHint[BftScanClientConfig]("type")
    implicit val scanClientConfigTrustSingleConfigReader
        : ConfigReader[BftScanClientConfig.TrustSingle] =
      deriveReader[BftScanClientConfig.TrustSingle]
    implicit val scanClientConfigSeedsConfigReader: ConfigReader[BftScanClientConfig.Bft] =
      deriveReader[BftScanClientConfig.Bft]
    implicit val scanClientConfigConfigReader: ConfigReader[BftScanClientConfig] =
      deriveReader[BftScanClientConfig]
    implicit val scanClientConfigReader: ConfigReader[ScanAppClientConfig] =
      deriveReader[ScanAppClientConfig]
    implicit val domainConfigReader: ConfigReader[DomainConfig] =
      deriveReader[DomainConfig]
    implicit val scanConfigReader: ConfigReader[ScanAppBackendConfig] =
      deriveReader[ScanAppBackendConfig]

    implicit val svClientConfigReader: ConfigReader[SvAppClientConfig] =
      deriveReader[SvAppClientConfig]

    implicit val gcpCredentialsConfigHint: FieldCoproductHint[GcpCredentialsConfig] =
      new FieldCoproductHint[GcpCredentialsConfig]("type")
    implicit val userCredentialsConfigReader: ConfigReader[GcpCredentialsConfig.User] =
      deriveReader[GcpCredentialsConfig.User]
    implicit val serviceAccountCredentialsConfigReader
        : ConfigReader[GcpCredentialsConfig.ServiceAccount] =
      deriveReader[GcpCredentialsConfig.ServiceAccount]
    implicit val gcpCredentialsConfigReader: ConfigReader[GcpCredentialsConfig] =
      deriveReader[GcpCredentialsConfig]
    implicit val gcpBucketConfig: ConfigReader[GcpBucketConfig] = deriveReader[GcpBucketConfig]
    implicit val participantBootstrapDumpConfigHint
        : FieldCoproductHint[ParticipantBootstrapDumpConfig] =
      new FieldCoproductHint[ParticipantBootstrapDumpConfig]("type")
    implicit val participantBootstrapDumpConfigFileReader
        : ConfigReader[ParticipantBootstrapDumpConfig.File] =
      deriveReader[ParticipantBootstrapDumpConfig.File]
    implicit val participantBootstrapDumpConfigReader
        : ConfigReader[ParticipantBootstrapDumpConfig] =
      deriveReader[ParticipantBootstrapDumpConfig]
    implicit val svBootstrapDumpConfigHint: FieldCoproductHint[SvBootstrapDumpConfig] =
      new FieldCoproductHint[SvBootstrapDumpConfig]("type")
    implicit val svBootstrapDumpConfigFileReader: ConfigReader[SvBootstrapDumpConfig.File] =
      deriveReader[SvBootstrapDumpConfig.File]
    implicit val svBootstrapDumpConfigGcpReader: ConfigReader[SvBootstrapDumpConfig.Gcp] =
      deriveReader[SvBootstrapDumpConfig.Gcp]
    implicit val svBootstrapDumpConfigReader: ConfigReader[SvBootstrapDumpConfig] =
      deriveReader[SvBootstrapDumpConfig]
    implicit val svOnboardingConfigHint: FieldCoproductHint[SvOnboardingConfig] =
      new FieldCoproductHint[SvOnboardingConfig]("type")
    implicit val initialCnsConfigReader: ConfigReader[InitialCnsConfig] =
      deriveReader[InitialCnsConfig]
    implicit val trafficControlConfigReader: ConfigReader[TrafficControlConfig] =
      deriveReader[TrafficControlConfig]
    implicit val svOnboardingFoundCollectiveReader
        : ConfigReader[SvOnboardingConfig.FoundCollective] =
      deriveReader[SvOnboardingConfig.FoundCollective]
    implicit val svOnboardingJoinWithKeyReader: ConfigReader[SvOnboardingConfig.JoinWithKey] =
      deriveReader[SvOnboardingConfig.JoinWithKey]
    implicit val svOnboardingDomainMigrationReader
        : ConfigReader[SvOnboardingConfig.DomainMigration] =
      deriveReader[SvOnboardingConfig.DomainMigration]
    implicit val svOnboardingConfigReader: ConfigReader[SvOnboardingConfig] =
      deriveReader[SvOnboardingConfig]
    implicit val expectedValidatorOnboardingConfigReader
        : ConfigReader[ExpectedValidatorOnboardingConfig] =
      deriveReader[ExpectedValidatorOnboardingConfig]
    implicit val approvedSvIdentityConfigReader: ConfigReader[ApprovedSvIdentityConfig] =
      deriveReader[ApprovedSvIdentityConfig]
    implicit val cometBftConfigReader: ConfigReader[CometBftConfig] = deriveReader
    implicit val svSequencerConfig: ConfigReader[SvSequencerConfig] =
      deriveReader[SvSequencerConfig]
    implicit val sequencerPruningConfig: ConfigReader[SequencerPruningConfig] =
      deriveReader[SequencerPruningConfig]
    implicit val svMediatorConfig: ConfigReader[SvMediatorConfig] =
      deriveReader[SvMediatorConfig]
    implicit val svScanConfig: ConfigReader[SvScanConfig] =
      deriveReader[SvScanConfig]
    implicit val svDomainNodeConfig: ConfigReader[SvDomainNodeConfig] =
      deriveReader[SvDomainNodeConfig]
    implicit val svGlobalDomainConfigReader: ConfigReader[SvGlobalDomainConfig] =
      deriveReader[SvGlobalDomainConfig]
    implicit val svDomainConfigReader: ConfigReader[SvDomainConfig] =
      deriveReader[SvDomainConfig]
    implicit val backupDumpConfigHint: FieldCoproductHint[BackupDumpConfig] =
      new FieldCoproductHint[BackupDumpConfig]("type")
    implicit val backupDumpConfigDirectoryReader: ConfigReader[BackupDumpConfig.Directory] =
      deriveReader[BackupDumpConfig.Directory]
    implicit val backupDumpConfigGcpReader: ConfigReader[BackupDumpConfig.Gcp] =
      deriveReader[BackupDumpConfig.Gcp]
    implicit val backupDumpConfigReader: ConfigReader[BackupDumpConfig] =
      deriveReader[BackupDumpConfig]
    implicit val migrateSvPartyConfigReader: ConfigReader[MigrateSvPartyConfig] =
      deriveReader[MigrateSvPartyConfig]
    implicit val svConfigReader: ConfigReader[SvAppBackendConfig] =
      deriveReader[SvAppBackendConfig].emap { conf =>
        // We support joining nodes without sequencers/mediators but
        // the founding node must alway configure one to bootstrap the domain.
        val foundingNodeHasDomainConfig = conf.onboarding.fold(true) {
          _ match {
            case _: SvOnboardingConfig.FoundCollective => conf.localDomainNode.isDefined
            case _: SvOnboardingConfig.JoinWithKey => true
            case _: SvOnboardingConfig.DomainMigration => true
          }
        }
        Either.cond(
          foundingNodeHasDomainConfig,
          conf,
          ConfigValidationFailed("Founding node must always specify a domain config"),
        )
      }

    implicit val cnNodeAppParametersReader: ConfigReader[SharedCNNodeAppParameters] =
      deriveReader[SharedCNNodeAppParameters]
    implicit val validatorOnboardingConfigReader: ConfigReader[ValidatorOnboardingConfig] =
      deriveReader[ValidatorOnboardingConfig]
    implicit val treasuryConfigReader: ConfigReader[TreasuryConfig] =
      deriveReader[TreasuryConfig]
    implicit val buyExtraTrafficConfigReader: ConfigReader[BuyExtraTrafficConfig] =
      deriveReader[BuyExtraTrafficConfig]
    implicit val validatorGlobalDomainConfigReader: ConfigReader[ValidatorGlobalDomainConfig] =
      deriveReader[ValidatorGlobalDomainConfig].emap(config => {
        val trafficPurchasedOnEachTopup =
          config.buyExtraTraffic.targetThroughput.value * config.buyExtraTraffic.minTopupInterval.duration.toSeconds
        val trafficReservedForTopups = config.trafficReservedForTopupsO.fold(0L)(_.value)
        Either.cond(
          // config is valid if either the validator is not configured to do top-ups
          // or the reserved traffic is less than the traffic purchased per top-up
          trafficPurchasedOnEachTopup == 0 || trafficReservedForTopups < trafficPurchasedOnEachTopup,
          config,
          ConfigValidationFailed(
            s"The target-throughput times the min-topup-interval in the buy-extra-traffic config (currently: $trafficPurchasedOnEachTopup) " +
              s"must be greater than the traffic-reserved-for-topups (currently: $trafficReservedForTopups)"
          ),
        )
      })
    implicit val validatorExtraDomainConfigReader: ConfigReader[ValidatorExtraDomainConfig] =
      deriveReader[ValidatorExtraDomainConfig]
    implicit val validatorDomainConfigReader: ConfigReader[ValidatorDomainConfig] =
      deriveReader[ValidatorDomainConfig]
    implicit val offsetDateTimeConfigurationReader: ConfigReader[java.time.OffsetDateTime] =
      implicitly[ConfigReader[String]].map(java.time.OffsetDateTime.parse)
    implicit val timespanConfigurationReader: ConfigReader[Timespan] = deriveReader[Timespan]
    implicit val domainConfigurationReader: ConfigReader[Domain] = deriveReader[Domain]
    implicit val releaseConfigurationReader: ConfigReader[ReleaseConfiguration] =
      deriveReader[ReleaseConfiguration]
    implicit val appConfigurationReader: ConfigReader[AppConfiguration] =
      deriveReader[AppConfiguration]
    implicit val initialRegisteredAppReader: ConfigReader[InitialRegisteredApp] =
      deriveReader[InitialRegisteredApp]
    implicit val initialInstalledAppReader: ConfigReader[InitialInstalledApp] =
      deriveReader[InitialInstalledApp]
    implicit val appManagerConfigReader: ConfigReader[AppManagerConfig] =
      deriveReader[AppManagerConfig]
    implicit val validatorConfigReader: ConfigReader[ValidatorAppBackendConfig] =
      deriveReader[ValidatorAppBackendConfig]
    implicit val validatorClientConfigReader: ConfigReader[ValidatorAppClientConfig] =
      deriveReader[ValidatorAppClientConfig]
    implicit val walletvalidatorClientConfigReader: ConfigReader[WalletValidatorAppClientConfig] =
      deriveReader[WalletValidatorAppClientConfig]
    implicit val walletDomainConfigReader: ConfigReader[WalletDomainConfig] =
      deriveReader[WalletDomainConfig]
    implicit val WalletAppClientConfigReader: ConfigReader[WalletAppClientConfig] =
      deriveReader[WalletAppClientConfig]
    implicit val AppManagerAppClientConfigReader: ConfigReader[AppManagerAppClientConfig] =
      deriveReader[AppManagerAppClientConfig]
    implicit val cnsExternalClientConfigReader: ConfigReader[CnsAppExternalClientConfig] =
      deriveReader[CnsAppExternalClientConfig]
    implicit val splitwellDomainsReader: ConfigReader[SplitwellDomains] =
      deriveReader[SplitwellDomains]
    implicit val splitwellDomainConfigReader: ConfigReader[SplitwellDomainConfig] =
      deriveReader[SplitwellDomainConfig]
    implicit val splitwellConfigReader: ConfigReader[SplitwellAppBackendConfig] =
      deriveReader[SplitwellAppBackendConfig]
    implicit val splitwellClientConfigReader: ConfigReader[SplitwellAppClientConfig] =
      deriveReader[SplitwellAppClientConfig]

    implicit val communityDomainConfigReader: ConfigReader[CommunityDomainConfig] =
      deriveReader[CommunityDomainConfig].applyDeprecations
    implicit val communityParticipantConfigReader: ConfigReader[CommunityParticipantConfig] =
      deriveReader[CommunityParticipantConfig].applyDeprecations

    implicit val cnNodeConfigReader: ConfigReader[CNNodeConfig] = deriveReader[CNNodeConfig]
  }

  @nowarn("cat=unused")
  class ConfigWriters(confidential: Boolean) {
    val writers = new CantonConfig.ConfigWriters(confidential)

    import writers.*
    import DeprecatedConfigUtils.*
    import CantonDeprecationImplicits.*

    implicit val nonNegativeBigDecimalWriter: ConfigWriter[NonNegativeNumeric[BigDecimal]] =
      ConfigWriter.toString(x => x.unwrap.toString)

    // Use a `confidentialWriter` if a config can contain confidential values!
    // Also consider revisiting if the "leaked secrets check" in
    // `.circleci/canton-scripts/check-logs.sh` catches the new type of secret.

    implicit val authConfigHint: FieldCoproductHint[AuthConfig] =
      new FieldCoproductHint[AuthConfig]("algorithm")

    implicit val hs256UnsafeConfig: ConfigWriter[AuthConfig.Hs256Unsafe] =
      deriveWriter[AuthConfig.Hs256Unsafe]
    implicit val rs256Config: ConfigWriter[AuthConfig.Rs256] =
      deriveWriter[AuthConfig.Rs256]
    implicit val authConfig: ConfigWriter[AuthConfig] =
      confidentialWriter[AuthConfig](AuthConfig.hideConfidential)

    implicit val cnNodeParametersConfig: ConfigWriter[CNNodeParametersConfig] =
      deriveWriter[CNNodeParametersConfig]

    implicit val authTokenSourceConfigHint: FieldCoproductHint[AuthTokenSourceConfig] =
      new FieldCoproductHint[AuthTokenSourceConfig]("type")
    implicit val authTokenSourceNoneWriter: ConfigWriter[AuthTokenSourceConfig.None] =
      deriveWriter[AuthTokenSourceConfig.None]
    implicit val authTokenSourceStaticWriter: ConfigWriter[AuthTokenSourceConfig.Static] =
      deriveWriter[AuthTokenSourceConfig.Static]
    implicit val authTokenSourceSelfSignedWriter: ConfigWriter[AuthTokenSourceConfig.SelfSigned] =
      deriveWriter[AuthTokenSourceConfig.SelfSigned]
    implicit val authTokenSourceCCWriter: ConfigWriter[AuthTokenSourceConfig.ClientCredentials] =
      deriveWriter[AuthTokenSourceConfig.ClientCredentials]
    implicit val authTokenSourceConfigWriter: ConfigWriter[AuthTokenSourceConfig] =
      confidentialWriter[AuthTokenSourceConfig](AuthTokenSourceConfig.hideConfidential)

    implicit val uriConfigWriter: ConfigWriter[Uri] =
      ConfigWriter.stringConfigWriter.contramap(_.toString())
    implicit val networkAppClientConfigReader: ConfigWriter[NetworkAppClientConfig] =
      deriveWriter[NetworkAppClientConfig]

    implicit val postgresCNDbConfigWriter: ConfigWriter[CNDbConfig.Postgres] =
      confidentialWriter[CNDbConfig.Postgres](pg =>
        pg.copy(config = DbConfig.hideConfidential(pg.config))
      )
    implicit val memoryCNDbConfigWriter: ConfigWriter[CNDbConfig.Memory] =
      deriveWriter[CNDbConfig.Memory]
    implicit val cnDbConfigWriter: ConfigWriter[CNDbConfig] = deriveWriter[CNDbConfig]

    implicit val automationConfig: ConfigWriter[AutomationConfig] =
      deriveWriter[AutomationConfig]
    implicit val cnNodeLedgerApiClientConfigWriter: ConfigWriter[CNLedgerApiClientConfig] =
      deriveWriter[CNLedgerApiClientConfig]
    implicit val cnNodeParticipantClientConfigWriter: ConfigWriter[CNParticipantClientConfig] =
      deriveWriter[CNParticipantClientConfig]
    implicit val appInstanceWriter: ConfigWriter[AppInstance] =
      deriveWriter[AppInstance]
    implicit val scanClientConfigConfigHint: FieldCoproductHint[BftScanClientConfig] =
      new FieldCoproductHint[BftScanClientConfig]("type")
    implicit val scanClientConfigTrustSingleConfigWriter
        : ConfigWriter[BftScanClientConfig.TrustSingle] =
      deriveWriter[BftScanClientConfig.TrustSingle]
    implicit val scanClientConfigSeedsConfigWriter: ConfigWriter[BftScanClientConfig.Bft] =
      deriveWriter[BftScanClientConfig.Bft]
    implicit val scanClientConfigConfigWriter: ConfigWriter[BftScanClientConfig] =
      deriveWriter[BftScanClientConfig]
    implicit val scanClientConfigWriter: ConfigWriter[ScanAppClientConfig] =
      deriveWriter[ScanAppClientConfig]
    implicit val scanConfigWriter: ConfigWriter[ScanAppBackendConfig] =
      deriveWriter[ScanAppBackendConfig]

    implicit val svClientConfigWriter: ConfigWriter[SvAppClientConfig] =
      deriveWriter[SvAppClientConfig]

    implicit val gcpCredentialsConfigHint: FieldCoproductHint[GcpCredentialsConfig] =
      new FieldCoproductHint[GcpCredentialsConfig]("type")
    implicit val userCredentialsConfigWriter: ConfigWriter[GcpCredentialsConfig.User] =
      deriveWriter[GcpCredentialsConfig.User]
    implicit val serviceAccountCredentialsConfigWriter
        : ConfigWriter[GcpCredentialsConfig.ServiceAccount] =
      deriveWriter[GcpCredentialsConfig.ServiceAccount]
    implicit val gcpCredentialsConfigWriter: ConfigWriter[GcpCredentialsConfig] =
      confidentialWriter[GcpCredentialsConfig](GcpCredentialsConfig.hideConfidential)
    implicit val gcpBucketConfig: ConfigWriter[GcpBucketConfig] = deriveWriter[GcpBucketConfig]
    implicit val participantBootstrapDumpConfigHint
        : FieldCoproductHint[ParticipantBootstrapDumpConfig] =
      new FieldCoproductHint[ParticipantBootstrapDumpConfig]("type")
    implicit val participantBootstrapDumpConfigFileWriter
        : ConfigWriter[ParticipantBootstrapDumpConfig.File] =
      deriveWriter[ParticipantBootstrapDumpConfig.File]
    implicit val participantBootstrapDumpConfigWriter
        : ConfigWriter[ParticipantBootstrapDumpConfig] =
      deriveWriter[ParticipantBootstrapDumpConfig]
    implicit val svBootstrapDumpConfigHint: FieldCoproductHint[SvBootstrapDumpConfig] =
      new FieldCoproductHint[SvBootstrapDumpConfig]("type")
    implicit val svBootstrapDumpConfigFileWriter: ConfigWriter[SvBootstrapDumpConfig.File] =
      deriveWriter[SvBootstrapDumpConfig.File]
    implicit val svBootstrapDumpConfigGcpWriter: ConfigWriter[SvBootstrapDumpConfig.Gcp] =
      deriveWriter[SvBootstrapDumpConfig.Gcp]
    implicit val svBootstrapDumpConfigWriter: ConfigWriter[SvBootstrapDumpConfig] =
      deriveWriter[SvBootstrapDumpConfig]
    implicit val svOnboardingConfigHint: FieldCoproductHint[SvOnboardingConfig] =
      new FieldCoproductHint[SvOnboardingConfig]("type")
    implicit val initialCnsConfigWriter: ConfigWriter[InitialCnsConfig] =
      deriveWriter[InitialCnsConfig]
    implicit val trafficControlConfigWriter: ConfigWriter[TrafficControlConfig] =
      deriveWriter[TrafficControlConfig]
    implicit val svOnboardingFoundCollectiveWriter
        : ConfigWriter[SvOnboardingConfig.FoundCollective] =
      deriveWriter[SvOnboardingConfig.FoundCollective]
    implicit val svOnboardingJoinWithKeyWriter: ConfigWriter[SvOnboardingConfig.JoinWithKey] =
      deriveWriter[SvOnboardingConfig.JoinWithKey]
    implicit val svOnboardingDomainMigrationWriter
        : ConfigWriter[SvOnboardingConfig.DomainMigration] =
      deriveWriter[SvOnboardingConfig.DomainMigration]
    implicit val svOnboardingConfigWriter: ConfigWriter[SvOnboardingConfig] =
      confidentialWriter[SvOnboardingConfig](SvOnboardingConfig.hideConfidential)

    implicit val expectedValidatorOnboardingConfigWriter
        : ConfigWriter[ExpectedValidatorOnboardingConfig] =
      confidentialWriter[ExpectedValidatorOnboardingConfig](
        ExpectedValidatorOnboardingConfig.hideConfidential
      )
    implicit val approvedSvIdentityConfigWriter: ConfigWriter[ApprovedSvIdentityConfig] =
      deriveWriter[ApprovedSvIdentityConfig]
    implicit val cometBftConfigWriter: ConfigWriter[CometBftConfig] = deriveWriter
    implicit val svSequencerConfig: ConfigWriter[SvSequencerConfig] =
      deriveWriter[SvSequencerConfig]
    implicit val sequencerPruningConfig: ConfigWriter[SequencerPruningConfig] =
      deriveWriter[SequencerPruningConfig]
    implicit val svMediatorConfig: ConfigWriter[SvMediatorConfig] =
      deriveWriter[SvMediatorConfig]
    implicit val svScanConfig: ConfigWriter[SvScanConfig] =
      deriveWriter[SvScanConfig]
    implicit val svDomainNodeConfig: ConfigWriter[SvDomainNodeConfig] =
      deriveWriter[SvDomainNodeConfig]
    implicit val svGlobalDomainConfigWriter: ConfigWriter[SvGlobalDomainConfig] =
      deriveWriter[SvGlobalDomainConfig]
    implicit val svDomainConfigWriter: ConfigWriter[SvDomainConfig] =
      deriveWriter[SvDomainConfig]
    implicit val backupDumpConfigHint: FieldCoproductHint[BackupDumpConfig] =
      new FieldCoproductHint[BackupDumpConfig]("type")
    implicit val backupDumpConfigDirectoryWriter: ConfigWriter[BackupDumpConfig.Directory] =
      deriveWriter[BackupDumpConfig.Directory]
    implicit val backupDumpConfigGcpWriter: ConfigWriter[BackupDumpConfig.Gcp] =
      deriveWriter[BackupDumpConfig.Gcp]
    implicit val backupDumpConfigWriter: ConfigWriter[BackupDumpConfig] =
      deriveWriter[BackupDumpConfig]
    implicit val migrateSvPartyConfigWriter: ConfigWriter[MigrateSvPartyConfig] =
      deriveWriter[MigrateSvPartyConfig]
    implicit val svConfigWriter: ConfigWriter[SvAppBackendConfig] =
      deriveWriter[SvAppBackendConfig]

    implicit val cnNodeAppParametersWriter: ConfigWriter[SharedCNNodeAppParameters] =
      deriveWriter[SharedCNNodeAppParameters]
    implicit val domainConfigWriter: ConfigWriter[DomainConfig] =
      deriveWriter[DomainConfig]

    implicit val validatorOnboardingConfigWriter: ConfigWriter[ValidatorOnboardingConfig] =
      confidentialWriter[ValidatorOnboardingConfig](ValidatorOnboardingConfig.hideConfidential)
    implicit val buyExtraTrafficWriter: ConfigWriter[BuyExtraTrafficConfig] =
      deriveWriter[BuyExtraTrafficConfig]
    implicit val validatorGlobalDomainConfigWriter: ConfigWriter[ValidatorGlobalDomainConfig] =
      deriveWriter[ValidatorGlobalDomainConfig]
    implicit val validatorExtraDomainConfigWriter: ConfigWriter[ValidatorExtraDomainConfig] =
      deriveWriter[ValidatorExtraDomainConfig]
    implicit val validatorDomainConfigWriter: ConfigWriter[ValidatorDomainConfig] =
      deriveWriter[ValidatorDomainConfig]
    implicit val offsetDateTimeConfigurationWriter: ConfigWriter[java.time.OffsetDateTime] =
      implicitly[ConfigWriter[String]].contramap(_.toString)
    implicit val timespanConfigurationWriter: ConfigWriter[Timespan] = deriveWriter[Timespan]
    implicit val domainConfigurationWriter: ConfigWriter[Domain] = deriveWriter[Domain]
    implicit val releaseConfigurationWriter: ConfigWriter[ReleaseConfiguration] =
      deriveWriter[ReleaseConfiguration]
    implicit val appConfigurationWriter: ConfigWriter[AppConfiguration] =
      deriveWriter[AppConfiguration]
    implicit val initialRegisteredAppWriter: ConfigWriter[InitialRegisteredApp] =
      deriveWriter[InitialRegisteredApp]
    implicit val initialInstalledAppWriter: ConfigWriter[InitialInstalledApp] =
      deriveWriter[InitialInstalledApp]
    implicit val appManagerConfigWriter: ConfigWriter[AppManagerConfig] =
      deriveWriter[AppManagerConfig]
    implicit val validatorConfigWriter: ConfigWriter[ValidatorAppBackendConfig] =
      deriveWriter[ValidatorAppBackendConfig]
    implicit val validatorClientConfigWriter: ConfigWriter[ValidatorAppClientConfig] =
      deriveWriter[ValidatorAppClientConfig]
    implicit val walletvalidatorClientConfigWriter: ConfigWriter[WalletValidatorAppClientConfig] =
      deriveWriter[WalletValidatorAppClientConfig]
    implicit val treasuryConfigWriter: ConfigWriter[TreasuryConfig] =
      deriveWriter[TreasuryConfig]
    implicit val walletDomainConfigWriter: ConfigWriter[WalletDomainConfig] =
      deriveWriter[WalletDomainConfig]
    implicit val WalletAppClientConfigWriter: ConfigWriter[WalletAppClientConfig] =
      deriveWriter[WalletAppClientConfig]
    implicit val AppManagerAppClientConfigWriter: ConfigWriter[AppManagerAppClientConfig] =
      deriveWriter[AppManagerAppClientConfig]
    implicit val cnsExternalClientConfigWriter: ConfigWriter[CnsAppExternalClientConfig] =
      deriveWriter[CnsAppExternalClientConfig]
    implicit val splitwellDomains: ConfigWriter[SplitwellDomains] =
      deriveWriter[SplitwellDomains]
    implicit val splitwellDomainConfigWriter: ConfigWriter[SplitwellDomainConfig] =
      deriveWriter[SplitwellDomainConfig]
    implicit val splitwellConfigWriter: ConfigWriter[SplitwellAppBackendConfig] =
      deriveWriter[SplitwellAppBackendConfig]
    implicit val splitwellClientConfigWriter: ConfigWriter[SplitwellAppClientConfig] =
      deriveWriter[SplitwellAppClientConfig]

    implicit val communityDomainConfigWriter: ConfigWriter[CommunityDomainConfig] =
      deriveWriter[CommunityDomainConfig]
    implicit val communityParticipantConfigWriter: ConfigWriter[CommunityParticipantConfig] =
      deriveWriter[CommunityParticipantConfig]

    implicit val cnNodeConfigWriter: ConfigWriter[CNNodeConfig] =
      deriveWriter[CNNodeConfig]

  }

  private implicit def configReader(implicit
      elc: ErrorLoggingContext
  ): ConfigReader[CNNodeConfig] = {
    val readers = new ConfigReaders()(elc)
    readers.cnNodeConfigReader
  }

  def load(config: Config)(implicit
      elc: ErrorLoggingContext = elc
  ): Either[CantonConfigError, CNNodeConfig] =
    CantonConfig.loadAndValidate[CNNodeConfig](config)

  def parseAndLoadOrThrow(files: Seq[File])(implicit
      elc: ErrorLoggingContext = elc
  ): CNNodeConfig =
    CantonConfig
      .parseAndLoad[CNNodeConfig](files)
      .valueOr(error => throw CNNodeConfigException(error))

  def loadOrThrow(config: Config)(implicit elc: ErrorLoggingContext = elc): CNNodeConfig = {
    CantonConfig
      .loadAndValidate[CNNodeConfig](config)
      .valueOr(error => throw CNNodeConfigException(error))
  }

  lazy val defaultConfigRenderer: ConfigRenderOptions =
    ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false)

  // Used in scripts/transform-config.sc when spinning up nodes for UI development
  def writeToFile(config: CNNodeConfig, path: Path, confidential: Boolean = true): Unit = {
    val writers = new CNNodeConfig.ConfigWriters(confidential)
    import writers.*
    val renderer = ConfigRenderOptions
      .defaults()
      .setOriginComments(false)
      .setComments(false)
      .setJson(false)
    val content = "canton { " + ConfigWriter[CNNodeConfig]
      .to(config)
      .render(renderer) + "}"
    Files.write(path, content.getBytes(StandardCharsets.UTF_8)).discard
  }
}

object CNNodeConfigException {
  def apply(error: CantonConfigError): CNNodeConfigException =
    error.throwableO.fold(new CNNodeConfigException(error.cause))(t =>
      new CNNodeConfigException(error.cause, t)
    )
}

@SuppressWarnings(Array("org.wartremover.warts.Null"))
final case class CNNodeConfigException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
    with NoStackTrace
