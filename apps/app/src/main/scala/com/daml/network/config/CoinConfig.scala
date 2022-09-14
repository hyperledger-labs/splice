package com.daml.network.config

import cats.data.Validated
import cats.syntax.functor._
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.scan.config.{LocalScanAppConfig, RemoteScanAppConfig}
import com.daml.network.splitwise.config.{LocalSplitwiseAppConfig, RemoteSplitwiseAppConfig}
import com.daml.network.svc.config.{LocalSvcAppConfig, RemoteSvcAppConfig}
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.wallet.config.{LocalWalletAppConfig, RemoteWalletAppConfig}
import com.daml.nonempty.NonEmpty
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
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.ConfigReader

import java.io.File
import scala.annotation.nowarn

case class CoinConfig(
    validatorApps: Map[InstanceName, LocalValidatorAppConfig] = Map.empty,
    svcApp: Option[LocalSvcAppConfig] = None,
    remoteSvcApp: Option[RemoteSvcAppConfig] = None,
    scanApp: Option[LocalScanAppConfig] = None,
    walletApps: Map[InstanceName, LocalWalletAppConfig] = Map.empty,
    remoteWalletApps: Map[InstanceName, RemoteWalletAppConfig] = Map.empty,
    directoryApps: Map[InstanceName, LocalDirectoryAppConfig] = Map.empty,
    remoteDirectoryApps: Map[InstanceName, RemoteDirectoryAppConfig] = Map.empty,
    splitwiseApps: Map[InstanceName, LocalSplitwiseAppConfig] = Map.empty,
    remoteSplitwiseApps: Map[InstanceName, RemoteSplitwiseAppConfig] = Map.empty,
    // TODO(i736): we want to remove all of the configurations options below:
    domains: Map[InstanceName, CommunityDomainConfig] = Map.empty,
    participants: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteDomains: Map[InstanceName, RemoteDomainConfig] = Map.empty,
    remoteParticipants: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    monitoring: MonitoringConfig = MonitoringConfig(),
    parameters: CantonParameters = CantonParameters(),
    features: CantonFeatures = CantonFeatures(),
) extends CantonConfig // TODO(i736): generalize or fork this trait.
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
  def validatorsByString: Map[String, LocalValidatorAppConfig] = validatorApps.map { case (n, c) =>
    n.unwrap -> c
  }

  // The config contains one optional unnamed SVC app (because in M1, there can only be one)
  // Since the rest of the code generally expects a map of nodes, we'll create one.
  private lazy val svcAppInstanceName = InstanceName.tryCreate("svc-app")
  private lazy val svcApps = svcApp.toList.map(config => svcAppInstanceName -> config).toMap
  private lazy val remoteSvcAppInstanceName = InstanceName.tryCreate("remote-svc-app")
  private lazy val remoteSvcApps =
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

  def remoteSvcsByString: Map[String, RemoteSvcAppConfig] = remoteSvcApps.map { case (n, c) =>
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
  def scansByString: Map[String, LocalScanAppConfig] = scanApps.map { case (n, c) =>
    n.unwrap -> c
  }

  private lazy val walletAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    walletApps.fmap { walletConfig =>
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

  private[network] def walletAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(walletAppParameters_, "wallet-app", appName)

  /** Use `walletAppParameters` instead!
    */
  def tryWalletAppParametersByString(name: String): SharedCoinAppParameters =
    walletAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `wallets` instead!
    */
  def walletsByString: Map[String, LocalWalletAppConfig] = walletApps.map { case (n, c) =>
    n.unwrap -> c
  }

  def remoteWalletsByString: Map[String, RemoteWalletAppConfig] = remoteWalletApps.map {
    case (n, c) =>
      n.unwrap -> c
  }

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
  def splitwisesByString: Map[String, LocalSplitwiseAppConfig] =
    splitwiseApps.map { case (n, c) =>
      n.unwrap -> c
    }

  def remoteSplitwisesByString: Map[String, RemoteSplitwiseAppConfig] =
    remoteSplitwiseApps.map { case (n, c) =>
      n.unwrap -> c
    }

  override def dumpString: String = "TODO(i736): remove or implement."

  override def withDefaults(ports: DefaultPorts): CoinConfig =
    this // TODO(i736): CantonCommunityConfig does more here. Do we want to copy that?
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

    implicit val remoteScanConfigReader: ConfigReader[RemoteScanAppConfig] =
      deriveReader[RemoteScanAppConfig]
    implicit val validatorConfigReader: ConfigReader[LocalValidatorAppConfig] =
      deriveReader[LocalValidatorAppConfig]
    implicit val scanConfigReader: ConfigReader[LocalScanAppConfig] =
      deriveReader[LocalScanAppConfig]
    implicit val svcConfigReader: ConfigReader[LocalSvcAppConfig] =
      deriveReader[LocalSvcAppConfig]
    implicit val remoteSvcConfigReader: ConfigReader[RemoteSvcAppConfig] =
      deriveReader[RemoteSvcAppConfig]
    implicit val coinAppParametersReader: ConfigReader[SharedCoinAppParameters] =
      deriveReader[SharedCoinAppParameters]
    implicit val walletConfigReader: ConfigReader[LocalWalletAppConfig] =
      deriveReader[LocalWalletAppConfig]
    implicit val remoteWalletConfigReader: ConfigReader[RemoteWalletAppConfig] =
      deriveReader[RemoteWalletAppConfig]
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
}
