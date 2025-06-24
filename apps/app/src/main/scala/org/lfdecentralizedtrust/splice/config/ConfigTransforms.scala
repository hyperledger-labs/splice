// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.scan.config.{BftSequencerConfig, ScanAppBackendConfig}
import org.lfdecentralizedtrust.splice.splitwell.config.{
  SplitwellAppBackendConfig,
  SplitwellAppClientConfig,
  SplitwellDomains,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingPartyToParticipantProposalTrigger,
  SvOffboardingSequencerTrigger,
}
import org.lfdecentralizedtrust.splice.sv.config.*
import org.lfdecentralizedtrust.splice.sv.SvAppClientConfig
import org.lfdecentralizedtrust.splice.validator.config.{
  AnsAppExternalClientConfig,
  ValidatorAppBackendConfig,
}
import org.lfdecentralizedtrust.splice.wallet.config.WalletAppClientConfig
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.Port
import monocle.macros.syntax.lens.*
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvBftSequencerPeerOffboardingTrigger

import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.ImmutableMapIsParallelizable
import scala.concurrent.duration.*
import scala.io.Source

object ConfigTransforms {

  val IsTheCantonSequencerBFTEnabled: Boolean = sys.env.contains("SPLICE_USE_BFT_SEQUENCER")

  sealed abstract class ConfigurableApp extends Product with Serializable

  object ConfigurableApp {
    case object Sv extends ConfigurableApp
    case object Scan extends ConfigurableApp
    case object Validator extends ConfigurableApp
    case object Splitwell extends ConfigurableApp
    val All = Seq(Sv, Scan, Validator, Splitwell)
  }

  def makeAllTimeoutsBounded: ConfigTransform = {
    // make unbounded duration bounded for our test
    _.focus(_.parameters.timeouts.console.unbounded)
      .replace(NonNegativeDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.unbounded)
      .replace(NonNegativeDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.shutdownProcessing)
      .replace(NonNegativeDuration.tryFromDuration(10.seconds))
  }

  def addConfigName(context: String): ConfigTransform = { config =>
    config.copy(name = Some(context))
  }

  /** Ensure that the set of Daml user names used in a given instance of a configuration
    * have a common, context-specific suffix.
    *
    * Note that this creates usernames that are textually different from what appears
    * in the source text of a '.conf' file. To reference these names in a '.canton' file,
    * you must read them from the objects themselves:
    *
    * val validatorUserName = validatorApp.config.ledgerApiUser
    * // validatorUserName will have the name with the suffix applied
    * val validatorParty = validatorParticipant.ledger_api.parties.allocate(validatorUserName, validatorUserName).party
    */
  def addDamlNameSuffix(suffix: String): ConfigTransform = { config =>
    val transforms = Seq(
      updateAllSvAppConfigs_(c =>
        c.copy(
          ledgerApiUser = s"${c.ledgerApiUser}-$suffix",
          validatorLedgerApiUser = s"${c.validatorLedgerApiUser}-$suffix",
          svPartyHint = c.svPartyHint.map(sv => s"$sv-$suffix"),
          onboarding = c.onboarding match {
            case Some(foundDso: SvOnboardingConfig.FoundDso) =>
              Some(
                foundDso.copy(
                  dsoPartyHint = s"${foundDso.dsoPartyHint}-$suffix"
                )
              )
            case Some(joinWithKey: SvOnboardingConfig.JoinWithKey) => Some(joinWithKey)
            case Some(domainMigration: SvOnboardingConfig.DomainMigration) => Some(domainMigration)
            case None => None
          },
        )
      ),
      updateAllSvAppFoundDsoConfigs_(c =>
        c.copy(
          dsoPartyHint = s"${c.dsoPartyHint}-$suffix"
        )
      ),
      updateAllScanAppConfigs_(c => c.copy(svUser = s"${c.svUser}-$suffix")),
      updateAllValidatorConfigs_(c =>
        c.copy(
          ledgerApiUser = s"${c.ledgerApiUser}-$suffix",
          validatorPartyHint =
            c.validatorPartyHint.map(h => h.replaceAll("-(.*)-", s"-$$1$suffix-")),
          validatorWalletUsers = c.validatorWalletUsers.map(u => s"$u-$suffix"),
          appInstances = c.appInstances.view
            .mapValues(i =>
              i.copy(
                serviceUser = s"${i.serviceUser}-$suffix",
                walletUser = i.walletUser.map(u => s"$u-$suffix"),
              )
            )
            .toMap,
          svUser = c.svUser.map(u => s"$u-$suffix"),
        )
      ),
      updateAllWalletAppClientConfigs_(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateAllSplitwellAppConfigs_(c => c.copy(providerUser = s"${c.providerUser}-$suffix")),
      updateAllRemoteSplitwellAppConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
      updateAllAnsAppExternalClientConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }

  def reducePollingInterval: ConfigTransform = { config =>
    def setPollingIntervalInternal(config: AutomationConfig): AutomationConfig =
      config
        .focus(_.pollingInterval)
        .replace(NonNegativeFiniteDuration.ofSeconds(1))
        .focus(_.rewardOperationPollingInterval)
        .replace(NonNegativeFiniteDuration.ofSeconds(1))

    updateAllAutomationConfigs(setPollingIntervalInternal)(config)
  }

  def updateAutomationConfig(
      app: ConfigurableApp
  )(transform: AutomationConfigTransform): ConfigTransform = {
    import ConfigurableApp.*
    app match {
      case Sv => updateAllSvAppConfigs_(c => c.focus(_.automation).modify(transform))
      case Scan => updateAllScanAppConfigs_(c => c.focus(_.automation).modify(transform))
      case Validator => updateAllValidatorConfigs_(c => c.focus(_.automation).modify(transform))
      case Splitwell => updateAllSplitwellAppConfigs_(c => c.focus(_.automation).modify(transform))
    }
  }

  def updateAllAutomationConfigs(transform: AutomationConfigTransform): ConfigTransform = {
    config =>
      val transforms = Seq(
        updateAllSvAppConfigs_(c => c.focus(_.automation).modify(transform)),
        updateAllScanAppConfigs_(c => c.focus(_.automation).modify(transform)),
        updateAllValidatorConfigs_(c => c.focus(_.automation).modify(transform)),
        updateAllSplitwellAppConfigs_(c => c.focus(_.automation).modify(transform)),
      )
      transforms.foldLeft(config)((c, tf) => tf(c))
  }

  /** Ensure that the set of Daml user names used in a given instance of a configuration
    * are novel and unshared with any previous instance of that configuration. This is used
    * To isolate one set of tests from another. (Leveraging Daml's party visiblity model.)
    *
    * Note that this creates usernames that are textually different from what appears
    * in the source text of a '.conf' file. To reference these names in a '.canton' file,
    * you must read them from the objects themselves:
    *
    * val validatorUserName = validatorApp.config.ledgerApiUser
    * // validatorUserName will have the name with the suffix applied
    * val validatorParty = validatorParticipant.ledger_api.parties.allocate(validatorUserName, validatorUserName).party
    */
  def ensureNovelDamlNames(id: Option[String] = None): ConfigTransform = { config =>
    val _id = id.getOrElse((new scala.util.Random).nextInt().toHexString.toLowerCase)
    addConfigName(_id)(addDamlNameSuffix(_id)(config))
  }

  /** Default transforms to apply to tests using a [[EnvironmentDefinition]].
    * Covers the primary ways that distinct concurrent environments may unintentionally
    * collide, and adds a suffix to Daml user names that is specific to a given test
    * context.
    */
  def defaults(testId: Option[String] = None): Seq[ConfigTransform] = {
    Seq(
      makeAllTimeoutsBounded,
      ensureNovelDamlNames(testId),
      useSelfSignedTokensForLedgerApiAuth("test"),
      reducePollingInterval,
      withPausedSvDomainComponentsOffboardingTriggers(),
      disableOnboardingParticipantPromotionDelay(),
      setDefaultGrpcDeadlineForBuyExtraTraffic(),
      setDefaultGrpcDeadlineForTreasuryService(),
    )
  }

  import cats.Endo
  type AnsExternalClientConfigReader = Endo[AnsAppExternalClientConfig]
  type ValidatorAppTransform = Endo[ValidatorAppBackendConfig]
  type WalletAppClientTransform = Endo[WalletAppClientConfig]
  type ScanAppTransform = Endo[ScanAppBackendConfig]
  type SplitwellAppTransform = Endo[SplitwellAppBackendConfig]
  type RemoteSplitwellAppTransform = Endo[SplitwellAppClientConfig]
  type AutomationConfigTransform = Endo[AutomationConfig]

  def withPausedSvDomainComponentsOffboardingTriggers(): ConfigTransform =
    updateAutomationConfig(ConfigurableApp.Sv)(
      _.withPausedTrigger[SvOffboardingMediatorTrigger]
        .withPausedTrigger[SvOffboardingSequencerTrigger]
        .withPausedTrigger[SvBftSequencerPeerOffboardingTrigger]
    )

  def withPausedSvOffboardingMediatorAndPartyToParticipantTriggers(): ConfigTransform =
    updateAutomationConfig(ConfigurableApp.Sv)(
      _.withPausedTrigger[SvOffboardingMediatorTrigger]
        .withPausedTrigger[SvOffboardingPartyToParticipantProposalTrigger]
    )

  def withResumedOffboardingTriggers(): ConfigTransform = {
    updateAutomationConfig(ConfigurableApp.Sv)(
      _.withResumedTrigger[SvOffboardingMediatorTrigger]
        .withResumedTrigger[SvOffboardingSequencerTrigger]
        .withResumedTrigger[SvBftSequencerPeerOffboardingTrigger]
    )
  }

  def setAmuletPrice(price: BigDecimal): ConfigTransform =
    config =>
      Seq(
        updateAllSvAppFoundDsoConfigs_(c => c.focus(_.initialAmuletPrice).replace(price)),
        updateAllSvAppConfigs_(c => c.focus(_.initialAmuletPriceVote).replace(Some(price))),
      ).foldLeft(config)((c, tf) => tf(c))

  def updateAllWalletAppClientConfigs(
      update: (String, WalletAppClientConfig) => WalletAppClientConfig
  ): ConfigTransform =
    _.focus(_.walletAppClients).modify(_.map { case (name, config) =>
      name -> update(name.unwrap, config)
    })

  def updateAllWalletAppClientConfigs_(
      update: WalletAppClientTransform
  ): ConfigTransform =
    updateAllWalletAppClientConfigs((_, config) => update(config))

  def updateAllAnsAppExternalClientConfigs_(
      update: AnsExternalClientConfigReader
  ): ConfigTransform =
    _.focus(_.ansAppExternalClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllScanAppConfigs_(
      update: ScanAppBackendConfig => ScanAppBackendConfig
  ): ConfigTransform =
    updateAllScanAppConfigs((_, config) => update(config))

  def updateAllScanAppConfigs(
      update: (String, ScanAppBackendConfig) => ScanAppBackendConfig
  ): ConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.scanApps)
        .modify(_.par.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) }.seq)

  def disableOnboardingParticipantPromotionDelay(): ConfigTransform =
    updateAllSvAppConfigs_(c => c.focus(_.enableOnboardingParticipantPromotionDelay).replace(false))

  def setDefaultGrpcDeadlineForBuyExtraTraffic(): ConfigTransform =
    ConfigTransforms.updateAllValidatorAppConfigs_(c =>
      c.copy(domains =
        c.domains.copy(global =
          c.domains.global.copy(buyExtraTraffic =
            c.domains.global.buyExtraTraffic
              .copy(grpcDeadline = Some(NonNegativeFiniteDuration.ofSeconds(15)))
          )
        )
      )
    )

  def setDefaultGrpcDeadlineForTreasuryService(): ConfigTransform =
    ConfigTransforms.updateAllValidatorAppConfigs_(c =>
      c.copy(treasury =
        c.treasury.copy(
          grpcDeadline = Some(NonNegativeFiniteDuration.ofSeconds(15))
        )
      )
    )

  def updateAllValidatorAppConfigs(
      update: (String, ValidatorAppBackendConfig) => ValidatorAppBackendConfig
  ): ConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.validatorApps)
        .modify(_.par.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) }.seq)

  def updateAllSvAppConfigs(
      update: (String, SvAppBackendConfig) => SvAppBackendConfig
  ): ConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.svApps)
        .modify(
          _.par.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) }.seq
        )

  def updateAllSvAppConfigs_(
      update: SvAppBackendConfig => SvAppBackendConfig
  ): ConfigTransform =
    updateAllSvAppConfigs((_, config) => update(config))

  def updateAllSvAppFoundDsoConfigs_(
      update: Endo[SvOnboardingConfig.FoundDso]
  ): ConfigTransform =
    updateAllSvAppConfigs_(c =>
      c.focus(_.onboarding)
        .modify {
          case Some(foundDso: SvOnboardingConfig.FoundDso) =>
            Some(update(foundDso))
          case Some(joinWithKey: SvOnboardingConfig.JoinWithKey) => Some(joinWithKey)
          case Some(domainMigration: SvOnboardingConfig.DomainMigration) => Some(domainMigration)
          case None => None
        }
    )

  def noDevNet: ConfigTransform =
    updateAllSvAppFoundDsoConfigs_(_.focus(_.isDevNet).replace(false))

  def updateAllValidatorConfigs(
      update: (String, ValidatorAppBackendConfig) => ValidatorAppBackendConfig
  ): ConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.validatorApps)
        .modify(_.par.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) }.seq)

  def updateAllValidatorConfigs_(
      update: ValidatorAppBackendConfig => ValidatorAppBackendConfig
  ): ConfigTransform =
    updateAllValidatorConfigs((_, config) => update(config))

  def updateAllValidatorAppConfigs_(
      update: ValidatorAppTransform
  ): ConfigTransform =
    _.focus(_.validatorApps).modify(
      _.par
        .map { case (name, config) =>
          (name, update(config))
        }
        .seq
    )

  def updateAllSplitwellAppConfigs_(
      update: SplitwellAppTransform
  ): ConfigTransform =
    _.focus(_.splitwellApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllRemoteSplitwellAppConfigs_(
      update: RemoteSplitwellAppTransform
  ): ConfigTransform =
    _.focus(_.splitwellAppClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def bumpCantonDomainPortsBy(bump: Int): ConfigTransform =
    bumpSvAppCantonDomainPortsBy(bump) compose bumpValidatorAppCantonDomainPortsBy(
      bump
    ) compose bumpScanCantonDomainPortsBy(bump)

  def bumpSvAppCantonDomainPortsBy(bump: Int): ConfigTransform = {
    updateAllSvAppConfigs_(
      _.focus(_.domains.global.url)
        .modify(_.map(bumpUrl(bump, _)))
        .focus(_.localSynchronizerNode)
        .modify(
          _.map(d =>
            d.copy(
              sequencer = d.sequencer
                .copy(
                  externalPublicApiUrl = bumpUrl(bump, d.sequencer.externalPublicApiUrl)
                )
            )
          )
        )
    )
  }

  def bumpScanCantonDomainPortsBy(bump: Int) = {
    updateAllScanAppConfigs_(
      _.focus(_.bftSequencers).modify(
        _.map(
          _.focus(_.p2pUrl).modify(
            bumpUrl(bump, _)
          )
        )
      )
    )
  }

  def bumpValidatorAppCantonDomainPortsBy(bump: Int): ConfigTransform = {
    def bumpUrl(s: String): String = {
      val uri = Uri(s)
      uri.withPort(uri.effectivePort + bump).toString
    }
    def bumpOptionalUrl(o: Option[String]): Option[String] = {
      o.map(bumpUrl(_))
    }
    updateAllValidatorConfigs_(
      _.focus(_.domains.global.url)
        .modify(bumpOptionalUrl(_))
        .focus(_.domains.extra)
        .modify(_.map(d => d.copy(url = bumpUrl(d.url))))
    )
  }

  def bumpCantonPortsBy(bump: Int): ConfigTransform = {

    val transforms = Seq(
      updateAllSvAppConfigs_(
        _.focus(_.participantClient)
          .modify(portTransform(bump, _))
          .focus(_.localSynchronizerNode)
          .modify(_.map(portTransform(bump, _)))
      ),
      updateAllScanAppConfigs_(
        _.focus(_.participantClient)
          .modify(portTransform(bump, _))
          .focus(_.sequencerAdminClient)
          .modify(portTransform(bump, _))
          .focus(_.bftSequencers)
          .modify(_.map(_.focus(_.sequencerAdminClient).modify(portTransform(bump, _))))
      ),
      updateAllValidatorConfigs_(
        _.focus(_.participantClient)
          .modify(portTransform(bump, _))
      ),
      updateAllSplitwellAppConfigs_(
        _.focus(_.participantClient).modify(portTransform(bump, _))
      ),
    )

    transforms.foldLeft((c: SpliceConfig) => c)((f, tf) => f compose tf)

  }

  def bumpSomeSvAppPortsBy(bump: Int, svApps: Seq[String]): ConfigTransform = {
    updateAllSvAppConfigs((name, config) => {
      if (svApps.contains(name)) {
        config
          .focus(_.participantClient)
          .modify(portTransform(bump, _))
          .focus(_.localSynchronizerNode)
          .modify(_.map(portTransform(bump, _)))
          .focus(_.adminApi)
          .modify(portTransform(bump, _))
      } else {
        config
      }
    })
  }

  def bumpSomeSvAppCantonDomainPortsBy(bump: Int, svApps: Seq[String]): ConfigTransform = {
    updateAllSvAppConfigs((name, config) => {
      if (svApps.contains(name)) {
        config
          .focus(_.domains.global.url)
          .modify(_.map(bumpUrl(bump, _)))
          .focus(_.localSynchronizerNode)
          .modify(
            _.map(d =>
              d.copy(
                sequencer = d.sequencer
                  .copy(externalPublicApiUrl = bumpUrl(bump, d.sequencer.externalPublicApiUrl))
              )
            )
          )
      } else config
    })
  }

  def bumpUrl(bump: Int, uri: Uri): Uri = {
    uri.withPort(uri.effectivePort + bump)
  }
  def bumpUrl(bump: Int, s: String): String = {
    val uri = Uri(s)
    bumpUrl(bump, uri).toString
  }

  private def setPortPrefix(range: Int): Port => Port = { port =>
    Port.tryCreate((range * 1000) + port.unwrap % 1000)
  }

  private def setPortPrefixInUrl(range: Int): String => String = { s =>
    val uri = Uri(s)
    val port = uri.effectivePort
    uri.withPort((range * 1000) + port % 1000).toString()
  }

  def setSomeSvAppPortsPrefix(range: Int, svApps: Seq[String]): ConfigTransform = {
    updateAllSvAppConfigs((name, config) => {
      if (svApps.contains(name)) {
        config
          .focus(_.participantClient.ledgerApi.clientConfig.port)
          .modify(setPortPrefix(range))
          .focus(_.participantClient.adminApi.port)
          .modify(setPortPrefix(range))
          .focus(_.localSynchronizerNode)
          .modify(_.map(c => setSvSynchronizerConfigPortsPrefix(range, c)))
          .focus(_.adminApi.internalPort)
          .modify(_.map(setPortPrefix(range)))
      } else {
        config
      }
    })
  }

  def setSvSynchronizerConfigPortsPrefix(
      range: Int,
      config: SvSynchronizerNodeConfig,
  ): SvSynchronizerNodeConfig = {
    config
      .focus(_.sequencer.internalApi.port)
      .modify(setPortPrefix(range))
      .focus(_.sequencer.adminApi.port)
      .modify(setPortPrefix(range))
      .focus(_.sequencer.externalPublicApiUrl)
      .modify(setPortPrefixInUrl(range))
      .focus(_.mediator.adminApi.port)
      .modify(setPortPrefix(range))
  }

  def bumpSomeScanAppPortsBy(bump: Int, scanApps: Seq[String]): ConfigTransform = {
    updateAllScanAppConfigs((name, config) => {
      if (scanApps.contains(name)) {
        config
          .focus(_.participantClient)
          .modify(portTransform(bump, _))
          .focus(_.adminApi)
          .modify(portTransform(bump, _))
          .focus(_.sequencerAdminClient)
          .modify(portTransform(bump, _))
      } else {
        config
      }
    })
  }

  def setSomeScanAppPortsPrefix(range: Int, scanApps: Seq[String]): ConfigTransform = {
    updateAllScanAppConfigs((name, config) => {
      if (scanApps.contains(name)) {
        config
          .focus(_.participantClient.ledgerApi.clientConfig.port)
          .modify(setPortPrefix(range))
          .focus(_.participantClient.adminApi.port)
          .modify(setPortPrefix(range))
          .focus(_.adminApi.internalPort)
          .modify(_.map(setPortPrefix(range)))
          .focus(_.sequencerAdminClient.port)
          .modify(setPortPrefix(range))
          .focus(_.bftSequencers)
          .modify(_.map(_.focus(_.sequencerAdminClient.port).modify(setPortPrefix(range))))
      } else {
        config
      }
    })
  }

  def bumpSomeValidatorAppPortsBy(bump: Int, validatorApps: Seq[String]): ConfigTransform = {
    updateAllValidatorAppConfigs((name, config) => {
      if (validatorApps.contains(name)) {
        config
          .focus(_.participantClient)
          .modify(portTransform(bump, _))
          .focus(_.adminApi)
          .modify(portTransform(bump, _))
      } else {
        config
      }
    })
  }

  def bumpSomeWalletClientPortsBy(bump: Int, wallets: Seq[String]): ConfigTransform = {
    updateAllWalletAppClientConfigs((name, config) => {
      if (wallets.contains(name)) {
        config
          .focus(_.adminApi.url)
          .modify(bumpUrl(bump, _))
      } else {
        config
      }
    })
  }

  def setSomeValidatorAppPortsPrefix(
      range: Int,
      validatorApps: Seq[String],
  ): ConfigTransform = {
    updateAllValidatorAppConfigs((name, config) => {
      if (validatorApps.contains(name)) {
        config
          .focus(_.participantClient.ledgerApi.clientConfig.port)
          .modify(setPortPrefix(range))
          .focus(_.participantClient.adminApi.port)
          .modify(setPortPrefix(range))
          .focus(_.adminApi.internalPort)
          .modify(_.map(setPortPrefix(range)))
          .focus(_.onboarding)
          .modify(
            _.map(oc =>
              oc.copy(svClient =
                SvAppClientConfig(
                  NetworkAppClientConfig(url =
                    setPortPrefixInUrl(range)(oc.svClient.adminApi.url.toString)
                  )
                )
              )
            )
          )
      } else {
        config
      }
    })
  }

  def bumpRemoteSplitwellPortsBy(bump: Int): ConfigTransform = {
    updateAllRemoteSplitwellAppConfigs_(
      _.focus(_.participantClient).modify(portTransform(bump, _))
    )
  }

  def bumpSelfHostedParticipantPortsBy(bump: Int): ConfigTransform = {
    val transforms = Seq(
      updateAllValidatorConfigs { case (name, config) =>
        if (name.startsWith("sv")) config
        else
          config.focus(_.participantClient).modify(portTransform(bump, _))
      }
    )
    transforms.foldLeft((c: SpliceConfig) => c)((f, tf) => f compose tf)
  }

  def withBftSequencers(): ConfigTransform = {
    updateAllSvAppConfigs_(appConfig =>
      appConfig
        .focus(_.localSynchronizerNode)
        .modify(
          _.map(
            _.focus(_.sequencer).modify(
              _.copy(
                isBftSequencer = true
              )
            )
          )
        )
    ) compose {
      updateAllScanAppConfigs((scan, config) =>
        config.copy(
          bftSequencers = Seq(
            BftSequencerConfig(
              0,
              config.sequencerAdminClient,
              s"http://localhost:${5010 + Integer.parseInt(scan.stripPrefix("sv").take(1)) * 100}",
            )
          )
        )
      )
    }
  }

  private def portTransform(bump: Int, c: AdminServerConfig): AdminServerConfig =
    c.copy(internalPort = c.internalPort.map(_ + bump))

  private def portTransform(bump: Int, c: FullClientConfig): FullClientConfig =
    c.copy(port = c.port + bump)

  private def portTransform(bump: Int, c: LedgerApiClientConfig): LedgerApiClientConfig =
    c.focus(_.clientConfig).modify(portTransform(bump, _))

  private def portTransform(
      bump: Int,
      c: ParticipantClientConfig,
  ): ParticipantClientConfig =
    c.focus(_.adminApi)
      .modify(portTransform(bump, _))
      .focus(_.ledgerApi)
      .modify(portTransform(bump, _))

  private def portTransform(
      bump: Int,
      c: SvParticipantClientConfig,
  ): SvParticipantClientConfig =
    c.focus(_.adminApi)
      .modify(portTransform(bump, _))
      .focus(_.ledgerApi)
      .modify(portTransform(bump, _))

  private def portTransform(bump: Int, c: SvSequencerConfig): SvSequencerConfig =
    c.focus(_.adminApi)
      .modify(portTransform(bump, _))
      .focus(_.internalApi)
      .modify(portTransform(bump, _))

  private def portTransform(bump: Int, c: SvMediatorConfig): SvMediatorConfig =
    c.focus(_.adminApi).modify(portTransform(bump, _))

  private def portTransform(
      bump: Int,
      c: SvSynchronizerNodeConfig,
  ): SvSynchronizerNodeConfig =
    c.focus(_.sequencer)
      .modify(portTransform(bump, _))
      .focus(_.mediator)
      .modify(portTransform(bump, _))

  /** Auth-enabled Splice apps use self-signed tokens with the given secret for their ledger API connections.
    * Other Splice apps use canton admin tokens for their ledger API connections.
    */
  def useSelfSignedTokensForLedgerApiAuth(secret: String): ConfigTransform = { config =>
    updateAllLedgerApiClientConfigs(
      enableAuth = selfSignedTokenAuthSourceTransform(config.parameters.clock, secret)
    )(config)
  }

  private def updateAllLedgerApiClientConfigs(
      enableAuth: (String, LedgerApiClientConfig) => LedgerApiClientConfig
  ): ConfigTransform = {
    combineAllTransforms(
      updateAllValidatorConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllSvAppConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllScanAppConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.svUser, _))
      }),
      updateAllSplitwellAppConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.providerUser, _))
      }),
      updateAllRemoteSplitwellAppConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
    )
  }

  def selfSignedTokenAuthSourceTransform(clockConfig: ClockConfig, secret: String)(
      user: String,
      c: LedgerApiClientConfig,
  ): LedgerApiClientConfig = {
    val userToken = AuthUtil.LedgerApi.testToken(user = user, secret = secret)
    c.copy(
      authConfig = AuthTokenSourceConfig.Static(
        userToken,
        getAdminToken(clockConfig, c.clientConfig),
      )
    )
  }

  def useSplitwellUpgradeDomain(): ConfigTransform =
    updateAllSplitwellAppConfigs_(c => {
      c.copy(
        domains = c.domains.copy(
          splitwell = SplitwellDomains(
            SynchronizerConfig(SynchronizerAlias.tryCreate("splitwellUpgrade")),
            Seq(
              SynchronizerConfig(SynchronizerAlias.tryCreate("splitwell"))
            ),
          )
        )
      )
    })

  def useDecentralizedSynchronizerSplitwell(): ConfigTransform =
    updateAllSplitwellAppConfigs_(c => {
      c.copy(
        domains = c.domains.copy(
          splitwell = SplitwellDomains(
            SynchronizerConfig(SynchronizerAlias.tryCreate("global")),
            Seq.empty,
          )
        )
      )
    })

  def modifyAllANStorageConfigs(
      storageConfigModifier: (
          String,
          DbConfig,
      ) => DbConfig
  ): ConfigTransform = {
    combineAllTransforms(
      updateAllValidatorConfigs((name, config) =>
        config
          .focus(_.storage)
          .modify(storage => storageConfigModifier(name, storage))
      ),
      updateAllScanAppConfigs((name, config) =>
        config
          .focus(_.storage)
          .modify(storage => storageConfigModifier(name, storage))
      ),
      updateAllSvAppConfigs((name, config) =>
        config
          .focus(_.storage)
          .modify(storage => storageConfigModifier(name, storage))
      ),
    )
  }

  private def combineAllTransforms(transforms: ConfigTransform*) = { (config: SpliceConfig) =>
    transforms.foldLeft(config)((c, tf) => tf(c))
  }

  /** Canton has a built in authorizer that accepts "canton admin tokens",
    * see [[com.digitalasset.canton.participant.ledger.api.CantonAdminTokenAuthService]]
    * These are 128 character random strings (not JWTs), generated independently for each local participant node at canton startup.
    * Attaching an admin token to a ledger API request allows you to bypass auth, i.e., to act as any party and perform all admin operations.
    * There is (intentionally) no way of getting the admin tokens from an external canton process,
    * so we export them to a file in our canton bootstrap script (see `bootstrap-canton.sc`).
    */
  private def readTokenDataFile(clockConfig: ClockConfig): Map[Int, String] = {
    readDataFile("tokens", clockConfig).map { case (k, v) => k.toInt -> v }
  }

  private def getAdminToken(clockConfig: ClockConfig, ledgerApi: ClientConfig): Option[String] = {
    val port = ledgerApi.port.unwrap
    readTokenDataFile(clockConfig).get(port)
  }

  def getParticipantIds(clockConfig: ClockConfig): Map[String, String] = {
    readDataFile("participants", clockConfig)
  }

  private def readDataFile(fileExtension: String, clockConfig: ClockConfig): Map[String, String] = {
    val rows: mutable.Map[String, String] = mutable.Map.empty

    val tokenDataSource = clockConfig match {
      case ClockConfig.RemoteClock(_) => Source.fromFile(s"canton-simtime.$fileExtension")
      case ClockConfig.WallClock(_) => Source.fromFile(s"canton.$fileExtension")
      case ClockConfig.SimClock =>
        sys.error(
          "Unexpected clock mode: use remote-clock for simulated time and wall-clock for normal execution"
        )
    }
    try {
      for (line <- tokenDataSource.getLines()) {
        val parts = line.split(" ")
        if (parts.length == 2)
          rows.put(parts(0), parts(1))
      }
    } finally {
      tokenDataSource.close
    }

    rows.toMap
  }

}
