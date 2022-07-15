package com.daml.network.config

import cats.data.Validated
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.{
  CantonConfig,
  CantonFeatures,
  CantonParameters,
  ConfigDefaults,
  MonitoringConfig,
}
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.domain.config.{CommunityDomainConfig, RemoteDomainConfig}
import com.digitalasset.canton.participant.config.{
  CommunityParticipantConfig,
  RemoteParticipantConfig,
}

import scala.annotation.nowarn
import cats.syntax.functor._
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.ConfigReader

case class CoinConfig(
    validatorApps: Map[InstanceName, LocalValidatorAppConfig] = Map.empty,
    svcApp: Option[LocalSvcAppConfig] = None,
    walletApps: Map[InstanceName, LocalWalletAppConfig] = Map.empty,
    // TODO(Arne): we want to remove all of these.
    domains: Map[InstanceName, CommunityDomainConfig] = Map.empty,
    participants: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteDomains: Map[InstanceName, RemoteDomainConfig] = Map.empty,
    remoteParticipants: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    monitoring: MonitoringConfig = MonitoringConfig(),
    parameters: CantonParameters = CantonParameters(),
    features: CantonFeatures = CantonFeatures(),
) extends CantonConfig // TODO(Arne): generalize or fork this trait.
    with ConfigDefaults[CoinConfig] {

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

  override def dumpString: String = "TODO(Arne): remove or implement."

  override def withDefaults: CoinConfig =
    this // TODO(Arne): CantonCommunityConfig does more here. Do we want to copy that?
}

// All this implicit weirdness below is copied analogue from CantonCommunityConfig
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
  private lazy implicit val coinConfigReader: ConfigReader[CoinConfig] = {
    import CantonConfig.ConfigReaders._
    implicit val validatorConfigReader: ConfigReader[LocalValidatorAppConfig] =
      deriveReader[LocalValidatorAppConfig]
    implicit val svcConfigReader: ConfigReader[LocalSvcAppConfig] =
      deriveReader[LocalSvcAppConfig]
    implicit val coinAppParametersReader: ConfigReader[SharedCoinAppParameters] =
      deriveReader[SharedCoinAppParameters]
    implicit val walletConfigReader: ConfigReader[LocalWalletAppConfig] =
      deriveReader[LocalWalletAppConfig]
    implicit val communityDomainConfigReader: ConfigReader[CommunityDomainConfig] =
      deriveReader[CommunityDomainConfig]
    implicit val communityParticipantConfigReader: ConfigReader[CommunityParticipantConfig] =
      deriveReader[CommunityParticipantConfig]

    deriveReader[CoinConfig]
  }

  def load(config: Config)(implicit
      elc: ErrorLoggingContext = elc
  ): Either[CantonConfigError, CoinConfig] =
    CantonConfig.loadAndValidate[CoinConfig](config)

  def loadOrExit(config: Config)(implicit elc: ErrorLoggingContext = elc): CoinConfig =
    CantonConfig.loadOrExit[CoinConfig](config)
}
