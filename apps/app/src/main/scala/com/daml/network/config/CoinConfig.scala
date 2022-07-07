package com.daml.network.config

import cats.data.Validated
import com.daml.network.validator.config.{LocalValidatorConfig, ValidatorNodeParameters}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.{
  CantonConfig,
  CantonFeatures,
  CantonParameters,
  CommunityConfigValidations,
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
import com.daml.network.svc.config.{LocalSvcAppConfig, SvcAppParameters}
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.ConfigReader

case class CoinConfig(
    validators: Map[InstanceName, LocalValidatorConfig] = Map.empty,
    svcApp: Option[LocalSvcAppConfig],
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
  private lazy val validatorNodeParameters_ : Map[InstanceName, ValidatorNodeParameters] =
    validators.fmap { validatorConfig =>
      val participantParameters = validatorConfig.parameters
      ValidatorNodeParameters(
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
        participantParameters.devVersionSupport,
      )
    }

  private[network] def validatorNodeParameters(
      participant: InstanceName
  ): ValidatorNodeParameters =
    nodeParametersFor(validatorNodeParameters_, "participant", participant)

  /** Use `validatorNodeParameters`` instead!
    */
  def tryValidatorNodeParametersByString(name: String): ValidatorNodeParameters =
    validatorNodeParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `validators` instead!
    */
  def validatorsByString: Map[String, LocalValidatorConfig] = validators.map { case (n, c) =>
    n.unwrap -> c
  }

  // The config contains one optional unnamed SVC app (because in M1, there can only be one)
  // Since the rest of the code generally expects a map of nodes, we'll create one.
  private lazy val svcAppInstanceName = InstanceName.tryCreate("svc-app")
  private lazy val svcApps = svcApp.toList.map(config => svcAppInstanceName -> config).toMap

  private lazy val svcNodeParameters_ : Map[InstanceName, SvcAppParameters] =
    svcApps.fmap { svcConfig =>
      val participantParameters = svcConfig.parameters
      SvcAppParameters(
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
        participantParameters.devVersionSupport,
      )
    }

  private[network] def svcNodeParameters(
      appName: InstanceName
  ): SvcAppParameters =
    nodeParametersFor(svcNodeParameters_, "svc-app", appName)

  /** Use `svcNodeParameters` instead!
    */
  def trySvcAppParametersByString(name: String): SvcAppParameters =
    svcNodeParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `svcs` instead!
    */
  def svcsByString: Map[String, LocalSvcAppConfig] = svcApps.map { case (n, c) =>
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
    implicit val validatorNodeParametersReader: ConfigReader[ValidatorNodeParameters] =
      deriveReader[ValidatorNodeParameters]
    implicit val validatorConfigReader: ConfigReader[LocalValidatorConfig] =
      deriveReader[LocalValidatorConfig]
    implicit val svcNodeParametersReader: ConfigReader[SvcAppParameters] =
      deriveReader[SvcAppParameters]
    implicit val svcConfigReader: ConfigReader[LocalSvcAppConfig] =
      deriveReader[LocalSvcAppConfig]
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
