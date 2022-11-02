package com.daml.network.integration

import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.splitwise.config.{LocalSplitwiseAppConfig, RemoteSplitwiseAppConfig}
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.wallet.config.{LocalWalletAppConfig, RemoteWalletAppConfig}
import com.digitalasset.canton.config.{
  ClientConfig,
  CommunityAdminServerConfig,
  NodeConfig,
  NonNegativeDuration,
}
import com.digitalasset.canton.domain.config.{CommunityDomainConfig, CommunityPublicServerConfig}
import com.digitalasset.canton.participant.config.{
  CommunityParticipantConfig,
  LedgerApiServerConfig,
  RemoteParticipantConfig,
}
import monocle.macros.syntax.lens._

import java.util.UUID
import scala.concurrent.duration._

object CoinConfigTransforms {

  def makeAllTimeoutsBounded: CoinConfigTransform = {
    // make unbounded duration bounded for our test
    _.focus(_.parameters.timeouts.console.unbounded)
      .replace(NonNegativeDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.unbounded)
      .replace(NonNegativeDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.shutdownProcessing)
      .replace(NonNegativeDuration.tryFromDuration(10.seconds))
  }

  /** Ensure that the set of Daml user names used in a given instance of a configuration
    * have a common, context-specific suffix.
    *
    * Note that this creates usernames that are textually different from what appears
    * in the source text of a '.conf' file. To reference these names in a '.canton' file,
    * you must read them from the objects themselves:
    *
    * val validatorUserName = validatorApp.config.damlUser
    * // validatorUserName will have the name with the suffix applied
    * val validatorParty = validatorParticipant.parties.enable(validatorUserName)
    */
  def addDamlNameSuffix(context: String): CoinConfigTransform = { config =>
    {
      val suffix = s"${context}".toLowerCase

      val config1 = updateSvcConfig(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(config)
      val config2 = updateCcScanConfig(c => c.copy(svcUser = s"${c.svcUser}-$suffix"))(config1)
      val config3 =
        updateAllValidatorConfigs_(c =>
          c.copy(
            damlUser = s"${c.damlUser}-$suffix",
            walletServiceUser = s"${c.walletServiceUser}-$suffix",
            appInstances = c.appInstances.view
              .mapValues(i => i.copy(serviceUser = s"${i.serviceUser}-$suffix"))
              .toMap,
          )
        )(config2)
      val config4 =
        updateAllWalletAppConfigs_(c => c.copy(serviceUser = s"${c.serviceUser}-$suffix"))(
          config3
        )
      val config5 =
        updateAllRemoteWalletAppConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(
          config4
        )
      val config6 =
        updateAllDirectoryAppConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(
          config5
        )
      val config7 =
        updateAllSplitwiseAppConfigs_(c => c.copy(providerUser = s"${c.providerUser}-$suffix"))(
          config6
        )
      val config8 =
        updateAllRemoteSplitwiseAppConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(
          config7
        )
      val config9 = updateAllRemoteDirectoryAppConfigs_(c =>
        c.copy(damlUser = s"${c.damlUser}-$suffix")
      )(config8)
      config9
    }
  }

  /** Ensure that the set of Daml user names used in a given instance of a configuration
    * are novel and unshared with any previous instance of that configuration. This is used
    * To isolate one set of tests from another. (Leveraging Daml's party visiblity model.)
    *
    * Note that this creates usernames that are textually different from what appears
    * in the source text of a '.conf' file. To reference these names in a '.canton' file,
    * you must read them from the objects themselves:
    *
    * val validatorUserName = validatorApp.config.damlUser
    * // validatorUserName will have the name with the suffix applied
    * val validatorParty = validatorParticipant.parties.enable(validatorUserName)
    */
  def ensureNovelDamlNames(): CoinConfigTransform = { config =>
    addDamlNameSuffix(UUID.randomUUID().toString())(config)
  }

  /** Default transforms to apply to tests using a [[CoinEnvironmentDefinition]].
    * Covers the primary ways that distinct concurrent environments may unintentionally
    * collide, and adds a suffix to Daml user names that is specific to a given test
    * context.
    */
  def defaults(testContextNameSuffix: String): Seq[CoinConfigTransform] = {
    Seq(
      makeAllTimeoutsBounded,
      addDamlNameSuffix(testContextNameSuffix),
      ensureNovelDamlNames(),
    )
  }

  type CnAppConfigTransform[A <: NodeConfig] = A => A
  type DirectoryAppTransform = CnAppConfigTransform[LocalDirectoryAppConfig]
  type RemoteDirectoryAppTransform = CnAppConfigTransform[RemoteDirectoryAppConfig]
  type ValidatorAppTransform = CnAppConfigTransform[LocalValidatorAppConfig]
  type WalletAppTransform = CnAppConfigTransform[LocalWalletAppConfig]
  type RemoteWalletAppTransform = CnAppConfigTransform[RemoteWalletAppConfig]
  type SvcAppTransform = CnAppConfigTransform[LocalSvcAppConfig]
  type ScanAppTransform = CnAppConfigTransform[LocalScanAppConfig]
  type SplitwiseAppTransform = CnAppConfigTransform[LocalSplitwiseAppConfig]
  type RemoteSplitwiseAppTransform = CnAppConfigTransform[RemoteSplitwiseAppConfig]

  def updateAllDirectoryAppConfigs_(
      update: DirectoryAppTransform
  ): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.directoryApp)
        .replace(cantonConfig.directoryApp match {
          case None => None
          case Some(directoryApp) => Some(update(directoryApp))
        })

  def updateAllRemoteDirectoryAppConfigs_(
      update: RemoteDirectoryAppTransform
  ): CoinConfigTransform =
    _.focus(_.remoteDirectoryApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllWalletAppConfigs_(
      update: WalletAppTransform
  ): CoinConfigTransform =
    _.focus(_.walletApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllRemoteWalletAppConfigs_(
      update: RemoteWalletAppTransform
  ): CoinConfigTransform =
    _.focus(_.remoteWalletApps).modify(_.map { case (name, config) =>
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

  def updateAllSplitwiseAppConfigs_(
      update: SplitwiseAppTransform
  ): CoinConfigTransform =
    _.focus(_.splitwiseApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllRemoteSplitwiseAppConfigs_(
      update: RemoteSplitwiseAppTransform
  ): CoinConfigTransform =
    _.focus(_.remoteSplitwiseApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

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

  // Bump ports by 1000 to avoid collisions with the Canton instance started
  // outside of our tests.
  def bumpCantonPortsBy1000: CoinConfigTransform = {
    val domain = updateAllDomainConfigs_(
      _.focus(_.adminApi).modify(portTransform).focus(_.publicApi).modify(portTransform)
    )
    val participant = updateAllParticipantConfigs_(
      _.focus(_.adminApi).modify(portTransform).focus(_.ledgerApi).modify(portTransform)
    )
    val validator = updateAllValidatorConfigs_(_.focus(_.remoteParticipant).modify(portTransform))
    val wallet = updateAllWalletAppConfigs_(_.focus(_.remoteParticipant).modify(portTransform))
    val svc = updateSvcConfig(_.focus(_.remoteParticipant).modify(portTransform))
    val scan = updateCcScanConfig(_.focus(_.remoteParticipant).modify(portTransform))

    domain compose participant compose validator compose wallet compose svc compose scan
  }

  // Our SVC participant is instance 0 usually. However in our runbook
  // our users are not exposed to that so we also use 0 for their participant. This
  // rewrites the SVC ports by an extra 1000 to avoid collisions.
  def bumpSvcParticipantPortsBy1000: CoinConfigTransform = {
    val participant = updateAllParticipantConfigs { case (name, conf) =>
      if (name == "svc_participant") {
        conf.focus(_.adminApi).modify(portTransform).focus(_.ledgerApi).modify(portTransform)
      } else conf
    }
    val svc = updateSvcConfig(_.focus(_.remoteParticipant).modify(portTransform))
    val scan = updateCcScanConfig(_.focus(_.remoteParticipant).modify(portTransform))
    participant compose svc compose scan
  }

  private def portTransform(c: CommunityAdminServerConfig): CommunityAdminServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p + 1000))
  private def portTransform(c: CommunityPublicServerConfig): CommunityPublicServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p + 1000))
  private def portTransform(c: ClientConfig): ClientConfig =
    c.copy(port = c.port + 1000)
  private def portTransform(c: LedgerApiServerConfig): LedgerApiServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p + 1000))
  private def portTransform(c: RemoteParticipantConfig): RemoteParticipantConfig =
    c.focus(_.adminApi).modify(portTransform).focus(_.ledgerApi).modify(portTransform)

}
