package com.daml.network.config

import cats.data.Validated
import cats.syntax.functor.*
import com.daml.network.auth.AuthConfig
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.scan.config.{LocalScanAppConfig, RemoteScanAppConfig}
import com.daml.network.splitwise.config.{LocalSplitwiseAppConfig, RemoteSplitwiseAppConfig}
import com.daml.network.svc.config.{LocalSvcAppConfig, RemoteSvcAppConfig}
import com.daml.network.validator.config.{
  AppInstance,
  LocalValidatorAppConfig,
  RemoteValidatorAppConfig,
}
import com.daml.network.wallet.config.{
  WalletAppBackendConfig,
  WalletAppClientConfig,
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

case class CoinConfig(
    name: Option[String] = None,
    validatorApps: Map[InstanceName, LocalValidatorAppConfig] = Map.empty,
    remoteValidatorApps: Map[InstanceName, RemoteValidatorAppConfig] = Map.empty,
    svcApp: Option[LocalSvcAppConfig] = None,
    remoteSvcApp: Option[RemoteSvcAppConfig] = None,
    scanApp: Option[LocalScanAppConfig] = None,
    remoteScanApps: Map[InstanceName, RemoteScanAppConfig] = Map.empty,
    walletAppBackends: Map[InstanceName, WalletAppBackendConfig] = Map.empty,
    walletAppClients: Map[InstanceName, WalletAppClientConfig] = Map.empty,
    directoryApp: Option[LocalDirectoryAppConfig] = None,
    remoteDirectoryApps: Map[InstanceName, RemoteDirectoryAppConfig] = Map.empty,
    splitwiseApps: Map[InstanceName, LocalSplitwiseAppConfig] = Map.empty,
    remoteSplitwiseApps: Map[InstanceName, RemoteSplitwiseAppConfig] = Map.empty,
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
        parameters.clock,
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
  def validatorsByString: Map[String, LocalValidatorAppConfig] = validatorApps.map { case (n, c) =>
    n.unwrap -> c
  }

  // The config contains one optional unnamed SVC app (because in M1, there can only be one)
  // Since the rest of the code generally expects a map of nodes, we'll create one.
  private lazy val svcAppInstanceName = InstanceName.tryCreate("svc-app")
  lazy val svcApps = svcApp.toList.map(config => svcAppInstanceName -> config).toMap
  private lazy val remoteSvcAppInstanceName = InstanceName.tryCreate("remote-svc-app")
  lazy val remoteSvcApps =
    remoteSvcApp.toList.map(config => remoteSvcAppInstanceName -> config).toMap

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
        parameters.clock,
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
  def svcsByString: Map[String, LocalSvcAppConfig] = svcApps.map { case (n, c) =>
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
        parameters.clock,
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
  def scansByString: Map[String, LocalScanAppConfig] = scanApps.map { case (n, c) =>
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
        parameters.clock,
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
        parameters.clock,
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
        parameters.clock,
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
  def splitwisesByString: Map[String, LocalSplitwiseAppConfig] =
    splitwiseApps.map { case (n, c) =>
      n.unwrap -> c
    }

  def remoteSplitwisesByString: Map[String, RemoteSplitwiseAppConfig] =
    remoteSplitwiseApps.map { case (n, c) =>
      n.unwrap -> c
    }

  override def dumpString: String = "TODO(#736): remove or implement."

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

  import pureconfig.generic.semiauto._
  import CantonConfig._

  @nowarn("cat=unused")
  private implicit def coinConfigReader(implicit
      elc: ErrorLoggingContext
  ): ConfigReader[CoinConfig] = {
    import CantonConfig.ConfigReaders._
    import DeprecatedConfigUtils._
    import CantonDeprecationImplicits._

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
    implicit val authTokenSourceCCReader: ConfigReader[AuthTokenSourceConfig.ClientCredentials] =
      deriveReader[AuthTokenSourceConfig.ClientCredentials]
    implicit val authTokenSourceConfigReader: ConfigReader[AuthTokenSourceConfig] =
      deriveReader[AuthTokenSourceConfig]

    implicit val automationConfig: ConfigReader[AutomationConfig] =
      deriveReader[AutomationConfig]
    implicit val coinLedgerApiClientConfigReader: ConfigReader[CoinLedgerApiClientConfig] =
      deriveReader[CoinLedgerApiClientConfig]
    implicit val coinRemoteParticipantConfigReader: ConfigReader[CoinRemoteParticipantConfig] =
      deriveReader[CoinRemoteParticipantConfig]
    implicit val appInstanceReader: ConfigReader[AppInstance] =
      deriveReader[AppInstance]
    implicit val remoteScanConfigReader: ConfigReader[RemoteScanAppConfig] =
      deriveReader[RemoteScanAppConfig]
    implicit val validatorConfigReader: ConfigReader[LocalValidatorAppConfig] =
      deriveReader[LocalValidatorAppConfig]
    implicit val remoteValidatorConfigReader: ConfigReader[RemoteValidatorAppConfig] =
      deriveReader[RemoteValidatorAppConfig]
    implicit val scanConfigReader: ConfigReader[LocalScanAppConfig] =
      deriveReader[LocalScanAppConfig]
    implicit val svcConfigReader: ConfigReader[LocalSvcAppConfig] =
      deriveReader[LocalSvcAppConfig]
    implicit val remoteSvcConfigReader: ConfigReader[RemoteSvcAppConfig] =
      deriveReader[RemoteSvcAppConfig]
    implicit val coinAppParametersReader: ConfigReader[SharedCoinAppParameters] =
      deriveReader[SharedCoinAppParameters]
    implicit val walletRemoteValidatorConfigReader: ConfigReader[WalletRemoteValidatorAppConfig] =
      deriveReader[WalletRemoteValidatorAppConfig]
    implicit val walletBackendConfigReader: ConfigReader[WalletAppBackendConfig] =
      deriveReader[WalletAppBackendConfig]
    implicit val WalletAppClientConfigReader: ConfigReader[WalletAppClientConfig] =
      deriveReader[WalletAppClientConfig]
    implicit val directoryConfigReader: ConfigReader[LocalDirectoryAppConfig] =
      deriveReader[LocalDirectoryAppConfig]
    implicit val remoteDirectoryConfigReader: ConfigReader[RemoteDirectoryAppConfig] =
      deriveReader[RemoteDirectoryAppConfig]
    implicit val splitwiseConfigReader: ConfigReader[LocalSplitwiseAppConfig] =
      deriveReader[LocalSplitwiseAppConfig]
    implicit val remoteSplitwiseConfigReader: ConfigReader[RemoteSplitwiseAppConfig] =
      deriveReader[RemoteSplitwiseAppConfig]

    implicit val communityDomainConfigReader: ConfigReader[CommunityDomainConfig] =
      deriveReader[CommunityDomainConfig].applyDeprecations
    implicit val communityParticipantConfigReader: ConfigReader[CommunityParticipantConfig] =
      deriveReader[CommunityParticipantConfig].applyDeprecations

    deriveReader[CoinConfig]
  }

  @nowarn("cat=unused")
  private implicit def coinConfigWriter: ConfigWriter[CoinConfig] = {
    val writers = new CantonConfig.ConfigWriters(confidential = false)
    import writers._
    import DeprecatedConfigUtils._
    import CantonDeprecationImplicits._

    implicit val authConfigHint = new FieldCoproductHint[AuthConfig]("algorithm")

    implicit val hs256UnsafeConfig: ConfigWriter[AuthConfig.Hs256Unsafe] =
      deriveWriter[AuthConfig.Hs256Unsafe]
    implicit val rs256Config: ConfigWriter[AuthConfig.Rs256] =
      deriveWriter[AuthConfig.Rs256]
    implicit val authConfig: ConfigWriter[AuthConfig] =
      deriveWriter[AuthConfig]

    implicit val authTokenSourceConfigHint: FieldCoproductHint[AuthTokenSourceConfig] =
      new FieldCoproductHint[AuthTokenSourceConfig]("type")
    implicit val authTokenSourceNoneWriter: ConfigWriter[AuthTokenSourceConfig.None] =
      deriveWriter[AuthTokenSourceConfig.None]
    implicit val authTokenSourceStaticWriter: ConfigWriter[AuthTokenSourceConfig.Static] =
      deriveWriter[AuthTokenSourceConfig.Static]
    implicit val authTokenSourceCCWriter: ConfigWriter[AuthTokenSourceConfig.ClientCredentials] =
      deriveWriter[AuthTokenSourceConfig.ClientCredentials]
    implicit val authTokenSourceConfigWriter: ConfigWriter[AuthTokenSourceConfig] =
      deriveWriter[AuthTokenSourceConfig]

    implicit val automationConfig: ConfigWriter[AutomationConfig] =
      deriveWriter[AutomationConfig]
    implicit val coinLedgerApiClientConfigWriter: ConfigWriter[CoinLedgerApiClientConfig] =
      deriveWriter[CoinLedgerApiClientConfig]
    implicit val coinRemoteParticipantConfigWriter: ConfigWriter[CoinRemoteParticipantConfig] =
      deriveWriter[CoinRemoteParticipantConfig]
    implicit val appInstanceWriter: ConfigWriter[AppInstance] =
      deriveWriter[AppInstance]
    implicit val remoteScanConfigWriter: ConfigWriter[RemoteScanAppConfig] =
      deriveWriter[RemoteScanAppConfig]
    implicit val validatorConfigWriter: ConfigWriter[LocalValidatorAppConfig] =
      deriveWriter[LocalValidatorAppConfig]
    implicit val remoteValidatorConfigWriter: ConfigWriter[RemoteValidatorAppConfig] =
      deriveWriter[RemoteValidatorAppConfig]
    implicit val scanConfigWriter: ConfigWriter[LocalScanAppConfig] =
      deriveWriter[LocalScanAppConfig]
    implicit val svcConfigWriter: ConfigWriter[LocalSvcAppConfig] =
      deriveWriter[LocalSvcAppConfig]
    implicit val remoteSvcConfigWriter: ConfigWriter[RemoteSvcAppConfig] =
      deriveWriter[RemoteSvcAppConfig]
    implicit val coinAppParametersWriter: ConfigWriter[SharedCoinAppParameters] =
      deriveWriter[SharedCoinAppParameters]
    implicit val walletRemoteValidatorConfigWriter: ConfigWriter[WalletRemoteValidatorAppConfig] =
      deriveWriter[WalletRemoteValidatorAppConfig]
    implicit val walletBackendConfigWriter: ConfigWriter[WalletAppBackendConfig] =
      deriveWriter[WalletAppBackendConfig]
    implicit val WalletAppClientConfigWriter: ConfigWriter[WalletAppClientConfig] =
      deriveWriter[WalletAppClientConfig]
    implicit val directoryConfigWriter: ConfigWriter[LocalDirectoryAppConfig] =
      deriveWriter[LocalDirectoryAppConfig]
    implicit val remoteDirectoryConfigWriter: ConfigWriter[RemoteDirectoryAppConfig] =
      deriveWriter[RemoteDirectoryAppConfig]
    implicit val splitwiseConfigWriter: ConfigWriter[LocalSplitwiseAppConfig] =
      deriveWriter[LocalSplitwiseAppConfig]
    implicit val remoteSplitwiseConfigWriter: ConfigWriter[RemoteSplitwiseAppConfig] =
      deriveWriter[RemoteSplitwiseAppConfig]

    implicit val communityDomainConfigWriter: ConfigWriter[CommunityDomainConfig] =
      deriveWriter[CommunityDomainConfig]
    implicit val communityParticipantConfigWriter: ConfigWriter[CommunityParticipantConfig] =
      deriveWriter[CommunityParticipantConfig]

    deriveWriter[CoinConfig]
  }

  def load(config: Config)(implicit
      elc: ErrorLoggingContext = elc
  ): Either[CantonConfigError, CoinConfig] =
    CantonConfig.loadAndValidate[CoinConfig](config)

  def parseAndLoadOrExit(files: Seq[File])(implicit
      elc: ErrorLoggingContext = elc
  ): CoinConfig =
    CantonConfig.parseAndLoadOrExit[CoinConfig](files)

  def loadOrExit(config: Config)(implicit elc: ErrorLoggingContext = elc): CoinConfig =
    CantonConfig.loadOrExit[CoinConfig](config)

  def writeToFile(config: CoinConfig, path: Path): Unit = {
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
}
