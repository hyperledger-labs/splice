package com.daml.network.config

import cats.data.Validated
import cats.syntax.either.*
import cats.syntax.functor.*

import com.daml.network.auth.AuthConfig
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import com.daml.network.splitwise.config.{
  SplitwiseAppBackendConfig,
  SplitwiseAppClientConfig,
  SplitwiseDomainConfig,
}
import com.daml.network.sv.config.{LocalSvAppConfig, RemoteSvAppConfig}
import com.daml.network.svc.config.{SvcAppBackendConfig, SvcAppClientConfig}
import com.daml.network.validator.config.{
  AppInstance,
  ValidatorAppBackendConfig,
  ValidatorAppClientConfig,
  ValidatorDomainConfig,
  ValidatorOnboardingConfig,
}
import com.daml.network.wallet.config.{
  TreasuryConfig,
  WalletAppBackendConfig,
  WalletAppClientConfig,
  WalletDomainConfig,
  WalletRemoteValidatorAppConfig,
}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.config.CantonCommunityConfig.CantonDeprecationImplicits
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.{
  CantonConfig,
  CantonFeatures,
  CantonParameters,
  ConfigDefaults,
  DefaultPorts,
  DeprecatedConfigUtils,
  MonitoringConfig,
}
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
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.annotation.nowarn
import scala.util.control.NoStackTrace

case class CoinConfig(
    override val name: Option[String] = None,
    validatorApps: Map[InstanceName, ValidatorAppBackendConfig] = Map.empty,
    validatorAppClients: Map[InstanceName, ValidatorAppClientConfig] = Map.empty,
    svcApp: Option[SvcAppBackendConfig] = None,
    svcAppClient: Option[SvcAppClientConfig] = None,
    svApps: Map[InstanceName, LocalSvAppConfig] = Map.empty,
    svAppClients: Map[InstanceName, RemoteSvAppConfig] = Map.empty,
    scanApp: Option[ScanAppBackendConfig] = None,
    ScanAppClients: Map[InstanceName, ScanAppClientConfig] = Map.empty,
    walletAppBackends: Map[InstanceName, WalletAppBackendConfig] = Map.empty,
    walletAppClients: Map[InstanceName, WalletAppClientConfig] = Map.empty,
    directoryApp: Option[LocalDirectoryAppConfig] = None,
    remoteDirectoryApps: Map[InstanceName, RemoteDirectoryAppConfig] = Map.empty,
    splitwiseApps: Map[InstanceName, SplitwiseAppBackendConfig] = Map.empty,
    splitwiseAppClients: Map[InstanceName, SplitwiseAppClientConfig] = Map.empty,
    // TODO(#736): we want to remove all of the configurations options below:
    domains: Map[InstanceName, CommunityDomainConfig] = Map.empty,
    participants: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteDomains: Map[InstanceName, RemoteDomainConfig] = Map.empty,
    remoteParticipants: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    monitoring: MonitoringConfig = MonitoringConfig(),
    parameters: CantonParameters = CantonParameters(),
    features: CantonFeatures = CantonFeatures(),
) extends CantonConfig // TODO(#736): generalize or fork this trait.
    with ConfigDefaults[DefaultPorts, CoinConfig] {

  override type DomainConfigType = CommunityDomainConfig
  override type ParticipantConfigType = CommunityParticipantConfig
  override def validate: Validated[NonEmpty[Seq[String]], Unit] = Validated.valid(())

  private lazy val validatorAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    validatorApps.fmap { validatorConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        validatorConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        validatorConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def validatorAppParameters(
      participant: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(validatorAppParameters_, "participant", participant)

  /** Use `validatorAppParameters`` instead!
    */
  def tryValidatorAppParametersByString(name: String): SharedCoinAppParameters =
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
  private lazy val remoteSvcAppInstanceName = InstanceName.tryCreate("svc-app-client")
  lazy val remoteSvcApps =
    svcAppClient.toList.map(config => remoteSvcAppInstanceName -> config).toMap

  private lazy val svcAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    svcApps.fmap { svcConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        svcConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        svcConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def svcAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(svcAppParameters_, "svc-app", appName)

  /** Use `svcAppParameters` instead!
    */
  def trySvcAppParametersByString(name: String): SharedCoinAppParameters =
    svcAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `svcs` instead!
    */
  def svcsByString: Map[String, SvcAppBackendConfig] = svcApps.map { case (n, c) =>
    n.unwrap -> c
  }

  private lazy val svAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    svApps.fmap { svConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        svConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        svConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def svAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(svAppParameters_, "sv-app-backend", appName)

  /** Use `svAppParameters` instead!
    */
  def trySvAppParametersByString(name: String): SharedCoinAppParameters =
    svAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `svs` instead!
    */
  def svsByString: Map[String, LocalSvAppConfig] = svApps.map { case (n, c) =>
    n.unwrap -> c
  }

  // The config contains one optional unnamed Scan app (because in M1, there can only be one)
  // Since the rest of the code generally expects a map of nodes, we'll create one.
  private lazy val scanAppName = "scan-app"
  private lazy val scanApps =
    scanApp.toList.map(config => InstanceName.tryCreate(scanAppName) -> config).toMap

  private lazy val scanAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    scanApps.fmap { scanConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        scanConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        scanConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def scanAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(scanAppParameters_, scanAppName, appName)

  /** Use `scanAppParameters` instead!
    */
  def tryScanAppParametersByString(name: String): SharedCoinAppParameters =
    scanAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `scans` instead!
    */
  def scansByString: Map[String, ScanAppBackendConfig] = scanApps.map { case (n, c) =>
    n.unwrap -> c
  }

  private lazy val walletAppBackendParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    walletAppBackends.fmap { walletConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        walletConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        walletConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def walletAppBackendParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(walletAppBackendParameters_, "wallet-app-backend", appName)

  /** Use `WalletAppBackendParameters` instead!
    */
  def tryWalletAppBackendParametersByString(name: String): SharedCoinAppParameters =
    walletAppBackendParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `wallets` instead!
    */
  def walletBackendsByString: Map[String, WalletAppBackendConfig] = walletAppBackends.map {
    case (n, c) =>
      n.unwrap -> c
  }

  // The config contains one optional unnamed directory app (because in M3, there can only be one)
  // Since the rest of the code generally expects a map of nodes, we'll create one.
  private lazy val directoryAppInstanceName = InstanceName.tryCreate("directory-app")
  private lazy val directoryApps =
    directoryApp.toList.map(config => directoryAppInstanceName -> config).toMap

  private lazy val directoryAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    directoryApps.fmap { directoryConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        directoryConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        directoryConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def directoryAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(directoryAppParameters_, "directory-app", appName)

  /** Use `directoryAppParameters` instead!
    */
  def tryDirectoryAppParametersByString(name: String): SharedCoinAppParameters =
    directoryAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `directories` instead!
    */
  def directoriesByString: Map[String, LocalDirectoryAppConfig] =
    directoryApps.map { case (n, c) =>
      n.unwrap -> c
    }

  def remoteDirectoriesByString: Map[String, RemoteDirectoryAppConfig] =
    remoteDirectoryApps.map { case (n, c) =>
      n.unwrap -> c
    }

  private lazy val splitwiseAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    splitwiseApps.fmap { splitwiseConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        splitwiseConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        splitwiseConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def splitwiseAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(splitwiseAppParameters_, "splitwise-app", appName)

  /** Use `splitwiseAppParameters` instead!
    */
  def trySplitwiseAppParametersByString(name: String): SharedCoinAppParameters =
    splitwiseAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `splitwises` instead!
    */
  def splitwisesByString: Map[String, SplitwiseAppBackendConfig] =
    splitwiseApps.map { case (n, c) =>
      n.unwrap -> c
    }

  def remoteSplitwisesByString: Map[String, SplitwiseAppClientConfig] =
    splitwiseAppClients.map { case (n, c) =>
      n.unwrap -> c
    }

  override def dumpString: String = {
    val writers = new CoinConfig.ConfigWriters(confidential = true)
    import writers.*
    ConfigWriter[CoinConfig].to(this).render(CoinConfig.defaultConfigRenderer)
  }

  override def withDefaults(ports: DefaultPorts): CoinConfig =
    this // TODO(#736): CantonCommunityConfig does more here. Do we want to copy that?
  // NOTE(Simon): in particular it handles default ports derived from the ports object introduced in https://github.com/DACH-NY/canton/commit/ccff59fccf349893cc68413a7859e8ef748a94fa
}

// NOTE: the below is patterned after CantonCommunityConfig.
// In case of changes, recopy from there.
@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
object CoinConfig {

  private val logger: Logger = LoggerFactory.getLogger(classOf[CoinConfig])
  private val elc = ErrorLoggingContext(
    TracedLogger(logger),
    NamedLoggerFactory.root.properties,
    TraceContext.empty,
  )

  import pureconfig.generic.semiauto.*
  import CantonConfig.*

  @nowarn("cat=unused")
  private implicit def coinConfigReader(implicit
      elc: ErrorLoggingContext
  ): ConfigReader[CoinConfig] = {
    val configReaders: ConfigReaders = new ConfigReaders()
    import configReaders.*
    import DeprecatedConfigUtils.*
    import CantonDeprecationImplicits.*

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

    implicit val automationConfig: ConfigReader[AutomationConfig] =
      deriveReader[AutomationConfig]
    implicit val coinHttpClientConfigReader: ConfigReader[CoinHttpClientConfig] =
      deriveReader[CoinHttpClientConfig]
    implicit val coinLedgerApiClientConfigReader: ConfigReader[CoinLedgerApiClientConfig] =
      deriveReader[CoinLedgerApiClientConfig]
    implicit val coinRemoteParticipantConfigReader: ConfigReader[CoinRemoteParticipantConfig] =
      deriveReader[CoinRemoteParticipantConfig]
    implicit val appInstanceReader: ConfigReader[AppInstance] =
      deriveReader[AppInstance]
    implicit val remoteScanConfigReader: ConfigReader[ScanAppClientConfig] =
      deriveReader[ScanAppClientConfig]
    implicit val scanConfigReader: ConfigReader[ScanAppBackendConfig] =
      deriveReader[ScanAppBackendConfig]
    implicit val svcConfigReader: ConfigReader[SvcAppBackendConfig] =
      deriveReader[SvcAppBackendConfig]
    implicit val remoteSvcConfigReader: ConfigReader[SvcAppClientConfig] =
      deriveReader[SvcAppClientConfig]
    implicit val svConfigReader: ConfigReader[LocalSvAppConfig] =
      deriveReader[LocalSvAppConfig]
    implicit val remoteSvConfigReader: ConfigReader[RemoteSvAppConfig] =
      deriveReader[RemoteSvAppConfig]

    implicit val coinAppParametersReader: ConfigReader[SharedCoinAppParameters] =
      deriveReader[SharedCoinAppParameters]
    implicit val validatorDomainConfigReader: ConfigReader[ValidatorDomainConfig] =
      deriveReader[ValidatorDomainConfig]
    implicit val validatorOnboardingConfigReader: ConfigReader[ValidatorOnboardingConfig] =
      deriveReader[ValidatorOnboardingConfig]
    implicit val validatorConfigReader: ConfigReader[ValidatorAppBackendConfig] =
      deriveReader[ValidatorAppBackendConfig]
    implicit val remoteValidatorConfigReader: ConfigReader[ValidatorAppClientConfig] =
      deriveReader[ValidatorAppClientConfig]
    implicit val walletRemoteValidatorConfigReader: ConfigReader[WalletRemoteValidatorAppConfig] =
      deriveReader[WalletRemoteValidatorAppConfig]
    implicit val treasuryConfigReader: ConfigReader[TreasuryConfig] =
      deriveReader[TreasuryConfig]
    implicit val walletDomainConfigReader: ConfigReader[WalletDomainConfig] =
      deriveReader[WalletDomainConfig]
    implicit val walletBackendConfigReader: ConfigReader[WalletAppBackendConfig] =
      deriveReader[WalletAppBackendConfig]
    implicit val WalletAppClientConfigReader: ConfigReader[WalletAppClientConfig] =
      deriveReader[WalletAppClientConfig]
    implicit val directoryConfigReader: ConfigReader[LocalDirectoryAppConfig] =
      deriveReader[LocalDirectoryAppConfig]
    implicit val remoteDirectoryConfigReader: ConfigReader[RemoteDirectoryAppConfig] =
      deriveReader[RemoteDirectoryAppConfig]
    implicit val splitwiseDomainConfigReader: ConfigReader[SplitwiseDomainConfig] =
      deriveReader[SplitwiseDomainConfig]
    implicit val splitwiseConfigReader: ConfigReader[SplitwiseAppBackendConfig] =
      deriveReader[SplitwiseAppBackendConfig]
    implicit val remoteSplitwiseConfigReader: ConfigReader[SplitwiseAppClientConfig] =
      deriveReader[SplitwiseAppClientConfig]

    implicit val communityDomainConfigReader: ConfigReader[CommunityDomainConfig] =
      deriveReader[CommunityDomainConfig].applyDeprecations
    implicit val communityParticipantConfigReader: ConfigReader[CommunityParticipantConfig] =
      deriveReader[CommunityParticipantConfig].applyDeprecations

    deriveReader[CoinConfig]
  }

  @nowarn("cat=unused")
  class ConfigWriters(confidential: Boolean) {
    val writers = new CantonConfig.ConfigWriters(confidential)
    import writers.*
    import DeprecatedConfigUtils.*
    import CantonDeprecationImplicits.*

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

    implicit val automationConfig: ConfigWriter[AutomationConfig] =
      deriveWriter[AutomationConfig]
    implicit val coinHttpClientConfigWriter: ConfigWriter[CoinHttpClientConfig] =
      deriveWriter[CoinHttpClientConfig]
    implicit val coinLedgerApiClientConfigWriter: ConfigWriter[CoinLedgerApiClientConfig] =
      deriveWriter[CoinLedgerApiClientConfig]
    implicit val coinRemoteParticipantConfigWriter: ConfigWriter[CoinRemoteParticipantConfig] =
      deriveWriter[CoinRemoteParticipantConfig]
    implicit val appInstanceWriter: ConfigWriter[AppInstance] =
      deriveWriter[AppInstance]
    implicit val remoteScanConfigWriter: ConfigWriter[ScanAppClientConfig] =
      deriveWriter[ScanAppClientConfig]
    implicit val scanConfigWriter: ConfigWriter[ScanAppBackendConfig] =
      deriveWriter[ScanAppBackendConfig]
    implicit val svcConfigWriter: ConfigWriter[SvcAppBackendConfig] =
      deriveWriter[SvcAppBackendConfig]
    implicit val remoteSvcConfigWriter: ConfigWriter[SvcAppClientConfig] =
      deriveWriter[SvcAppClientConfig]
    implicit val svConfigWriter: ConfigWriter[LocalSvAppConfig] =
      deriveWriter[LocalSvAppConfig]
    implicit val remoteSvConfigWriter: ConfigWriter[RemoteSvAppConfig] =
      deriveWriter[RemoteSvAppConfig]

    implicit val coinAppParametersWriter: ConfigWriter[SharedCoinAppParameters] =
      deriveWriter[SharedCoinAppParameters]
    implicit val validatorDomainConfigWriter: ConfigWriter[ValidatorDomainConfig] =
      deriveWriter[ValidatorDomainConfig]
    implicit val validatorOnboardingConfigWriter: ConfigWriter[ValidatorOnboardingConfig] =
      confidentialWriter[ValidatorOnboardingConfig](ValidatorOnboardingConfig.hideConfidential)
    implicit val validatorConfigWriter: ConfigWriter[ValidatorAppBackendConfig] =
      deriveWriter[ValidatorAppBackendConfig]
    implicit val remoteValidatorConfigWriter: ConfigWriter[ValidatorAppClientConfig] =
      deriveWriter[ValidatorAppClientConfig]
    implicit val walletRemoteValidatorConfigWriter: ConfigWriter[WalletRemoteValidatorAppConfig] =
      deriveWriter[WalletRemoteValidatorAppConfig]
    implicit val treasuryConfigWriter: ConfigWriter[TreasuryConfig] =
      deriveWriter[TreasuryConfig]
    implicit val walletDomainConfigWriter: ConfigWriter[WalletDomainConfig] =
      deriveWriter[WalletDomainConfig]
    implicit val walletBackendConfigWriter: ConfigWriter[WalletAppBackendConfig] =
      deriveWriter[WalletAppBackendConfig]
    implicit val WalletAppClientConfigWriter: ConfigWriter[WalletAppClientConfig] =
      deriveWriter[WalletAppClientConfig]
    implicit val directoryConfigWriter: ConfigWriter[LocalDirectoryAppConfig] =
      deriveWriter[LocalDirectoryAppConfig]
    implicit val remoteDirectoryConfigWriter: ConfigWriter[RemoteDirectoryAppConfig] =
      deriveWriter[RemoteDirectoryAppConfig]
    implicit val splitwiseDomainConfigWriter: ConfigWriter[SplitwiseDomainConfig] =
      deriveWriter[SplitwiseDomainConfig]
    implicit val splitwiseConfigWriter: ConfigWriter[SplitwiseAppBackendConfig] =
      deriveWriter[SplitwiseAppBackendConfig]
    implicit val remoteSplitwiseConfigWriter: ConfigWriter[SplitwiseAppClientConfig] =
      deriveWriter[SplitwiseAppClientConfig]

    implicit val communityDomainConfigWriter: ConfigWriter[CommunityDomainConfig] =
      deriveWriter[CommunityDomainConfig]
    implicit val communityParticipantConfigWriter: ConfigWriter[CommunityParticipantConfig] =
      deriveWriter[CommunityParticipantConfig]

    implicit val coinConfigWriter: ConfigWriter[CoinConfig] = deriveWriter[CoinConfig]
  }

  def load(config: Config)(implicit
      elc: ErrorLoggingContext = elc
  ): Either[CantonConfigError, CoinConfig] =
    CantonConfig.loadAndValidate[CoinConfig](config)

  def parseAndLoadOrThrow(files: Seq[File])(implicit
      elc: ErrorLoggingContext = elc
  ): CoinConfig =
    CantonConfig.parseAndLoad[CoinConfig](files).valueOr(error => throw CoinConfigException(error))

  def loadOrThrow(config: Config)(implicit elc: ErrorLoggingContext = elc): CoinConfig = {
    CantonConfig
      .loadAndValidate[CoinConfig](config)
      .valueOr(error => throw CoinConfigException(error))
  }

  def writeToFile(config: CoinConfig, path: Path, confidential: Boolean = true): Unit = {
    val writers = new CoinConfig.ConfigWriters(confidential)
    import writers.*
    val renderer = ConfigRenderOptions
      .defaults()
      .setOriginComments(false)
      .setComments(false)
      .setJson(false)
    val content = "canton { " + ConfigWriter[CoinConfig]
      .to(config)
      .render(renderer) + "}"
    Files.write(path, content.getBytes(StandardCharsets.UTF_8)).discard
  }

  lazy val defaultConfigRenderer =
    ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false)
}

object CoinConfigException {
  def apply(error: CantonConfigError): CoinConfigException =
    error.throwableO.fold(new CoinConfigException(error.cause))(t =>
      new CoinConfigException(error.cause, t)
    )
}

@SuppressWarnings(Array("org.wartremover.warts.Null"))
final case class CoinConfigException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
    with NoStackTrace
