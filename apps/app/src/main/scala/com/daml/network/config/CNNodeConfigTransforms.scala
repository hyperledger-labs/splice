package com.daml.network.config

import org.apache.pekko.http.scaladsl.model.Uri
import com.daml.network.auth.AuthUtil
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.splitwell.config.{
  SplitwellAppBackendConfig,
  SplitwellAppClientConfig,
  SplitwellDomains,
}
import com.daml.network.sv.automation.singlesv.membership.offboarding.SvOffboardingSequencerTrigger
import com.daml.network.sv.automation.singlesv.offboarding.SvOffboardingMediatorTrigger
import com.daml.network.sv.config.*
import com.daml.network.validator.config.{
  AppManagerAppClientConfig,
  AppManagerConfig,
  CnsAppExternalClientConfig,
  ValidatorAppBackendConfig,
}
import com.daml.network.wallet.config.WalletAppClientConfig
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import monocle.macros.syntax.lens.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.io.Source

object CNNodeConfigTransforms {

  sealed abstract class ConfigurableApp extends Product with Serializable

  object ConfigurableApp {
    case object Sv extends ConfigurableApp
    case object Scan extends ConfigurableApp
    case object Validator extends ConfigurableApp
    case object Splitwell extends ConfigurableApp
    val All = Seq(Sv, Scan, Validator, Splitwell)
  }

  def makeAllTimeoutsBounded: CNNodeConfigTransform = {
    // make unbounded duration bounded for our test
    _.focus(_.parameters.timeouts.console.unbounded)
      .replace(NonNegativeDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.unbounded)
      .replace(NonNegativeDuration.tryFromDuration(2.minutes))
      .focus(_.parameters.timeouts.processing.shutdownProcessing)
      .replace(NonNegativeDuration.tryFromDuration(10.seconds))
  }

  def addConfigName(context: String): CNNodeConfigTransform = { config =>
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
  def addDamlNameSuffix(context: String): CNNodeConfigTransform = { config =>
    val suffix = context.toLowerCase

    val transforms = Seq(
      updateAllSvAppConfigs_(c =>
        c.copy(
          ledgerApiUser = s"${c.ledgerApiUser}-$suffix",
          validatorLedgerApiUser = s"${c.validatorLedgerApiUser}-$suffix",
          svPartyHint = c.svPartyHint.map(sv => s"$sv-$suffix"),
          onboarding = c.onboarding match {
            case Some(foundCollective: SvOnboardingConfig.FoundCollective) =>
              Some(
                foundCollective.copy(
                  svcPartyHint = s"${foundCollective.svcPartyHint}-$suffix"
                )
              )
            case Some(joinWithKey: SvOnboardingConfig.JoinWithKey) => Some(joinWithKey)
            case Some(domainMigration: SvOnboardingConfig.DomainMigration) => Some(domainMigration)
            case None => None
          },
        )
      ),
      updateAllSvAppFoundCollectiveConfigs_(c =>
        c.copy(
          svcPartyHint = s"${c.svcPartyHint}-$suffix"
        )
      ),
      updateAllScanAppConfigs_(c => c.copy(svUser = s"${c.svUser}-$suffix")),
      updateAllValidatorConfigs_(c =>
        c.copy(
          ledgerApiUser = s"${c.ledgerApiUser}-$suffix",
          validatorWalletUser = c.validatorWalletUser.map(u => s"$u-$suffix"),
          appInstances = c.appInstances.view
            .mapValues(i =>
              i.copy(
                serviceUser = s"${i.serviceUser}-$suffix",
                walletUser = i.walletUser.map(u => s"$u-$suffix"),
              )
            )
            .toMap,
        )
      ),
      updateAllWalletAppClientConfigs_(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateAllAppManagerAppClientConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
      updateAllSplitwellAppConfigs_(c => c.copy(providerUser = s"${c.providerUser}-$suffix")),
      updateAllRemoteSplitwellAppConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
      updateAllCnsAppExternalClientConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }

  def reducePollingInterval = setPollingInterval(NonNegativeFiniteDuration.ofSeconds(1))

  def updateAutomationConfig(
      app: ConfigurableApp
  )(transform: AutomationConfigTransform): CNNodeConfigTransform = {
    import ConfigurableApp.*
    app match {
      case Sv => updateAllSvAppConfigs_(c => c.focus(_.automation).modify(transform))
      case Scan => updateAllScanAppConfigs_(c => c.focus(_.automation).modify(transform))
      case Validator => updateAllValidatorConfigs_(c => c.focus(_.automation).modify(transform))
      case Splitwell => updateAllSplitwellAppConfigs_(c => c.focus(_.automation).modify(transform))
    }
  }

  def updateAllAutomationConfigs(transform: AutomationConfigTransform): CNNodeConfigTransform = {
    config =>
      val transforms = Seq(
        updateAllSvAppConfigs_(c => c.focus(_.automation).modify(transform)),
        updateAllScanAppConfigs_(c => c.focus(_.automation).modify(transform)),
        updateAllValidatorConfigs_(c => c.focus(_.automation).modify(transform)),
        updateAllSplitwellAppConfigs_(c => c.focus(_.automation).modify(transform)),
      )
      transforms.foldLeft(config)((c, tf) => tf(c))
  }

  def setPollingInterval(newInterval: NonNegativeFiniteDuration): CNNodeConfigTransform = {
    config =>
      def setPollingIntervalInternal(config: AutomationConfig): AutomationConfig = {
        config.focus(_.pollingInterval).replace(newInterval)
      }

      updateAllAutomationConfigs(setPollingIntervalInternal)(config)
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
  def ensureNovelDamlNames(): CNNodeConfigTransform = { config =>
    val id = (new scala.util.Random).nextInt().toHexString
    addConfigName(id)(addDamlNameSuffix(id)(config))
  }

  /** Default transforms to apply to tests using a [[CNNodeEnvironmentDefinition]].
    * Covers the primary ways that distinct concurrent environments may unintentionally
    * collide, and adds a suffix to Daml user names that is specific to a given test
    * context.
    */
  def defaults(): Seq[CNNodeConfigTransform] = {
    Seq(
      makeAllTimeoutsBounded,
      ensureNovelDamlNames(),
      useSelfSignedTokensForLedgerApiAuth("test"),
      reducePollingInterval,
      withPauseSvDomainComponentsOffboardingTriggers(),
    )
  }

  type CnAppConfigTransform[A] = A => A
  type CnsExternalClientConfigReader = CnAppConfigTransform[CnsAppExternalClientConfig]
  type ValidatorAppTransform = CnAppConfigTransform[ValidatorAppBackendConfig]
  type WalletAppClientTransform = CnAppConfigTransform[WalletAppClientConfig]
  type AppManagerAppClientTransform = CnAppConfigTransform[AppManagerAppClientConfig]
  type ScanAppTransform = CnAppConfigTransform[ScanAppBackendConfig]
  type SplitwellAppTransform = CnAppConfigTransform[SplitwellAppBackendConfig]
  type RemoteSplitwellAppTransform = CnAppConfigTransform[SplitwellAppClientConfig]
  type AutomationConfigTransform = AutomationConfig => AutomationConfig

  def withPauseSvDomainComponentsOffboardingTriggers(): CNNodeConfigTransform =
    updateAutomationConfig(ConfigurableApp.Sv)(
      _.withPausedTrigger[SvOffboardingMediatorTrigger]
        .withPausedTrigger[SvOffboardingSequencerTrigger]
    )

  def setCoinPrice(price: BigDecimal): CNNodeConfigTransform =
    config =>
      Seq(
        updateAllSvAppFoundCollectiveConfigs_(c => c.focus(_.initialCoinPrice).replace(price)),
        updateAllSvAppConfigs_(c => c.focus(_.initialCoinPriceVote).replace(Some(price))),
      ).foldLeft(config)((c, tf) => tf(c))

  def updateAllWalletAppClientConfigs_(
      update: WalletAppClientTransform
  ): CNNodeConfigTransform =
    _.focus(_.walletAppClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllCnsAppExternalClientConfigs_(
      update: CnsExternalClientConfigReader
  ): CNNodeConfigTransform =
    _.focus(_.cnsAppExternalClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllAppManagerAppClientConfigs_(
      update: AppManagerAppClientTransform
  ): CNNodeConfigTransform =
    _.focus(_.appManagerAppClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllScanAppConfigs_(
      update: ScanAppBackendConfig => ScanAppBackendConfig
  ): CNNodeConfigTransform =
    updateAllScanAppConfigs((_, config) => update(config))

  def updateAllScanAppConfigs(
      update: (String, ScanAppBackendConfig) => ScanAppBackendConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.scanApps)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  private def updateAllValidatorAppConfigs(
      update: (String, ValidatorAppBackendConfig) => ValidatorAppBackendConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.validatorApps)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllSvAppConfigs(
      update: (String, SvAppBackendConfig) => SvAppBackendConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.svApps)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllSvAppConfigs_(
      update: SvAppBackendConfig => SvAppBackendConfig
  ): CNNodeConfigTransform =
    updateAllSvAppConfigs((_, config) => update(config))

  def updateAllSvAppFoundCollectiveConfigs_(
      update: SvOnboardingConfig.FoundCollective => SvOnboardingConfig.FoundCollective
  ): CNNodeConfigTransform =
    updateAllSvAppConfigs_(c =>
      c.focus(_.onboarding)
        .modify(_ match {
          case Some(foundCollective: SvOnboardingConfig.FoundCollective) =>
            Some(update(foundCollective))
          case Some(joinWithKey: SvOnboardingConfig.JoinWithKey) => Some(joinWithKey)
          case Some(domainMigration: SvOnboardingConfig.DomainMigration) => Some(domainMigration)
          case None => None
        })
    )

  def noDevNet: CNNodeConfigTransform =
    updateAllSvAppFoundCollectiveConfigs_(_.focus(_.isDevNet).replace(false))

  def updateAllValidatorConfigs(
      update: (String, ValidatorAppBackendConfig) => ValidatorAppBackendConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.validatorApps)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllValidatorConfigs_(
      update: ValidatorAppBackendConfig => ValidatorAppBackendConfig
  ): CNNodeConfigTransform =
    updateAllValidatorConfigs((_, config) => update(config))

  def updateAllValidatorAppConfigs_(
      update: ValidatorAppTransform
  ): CNNodeConfigTransform =
    _.focus(_.validatorApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllAppManagerConfigs_(
      update: AppManagerConfig => AppManagerConfig
  ): CNNodeConfigTransform =
    updateAllValidatorConfigs_(
      _.focus(_.appManager).modify(_.map(update))
    )

  def updateAllSplitwellAppConfigs_(
      update: SplitwellAppTransform
  ): CNNodeConfigTransform =
    _.focus(_.splitwellApps).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllRemoteSplitwellAppConfigs_(
      update: RemoteSplitwellAppTransform
  ): CNNodeConfigTransform =
    _.focus(_.splitwellAppClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateRemoteParticipantConfigs(
      update: (String, RemoteParticipantConfig) => RemoteParticipantConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.remoteParticipants)
        .modify(_.map { case (pName, pConfig) => (pName, update(pName.unwrap, pConfig)) })

  def bumpCantonDomainPortsBy(bump: Int): CNNodeConfigTransform =
    bumpSvAppCantonDomainPortsBy(bump) compose bumpValidatorAppCantonDomainPortsBy(bump)

  def bumpSvAppCantonDomainPortsBy(bump: Int): CNNodeConfigTransform = {
    def bumpUrl(s: String): String = {
      val uri = Uri(s)
      uri.withPort(uri.effectivePort + bump).toString
    }
    updateAllSvAppConfigs_(
      _.focus(_.domains.global.url)
        .modify(bumpUrl(_))
        .focus(_.localDomainNode)
        .modify(
          _.map(d =>
            d.copy(
              sequencer =
                d.sequencer.copy(externalPublicApiUrl = bumpUrl(d.sequencer.externalPublicApiUrl))
            )
          )
        )
    )
  }

  def bumpValidatorAppCantonDomainPortsBy(bump: Int): CNNodeConfigTransform = {
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

  def bumpCantonPortsBy(bump: Int): CNNodeConfigTransform = {

    val transforms = Seq(
      updateAllSvAppConfigs_(
        _.focus(_.participantClient)
          .modify(portTransform(bump, _))
          .focus(_.localDomainNode)
          .modify(_.map(portTransform(bump, _)))
      ),
      updateAllScanAppConfigs_(_.focus(_.participantClient).modify(portTransform(bump, _))),
      updateAllValidatorConfigs_(
        _.focus(_.participantClient)
          .modify(portTransform(bump, _))
      ),
      updateAllSplitwellAppConfigs_(
        _.focus(_.participantClient).modify(portTransform(bump, _))
      ),
    )

    transforms.foldLeft((c: CNNodeConfig) => c)((f, tf) => f compose tf)

  }

  def bumpSomeSvAppPortsBy(bump: Int, svApps: Seq[String]): CNNodeConfigTransform = {
    updateAllSvAppConfigs((name, config) => {
      if (svApps.contains(name)) {
        config
          .focus(_.participantClient)
          .modify(portTransform(bump, _))
          .focus(_.localDomainNode)
          .modify(_.map(portTransform(bump, _)))
          .focus(_.adminApi)
          .modify(portTransform(bump, _))
      } else {
        config
      }
    })
  }

  private def setPortPrefix(range: Int): Port => Port = { port =>
    Port.tryCreate((range * 1000) + port.unwrap % 1000)
  }

  def setSomeSvAppPortsPrefix(range: Int, svApps: Seq[String]): CNNodeConfigTransform = {
    updateAllSvAppConfigs((name, config) => {
      if (svApps.contains(name)) {
        config
          .focus(_.participantClient.ledgerApi.clientConfig.port)
          .modify(setPortPrefix(range))
          .focus(_.participantClient.adminApi.port)
          .modify(setPortPrefix(range))
          .focus(_.localDomainNode)
          .modify(_.map(c => {
            c
              .focus(_.sequencer.internalApi.port)
              .modify(setPortPrefix(range))
              .focus(_.sequencer.adminApi.port)
              .modify(setPortPrefix(range))
              .focus(_.mediator.adminApi.port)
              .modify(setPortPrefix(range))
          }))
          .focus(_.adminApi.internalPort)
          .modify(_.map(setPortPrefix(range)))
      } else {
        config
      }
    })
  }

  def bumpSomeScanAppPortsBy(bump: Int, scanApps: Seq[String]): CNNodeConfigTransform = {
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

  def setSomeScanAppPortsPrefix(range: Int, scanApps: Seq[String]): CNNodeConfigTransform = {
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
      } else {
        config
      }
    })
  }

  def bumpSomeValidatorAppPortsBy(bump: Int, validatorApps: Seq[String]): CNNodeConfigTransform = {
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

  def setSomeValidatorAppPortsPrefix(
      range: Int,
      validatorApps: Seq[String],
  ): CNNodeConfigTransform = {
    updateAllValidatorAppConfigs((name, config) => {
      if (validatorApps.contains(name)) {
        config
          .focus(_.participantClient.ledgerApi.clientConfig.port)
          .modify(setPortPrefix(range))
          .focus(_.participantClient.adminApi.port)
          .modify(setPortPrefix(range))
          .focus(_.adminApi.internalPort)
          .modify(_.map(setPortPrefix(range)))
      } else {
        config
      }
    })
  }

  def bumpRemoteSplitwellPortsBy(bump: Int): CNNodeConfigTransform = {
    updateAllRemoteSplitwellAppConfigs_(
      _.focus(_.participantClient).modify(portTransform(bump, _))
    )
  }

  def bumpSelfHostedParticipantPortsBy(bump: Int): CNNodeConfigTransform = {
    val transforms = Seq(
      updateAllValidatorConfigs { case (name, config) =>
        if (name.startsWith("sv")) config
        else
          config.focus(_.participantClient).modify(portTransform(bump, _))
      }
    )
    transforms.foldLeft((c: CNNodeConfig) => c)((f, tf) => f compose tf)
  }

  private def portTransform(bump: Int, c: CommunityAdminServerConfig): CommunityAdminServerConfig =
    c.copy(internalPort = c.internalPort.map(_ + bump))

  private def portTransform(bump: Int, c: ClientConfig): ClientConfig =
    c.copy(port = c.port + bump)

  private def portTransform(bump: Int, c: CNLedgerApiClientConfig): CNLedgerApiClientConfig =
    c.focus(_.clientConfig).modify(portTransform(bump, _))

  private def portTransform(
      bump: Int,
      c: CNParticipantClientConfig,
  ): CNParticipantClientConfig =
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
      c: SvDomainNodeConfig,
  ): SvDomainNodeConfig =
    c.focus(_.sequencer)
      .modify(portTransform(bump, _))
      .focus(_.mediator)
      .modify(portTransform(bump, _))

  /** Auth-enabled CN apps use self-signed tokens with the given secret for their ledger API connections.
    * Other CN apps use canton admin tokens for their ledger API connections.
    */
  def useSelfSignedTokensForLedgerApiAuth(secret: String): CNNodeConfigTransform = { config =>
    updateAllLedgerApiClientConfigs(
      enableAuth = selfSignedTokenAuthSourceTransform(config.parameters.clock, secret)
    )(config)
  }

  private def updateAllLedgerApiClientConfigs(
      enableAuth: (String, CNLedgerApiClientConfig) => CNLedgerApiClientConfig
  ): CNNodeConfigTransform = { config =>
    val transforms: Seq[CNNodeConfigTransform] = Seq(
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
    transforms.foldLeft(config)((c, tf) => tf(c))
  }

  def selfSignedTokenAuthSourceTransform(clockConfig: ClockConfig, secret: String)(
      user: String,
      c: CNLedgerApiClientConfig,
  ): CNLedgerApiClientConfig = {
    val userToken = AuthUtil.LedgerApi.testToken(user = user, secret = secret)
    c.copy(
      authConfig = AuthTokenSourceConfig.Static(
        userToken,
        getAdminToken(clockConfig, c.clientConfig),
      )
    )
  }

  def useSplitwellUpgradeDomain(): CNNodeConfigTransform =
    updateAllSplitwellAppConfigs_(c => {
      c.copy(
        domains = c.domains.copy(
          splitwell = SplitwellDomains(
            DomainConfig(DomainAlias.tryCreate("splitwellUpgrade")),
            Seq(
              DomainConfig(DomainAlias.tryCreate("splitwell"))
            ),
          )
        )
      )
    })

  def ingestFromParticipantBeginInScan: CNNodeConfigTransform =
    updateAllScanAppConfigs_(c =>
      c.copy(
        ingestFromParticipantBegin = true
      )
    )

  // Disable default domain connections to splitwell to test that the one established by the app manager works
  def disableSplitwellUserDomainConnections: CNNodeConfigTransform =
    updateAllValidatorConfigs(
      { case (name, config) =>
        if (Seq("aliceValidator", "bobValidator").contains(name))
          config.focus(_.domains.extra).replace(Seq.empty)
        else config
      }
    )

  def modifyAllCNStorageConfigs(
      storageConfigModifier: (
          String,
          CNDbConfig,
      ) => CNDbConfig
  ): CNNodeConfigTransform = { config =>
    val transforms: Seq[CNNodeConfigTransform] = Seq(
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
    val tokens: mutable.Map[Int, String] = mutable.Map.empty

    val tokenDataSource = clockConfig match {
      case ClockConfig.RemoteClock(_) => Source.fromFile("canton-simtime.tokens")
      case ClockConfig.WallClock(_) => Source.fromFile("canton.tokens")
      case ClockConfig.SimClock =>
        sys.error(
          "Unexpected clock mode: use remote-clock for simulated time and wall-clock for normal execution"
        )
    }
    for (line <- tokenDataSource.getLines()) {
      val parts = line.split(" ")
      if (parts.length == 2)
        tokens.put(parts(0).toInt, parts(1))
    }
    tokenDataSource.close

    tokens.toMap
  }

  private def getAdminToken(clockConfig: ClockConfig, ledgerApi: ClientConfig): Option[String] = {
    val port = ledgerApi.port.unwrap
    readTokenDataFile(clockConfig).get(port)
  }

}
