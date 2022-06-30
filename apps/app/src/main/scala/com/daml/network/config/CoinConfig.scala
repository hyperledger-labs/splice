package com.daml.network.config

import cats.data.Validated
import com.daml.network.validator.config.{LocalValidatorConfig, ValidatorNodeParameters}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.{
  CantonConfig,
  CantonFeatures,
  CantonParameters,
  CommunityConfigValidations,
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

case class CoinConfig(
    validators: Map[InstanceName, LocalValidatorConfig],
    // TODO(Arne): we want to remove all of these.
    domains: Map[InstanceName, CommunityDomainConfig] = Map.empty,
    participants: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteDomains: Map[InstanceName, RemoteDomainConfig] = Map.empty,
    remoteParticipants: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    monitoring: MonitoringConfig = MonitoringConfig(),
    parameters: CantonParameters = CantonParameters(),
    features: CantonFeatures = CantonFeatures(),
) extends CantonConfig // TODO(Arne): generalize or fork this trait.
    {

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

  override def dumpString: String = "TODO(Arne): remove or implement."
}
