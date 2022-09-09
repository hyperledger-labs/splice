package com.daml.network.config

import cats.data.Validated
import cats.syntax.functor._
import com.daml.network.directory.provider.config.{
  LocalDirectoryProviderAppConfig,
  RemoteDirectoryProviderAppConfig,
}
import com.daml.network.directory.user.config.LocalDirectoryUserAppConfig
import com.daml.network.scan.config.{LocalScanAppConfig, RemoteScanAppConfig}
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
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
    directoryProviderApps: Map[InstanceName, LocalDirectoryProviderAppConfig] = Map.empty,
    directoryUserApps: Map[InstanceName, LocalDirectoryUserAppConfig] = Map.empty,
    splitwiseApps: Map[InstanceName, LocalSplitwiseAppConfig] = Map.empty,
    // TODO(Arne): we want to remove all of these.
    domains: Map[InstanceName, CommunityDomainConfig] = Map.empty,
    participants: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteDomains: Map[InstanceName, RemoteDomainConfig] = Map.empty,
    remoteParticipants: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    monitoring: MonitoringConfig = MonitoringConfig(),
    parameters: CantonParameters = CantonParameters(),
    features: CantonFeatures = CantonFeatures(),
) extends CantonConfig // TODO(Arne): generalize or fork this trait.
    with ConfigDefaults[DefaultPorts, CoinConfig] {

  override type DomainConfigType = CommunityDomainConfig
  override type ParticipantConfigType = CommunityParticipantConfig
  override def validate: Validated[NonEmpty[Seq[String]], Unit] = Validated.valid(())

  // TODO(Arne): Revisit all of the classes/methods pertaining to ValidatorNodeParameters.
  // Can hopefully upstream some improvements.
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

  private lazy val directoryProviderAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    directoryProviderApps.fmap { directoryProviderConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        directoryProviderConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        directoryProviderConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def directoryProviderAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(directoryProviderAppParameters_, "directoryProvider-app", appName)

  /** Use `directoryProviderAppParameters` instead!
    */
  def tryDirectoryProviderAppParametersByString(name: String): SharedCoinAppParameters =
    directoryProviderAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `directoryProviders` instead!
    */
  def directoryProvidersByString: Map[String, LocalDirectoryProviderAppConfig] =
    directoryProviderApps.map { case (n, c) =>
      n.unwrap -> c
    }

  private lazy val directoryUserAppParameters_ : Map[InstanceName, SharedCoinAppParameters] =
    directoryUserApps.fmap { directoryUserConfig =>
      SharedCoinAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.getLoggingConfig,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        directoryUserConfig.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        directoryUserConfig.sequencerClient,
        devVersionSupport = false,
        dontWarnOnDeprecatedPV = false,
        initialProtocolVersion = ProtocolVersion.latest,
      )
    }

  private[network] def directoryUserAppParameters(
      appName: InstanceName
  ): SharedCoinAppParameters =
    nodeParametersFor(directoryUserAppParameters_, "directoryUser-app", appName)

  /** Use `directoryUserAppParameters` instead!
    */
  def tryDirectoryUserAppParametersByString(name: String): SharedCoinAppParameters =
    directoryUserAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `directoryUsers` instead!
    */
  def directoryUsersByString: Map[String, LocalDirectoryUserAppConfig] =
    directoryUserApps.map { case (n, c) =>
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

  override def dumpString: String = "TODO(Arne): remove or implement."

  override def withDefaults(ports: DefaultPorts): CoinConfig =
    this // TODO(Arne): CantonCommunityConfig does more here. Do we want to copy that?
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
    implicit val directoryProviderConfigReader: ConfigReader[LocalDirectoryProviderAppConfig] =
      deriveReader[LocalDirectoryProviderAppConfig]
    implicit val remoteDirectoryProviderConfigReader
        : ConfigReader[RemoteDirectoryProviderAppConfig] =
      deriveReader[RemoteDirectoryProviderAppConfig]
    implicit val directoryUserConfigReader: ConfigReader[LocalDirectoryUserAppConfig] =
      deriveReader[LocalDirectoryUserAppConfig]
    implicit val splitwiseConfigReader: ConfigReader[LocalSplitwiseAppConfig] =
      deriveReader[LocalSplitwiseAppConfig]

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
