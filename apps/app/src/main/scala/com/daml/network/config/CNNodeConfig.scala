package com.daml.network.config

import akka.http.scaladsl.model.Uri
import cats.data.Validated
import cats.syntax.either.*
import cats.syntax.functor.*
import com.daml.network.auth.AuthConfig
import com.daml.network.directory.config.{DirectoryAppBackendConfig, DirectoryAppClientConfig}
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import com.daml.network.splitwell.config.{
  SplitwellAppBackendConfig,
  SplitwellAppClientConfig,
  SplitwellDomainConfig,
  SplitwellDomains,
}
import com.daml.network.sv.config.*
import com.daml.network.svc.config.{SvcAppBackendConfig, SvcAppClientConfig}
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

import java.io.File
import scala.annotation.nowarn
import scala.util.Try
import scala.util.control.NoStackTrace

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import com.digitalasset.canton.DiscardOps

case class CNNodeConfig(
    override val name: Option[String] = None,
    validatorApps: Map[InstanceName, ValidatorAppBackendConfig] = Map.empty,
    validatorAppClients: Map[InstanceName, ValidatorAppClientConfig] = Map.empty,
    svcApp: Option[SvcAppBackendConfig] = None,
    svcAppClient: Option[SvcAppClientConfig] = None,
    svApps: Map[InstanceName, SvAppBackendConfig] = Map.empty,
    svAppClients: Map[InstanceName, SvAppClientConfig] = Map.empty,
    scanApps: Map[InstanceName, ScanAppBackendConfig] = Map.empty,
    scanAppClients: Map[InstanceName, ScanAppClientConfig] = Map.empty,
    walletAppClients: Map[InstanceName, WalletAppClientConfig] = Map.empty,
    directoryApp: Option[DirectoryAppBackendConfig] = None,
    directoryAppClients: Map[InstanceName, DirectoryAppClientConfig] = Map.empty,
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
    parameters: CantonParameters = CantonParameters(),
    features: CantonFeatures = CantonFeatures(),
    override val akkaConfig: Option[Config] = None,
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
        dbMigrateAndStart = false,
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

  // The config contains one optional unnamed SVC app (because in M1, there can only be one)
  // Since the rest of the code generally expects a map of nodes, we'll create one.
  private lazy val svcAppInstanceName = InstanceName.tryCreate("svc-app")
  lazy val svcApps = svcApp.toList.map(config => svcAppInstanceName -> config).toMap
  private lazy val svcAppClientInstanceName = InstanceName.tryCreate("svc-app-client")
  lazy val svcAppClients =
    svcAppClient.toList.map(config => svcAppClientInstanceName -> config).toMap

  private lazy val svcAppParameters_ : Map[InstanceName, SharedCNNodeAppParameters] =
    svcApps.fmap { svcConfig =>
      SharedCNNodeAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        svcConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        svcConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
        dbMigrateAndStart = false,
      )
    }

  private[network] def svcAppParameters(
      appName: InstanceName
  ): SharedCNNodeAppParameters =
    nodeParametersFor(svcAppParameters_, "svc-app", appName)

  /** Use `svcAppParameters` instead!
    */
  def trySvcAppParametersByString(name: String): SharedCNNodeAppParameters =
    svcAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `svcs` instead!
    */
  def svcsByString: Map[String, SvcAppBackendConfig] = svcApps.map { case (n, c) =>
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
        dbMigrateAndStart = false,
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
        dbMigrateAndStart = false,
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

  // The config contains one optional unnamed directory app (because in M3, there can only be one)
  // Since the rest of the code generally expects a map of nodes, we'll create one.
  private lazy val directoryAppInstanceName = InstanceName.tryCreate("directory-app")
  private lazy val directoryApps =
    directoryApp.toList.map(config => directoryAppInstanceName -> config).toMap

  private lazy val directoryAppParameters_ : Map[InstanceName, SharedCNNodeAppParameters] =
    directoryApps.fmap { directoryConfig =>
      SharedCNNodeAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        directoryConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        directoryConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
        dbMigrateAndStart = false,
      )
    }

  private[network] def directoryAppParameters(
      appName: InstanceName
  ): SharedCNNodeAppParameters =
    nodeParametersFor(directoryAppParameters_, "directory-app", appName)

  /** Use `directoryAppParameters` instead!
    */
  def tryDirectoryAppParametersByString(name: String): SharedCNNodeAppParameters =
    directoryAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `directories` instead!
    */
  def directoriesByString: Map[String, DirectoryAppBackendConfig] =
    directoryApps.map { case (n, c) =>
      n.unwrap -> c
    }

  def directoryClientsByString: Map[String, DirectoryAppClientConfig] =
    directoryAppClients.map { case (n, c) =>
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
        dbMigrateAndStart = false,
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
}

// NOTE: the below is patterned after CantonCommunityConfig.
// In case of changes, recopy from there.
@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
object CNNodeConfig {

  lazy val empty = CNNodeConfig()

  private val logger: Logger = LoggerFactory.getLogger(classOf[CNNodeConfig])
  private val elc = ErrorLoggingContext(
    TracedLogger(logger),
    NamedLoggerFactory.root.properties,
    TraceContext.empty,
  )

  import CantonConfig.*
  import pureconfig.generic.semiauto.*

  @nowarn("cat=unused")
  private implicit def cnNodeConfigReader(implicit
      elc: ErrorLoggingContext
  ): ConfigReader[CNNodeConfig] = {
    val configReaders: ConfigReaders = new ConfigReaders()
    import configReaders.*
    import DeprecatedConfigUtils.*
    import CantonDeprecationImplicits.*

    implicit val nonNegativeBigDecimalReader: ConfigReader[NonNegativeNumeric[BigDecimal]] =
      NonNegativeNumeric.nonNegativeNumericReader[BigDecimal]

    implicit val authConfigHint = new FieldCoproductHint[AuthConfig]("algorithm")

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
    implicit val scanClientConfigReader: ConfigReader[ScanAppClientConfig] =
      deriveReader[ScanAppClientConfig]
    implicit val domainConfigReader: ConfigReader[DomainConfig] =
      deriveReader[DomainConfig]
    implicit val globalOnlyDomainConfigReader: ConfigReader[GlobalOnlyDomainConfig] =
      deriveReader[GlobalOnlyDomainConfig]
    implicit val scanConfigReader: ConfigReader[ScanAppBackendConfig] =
      deriveReader[ScanAppBackendConfig]
    implicit val svcConfigReader: ConfigReader[SvcAppBackendConfig] =
      deriveReader[SvcAppBackendConfig]
    implicit val svcClientConfigReader: ConfigReader[SvcAppClientConfig] =
      deriveReader[SvcAppClientConfig]

    implicit val svClientConfigReader: ConfigReader[SvAppClientConfig] =
      deriveReader[SvAppClientConfig]

    implicit val svOnboardingConfigHint = new FieldCoproductHint[SvOnboardingConfig]("type")
    implicit val svOnboardingFoundCollectiveReader
        : ConfigReader[SvOnboardingConfig.FoundCollective] =
      deriveReader[SvOnboardingConfig.FoundCollective]
    implicit val svOnboardingJoinWithKeyReader: ConfigReader[SvOnboardingConfig.JoinWithKey] =
      deriveReader[SvOnboardingConfig.JoinWithKey]
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
    implicit val svMediatorConfig: ConfigReader[SvMediatorConfig] =
      deriveReader[SvMediatorConfig]
    implicit val svXNodesDomainConfig: ConfigReader[SvXNodesDomainConfig] =
      deriveReader[SvXNodesDomainConfig]
    implicit val svXNodesConfig: ConfigReader[SvXNodesConfig] =
      deriveReader[SvXNodesConfig]
    implicit val svConfigReader: ConfigReader[SvAppBackendConfig] =
      deriveReader[SvAppBackendConfig]

    implicit val cnNodeAppParametersReader: ConfigReader[SharedCNNodeAppParameters] =
      deriveReader[SharedCNNodeAppParameters]
    implicit val validatorOnboardingConfigReader: ConfigReader[ValidatorOnboardingConfig] =
      deriveReader[ValidatorOnboardingConfig]
    implicit val treasuryConfigReader: ConfigReader[TreasuryConfig] =
      deriveReader[TreasuryConfig]
    implicit val buyExtraTrafficConfigReader: ConfigReader[BuyExtraTrafficConfig] =
      deriveReader[BuyExtraTrafficConfig]
    implicit val validatorGlobalDomainConfigReader: ConfigReader[ValidatorGlobalDomainConfig] =
      deriveReader[ValidatorGlobalDomainConfig]
    implicit val validatorDomainConfigReader: ConfigReader[ValidatorDomainConfig] =
      deriveReader[ValidatorDomainConfig]
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
    implicit val directoryConfigReader: ConfigReader[DirectoryAppBackendConfig] =
      deriveReader[DirectoryAppBackendConfig]
    implicit val directoryClientConfigReader: ConfigReader[DirectoryAppClientConfig] =
      deriveReader[DirectoryAppClientConfig]
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

    deriveReader[CNNodeConfig]
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

    implicit val authConfigHint = new FieldCoproductHint[AuthConfig]("algorithm")

    implicit val hs256UnsafeConfig: ConfigWriter[AuthConfig.Hs256Unsafe] =
      deriveWriter[AuthConfig.Hs256Unsafe]
    implicit val rs256Config: ConfigWriter[AuthConfig.Rs256] =
      deriveWriter[AuthConfig.Rs256]
    implicit val authConfig: ConfigWriter[AuthConfig] =
      confidentialWriter[AuthConfig](AuthConfig.hideConfidential)

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
    implicit val scanClientConfigWriter: ConfigWriter[ScanAppClientConfig] =
      deriveWriter[ScanAppClientConfig]
    implicit val scanConfigWriter: ConfigWriter[ScanAppBackendConfig] =
      deriveWriter[ScanAppBackendConfig]
    implicit val svcConfigWriter: ConfigWriter[SvcAppBackendConfig] =
      deriveWriter[SvcAppBackendConfig]
    implicit val svcClientConfigWriter: ConfigWriter[SvcAppClientConfig] =
      deriveWriter[SvcAppClientConfig]

    implicit val svClientConfigWriter: ConfigWriter[SvAppClientConfig] =
      deriveWriter[SvAppClientConfig]

    implicit val svOnboardingConfigHint: FieldCoproductHint[SvOnboardingConfig] =
      new FieldCoproductHint[SvOnboardingConfig]("type")
    implicit val svOnboardingFoundCollectiveWriter
        : ConfigWriter[SvOnboardingConfig.FoundCollective] =
      deriveWriter[SvOnboardingConfig.FoundCollective]
    implicit val svOnboardingJoinWithKeyWriter: ConfigWriter[SvOnboardingConfig.JoinWithKey] =
      deriveWriter[SvOnboardingConfig.JoinWithKey]
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
    implicit val svMediatorConfig: ConfigWriter[SvMediatorConfig] =
      deriveWriter[SvMediatorConfig]
    implicit val svXNodesDomainConfig: ConfigWriter[SvXNodesDomainConfig] =
      deriveWriter[SvXNodesDomainConfig]
    implicit val svXNodesConfig: ConfigWriter[SvXNodesConfig] =
      deriveWriter[SvXNodesConfig]
    implicit val svConfigWriter: ConfigWriter[SvAppBackendConfig] =
      deriveWriter[SvAppBackendConfig]

    implicit val cnNodeAppParametersWriter: ConfigWriter[SharedCNNodeAppParameters] =
      deriveWriter[SharedCNNodeAppParameters]
    implicit val domainConfigWriter: ConfigWriter[DomainConfig] =
      deriveWriter[DomainConfig]
    implicit val globalOnlyDomainConfigWriter: ConfigWriter[GlobalOnlyDomainConfig] =
      deriveWriter[GlobalOnlyDomainConfig]

    implicit val validatorOnboardingConfigWriter: ConfigWriter[ValidatorOnboardingConfig] =
      confidentialWriter[ValidatorOnboardingConfig](ValidatorOnboardingConfig.hideConfidential)
    implicit val buyExtraTrafficWriter: ConfigWriter[BuyExtraTrafficConfig] =
      deriveWriter[BuyExtraTrafficConfig]
    implicit val validatorGlobalDomainConfigWriter: ConfigWriter[ValidatorGlobalDomainConfig] =
      deriveWriter[ValidatorGlobalDomainConfig]
    implicit val validatorDomainConfigWriter: ConfigWriter[ValidatorDomainConfig] =
      deriveWriter[ValidatorDomainConfig]
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
    implicit val directoryConfigWriter: ConfigWriter[DirectoryAppBackendConfig] =
      deriveWriter[DirectoryAppBackendConfig]
    implicit val directoryClientConfigWriter: ConfigWriter[DirectoryAppClientConfig] =
      deriveWriter[DirectoryAppClientConfig]
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

  lazy val defaultConfigRenderer =
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
