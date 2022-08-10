package com.daml.network.integration

import com.daml.network.directory.provider.config.LocalDirectoryProviderAppConfig
import com.daml.network.directory.user.config.LocalDirectoryUserAppConfig
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.wallet.config.{LocalWalletAppConfig, RemoteWalletAppConfig}
import com.digitalasset.canton.config.{NodeConfig, TimeoutDuration}
import com.digitalasset.canton.domain.config.CommunityDomainConfig
import com.digitalasset.canton.participant.config.CommunityParticipantConfig
import java.util.UUID
import monocle.macros.syntax.lens._

import scala.concurrent.duration._

object CoinConfigTransforms {

  /** Default transforms to apply to tests using a [[CoinEnvironmentDefinition]].
    * Covers the primary ways that distinct concurrent environments may unintentionally collide.
    */
  lazy val defaults: Seq[CoinConfigTransform] = {
    Seq(
      // make unbounded duration bounded for our test
      _.focus(_.parameters.timeouts.console.unbounded)
        .replace(TimeoutDuration.tryFromDuration(2.minutes))
        .focus(_.parameters.timeouts.processing.unbounded)
        .replace(TimeoutDuration.tryFromDuration(2.minutes))
        .focus(_.parameters.timeouts.processing.shutdownProcessing)
        .replace(TimeoutDuration.tryFromDuration(10.seconds)),
      config0 => {
        val suffix = UUID.randomUUID()
        val config1 = updateSvcConfig(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(config0)
        val config2 = updateCcScanConfig(c => c.copy(svcUser = s"${c.svcUser}-$suffix"))(config1)
        val config3 = updateAllValidatorConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(config2)
        val config4 = updateAllWalletAppConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(config3)
        val config5 = updateAllDirectoryProviderAppConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(config4)
        val config6 = updateAllDirectoryUserAppConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(config5)
        config6
      },
    )
  }

  type CnAppConfigTransform[A <: NodeConfig] = A => A
  type DirectoryUserAppTransform = CnAppConfigTransform[LocalDirectoryUserAppConfig]
  type DirectoryProviderAppTransform = CnAppConfigTransform[LocalDirectoryProviderAppConfig]
  type ValidatorAppTransform = CnAppConfigTransform[LocalValidatorAppConfig]
  type WalletAppTransform = CnAppConfigTransform[LocalWalletAppConfig]
  type RemoteWalletAppTransform = CnAppConfigTransform[RemoteWalletAppConfig]
  type SvcAppTransform = CnAppConfigTransform[LocalSvcAppConfig]
  type ScanAppTransform = CnAppConfigTransform[LocalScanAppConfig]

  def updateAllDirectoryUserAppConfigs_(
      update: DirectoryUserAppTransform
  ): CoinConfigTransform =
    _.focus(_.directoryUserApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllDirectoryProviderAppConfigs_(
      update: DirectoryProviderAppTransform
  ): CoinConfigTransform =
    _.focus(_.directoryProviderApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllWalletAppConfigs_(
      update: WalletAppTransform
  ): CoinConfigTransform =
    _.focus(_.walletApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllAppConfigs_(
      update: WalletAppTransform
  ): CoinConfigTransform =
    _.focus(_.walletApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateCcScanConfig(update: ScanAppTransform): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.scanApp)
        .replace(cantonConfig.scanApp match {
          case None => None
          case Some(scan) => Some(update(scan))
        })

  def updateSvcConfig(update: SvcAppTransform): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.svcApp)
        .replace(cantonConfig.svcApp match {
          case None => None
          case Some(svcApp) => Some(update(svcApp))
        })

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
}
