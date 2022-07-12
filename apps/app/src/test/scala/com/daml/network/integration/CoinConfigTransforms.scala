package com.daml.network.integration

import com.digitalasset.canton.UniquePortGenerator
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.domain.config.CommunityDomainConfig
import com.digitalasset.canton.participant.config.CommunityParticipantConfig
import monocle.macros.syntax.lens._
import cats.syntax.option._
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.digitalasset.canton.config.TimeoutDuration
import scala.concurrent.duration._

object CoinConfigTransforms {

  /** Default transforms to apply to tests using a [[CoinEnvironmentDefinition]].
    * Covers the primary ways that distinct concurrent environments may unintentionally collide.
    */
  lazy val defaults: Seq[CoinConfigTransform] = Seq(globallyUniquePorts) ++ Seq(
    // make unbounded duration bounded for our test
    _.focus(_.parameters.timeouts.console.unbounded)
      .replace(TimeoutDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.unbounded)
      .replace(TimeoutDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.shutdownProcessing)
      .replace(TimeoutDuration.tryFromDuration(10.seconds))
  )

  /** A shared unique port instance to allow every test in a test run to assign unique ports. */
  val globallyUniquePorts: CoinConfigTransform = uniquePorts(
    UniquePortGenerator.forIntegrationTests.next
  )

  def updateAllValidatorConfigs(
      update: (String, LocalValidatorAppConfig) => LocalValidatorAppConfig
  ): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.validatorApps)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllValidatorConfigs_(
      update: LocalValidatorAppConfig => LocalValidatorAppConfig
  ): CoinConfigTransform =
    updateAllValidatorConfigs((_, config) => update(config))

  def updateAllDomainConfigs(
      update: (String, CommunityDomainConfig) => CommunityDomainConfig
  ): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.domains)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllDomainConfigs_(
      update: CommunityDomainConfig => CommunityDomainConfig
  ): CoinConfigTransform =
    updateAllDomainConfigs((_, config) => update(config))

  def updateAllParticipantConfigs(
      update: (String, CommunityParticipantConfig) => CommunityParticipantConfig
  ): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.participants)
        .modify(_.map { case (pName, pConfig) => (pName, update(pName.unwrap, pConfig)) })

  def updateAllParticipantConfigs_(
      update: CommunityParticipantConfig => CommunityParticipantConfig
  ): CoinConfigTransform =
    updateAllParticipantConfigs((_, config) => update(config))

  /** Update a canton config to assign port numbers from the given `startingPort` to all domains and participants */
  def uniquePorts(nextPort: => Port): CoinConfigTransform = {
    val validatorUpdate = updateAllValidatorConfigs_(
      _.focus(_.adminApi.internalPort)
        .replace(nextPort.some)
    )

    val domainUpdate = updateAllDomainConfigs_(
      _.focus(_.publicApi.internalPort)
        .replace(nextPort.some)
        .focus(_.adminApi.internalPort)
        .replace(nextPort.some)
    )

    val participantUpdate = updateAllParticipantConfigs_(
      _.focus(_.ledgerApi.internalPort)
        .replace(nextPort.some)
        .focus(_.adminApi.internalPort)
        .replace(nextPort.some)
    )

    domainUpdate compose participantUpdate compose validatorUpdate
  }
}
