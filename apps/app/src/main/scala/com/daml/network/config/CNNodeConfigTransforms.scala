package com.daml.network.config

import com.daml.network.auth.AuthUtil
import com.daml.network.directory.config.{DirectoryAppBackendConfig, DirectoryAppClientConfig}
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.splitwell.config.{
  SplitwellAppBackendConfig,
  SplitwellAppClientConfig,
  SplitwellDomains,
}
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.validator.config.{ValidatorAppBackendConfig, ValidatorAppClientConfig}
import com.daml.network.wallet.config.WalletAppClientConfig
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.domain.config.CommunityDomainConfig
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import monocle.macros.syntax.lens.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.io.Source

object CNNodeConfigTransforms {

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
      updateSvcAppConfig(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateAllSvAppConfigs_(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateScanAppConfig(c => c.copy(svcUser = s"${c.svcUser}-$suffix")),
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
      updateDirectoryAppConfig(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateAllSplitwellAppConfigs_(c => c.copy(providerUser = s"${c.providerUser}-$suffix")),
      updateAllRemoteSplitwellAppConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
      updateAllDirectoryAppClientConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }

  def reducePollingInterval = setPollingInterval(NonNegativeFiniteDuration.ofSeconds(1))

  def updateAllAutomationConfigs(transform: AutomationConfigTransform): CNNodeConfigTransform = {
    config =>
      val transforms = Seq(
        updateSvcAppConfig(c => c.focus(_.automation).modify(transform)),
        updateAllSvAppConfigs_(c => c.focus(_.automation).modify(transform)),
        updateScanAppConfig(c => c.focus(_.automation).modify(transform)),
        updateAllValidatorConfigs_(c => c.focus(_.automation).modify(transform)),
        updateDirectoryAppConfig(c => c.focus(_.automation).modify(transform)),
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

  def onlySv1: (String, CNNodeConfig) => CNNodeConfig =
    (_, c) =>
      c.copy(
        svApps = c.svApps.filter(_._1 == InstanceName.tryCreate("sv1")),
        svAppClients = c.svAppClients.filter(_._1 == InstanceName.tryCreate("sv1")),
        validatorApps = c.validatorApps.filter { case (name, _) =>
          !name.toProtoPrimitive.startsWith("sv") || name == InstanceName.tryCreate("sv1Validator")
        },
      )

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
    )
  }

  type CnAppConfigTransform[A] = A => A
  type DirectoryAppTransform = CnAppConfigTransform[DirectoryAppBackendConfig]
  type DirectoryClientConfigReader = CnAppConfigTransform[DirectoryAppClientConfig]
  type ValidatorAppTransform = CnAppConfigTransform[ValidatorAppBackendConfig]
  type WalletAppClientTransform = CnAppConfigTransform[WalletAppClientConfig]
  type SvcAppTransform = CnAppConfigTransform[SvcAppBackendConfig]
  type ScanAppTransform = CnAppConfigTransform[ScanAppBackendConfig]
  type SplitwellAppTransform = CnAppConfigTransform[SplitwellAppBackendConfig]
  type RemoteSplitwellAppTransform = CnAppConfigTransform[SplitwellAppClientConfig]
  type AutomationConfigTransform = AutomationConfig => AutomationConfig

  def setCoinPrice(price: BigDecimal): CNNodeConfigTransform =
    config =>
      Seq(
        updateSvcAppConfig(c => c.focus(_.coinPrice).replace(price)),
        updateAllSvAppFoundCollectiveConfigs_(c => c.focus(_.initialCoinPrice).replace(price)),
        updateAllSvAppConfigs_(c => c.focus(_.initialCoinPriceVote).replace(Some(price))),
      ).foldLeft(config)((c, tf) => tf(c))

  def updateDirectoryAppConfig(update: DirectoryAppTransform): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.directoryApp)
        .replace(cantonConfig.directoryApp match {
          case None => None
          case Some(directoryApp) => Some(update(directoryApp))
        })

  def updateAllDirectoryAppClientConfigs_(
      update: DirectoryClientConfigReader
  ): CNNodeConfigTransform =
    _.focus(_.directoryAppClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllWalletAppClientConfigs_(
      update: WalletAppClientTransform
  ): CNNodeConfigTransform =
    _.focus(_.walletAppClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateScanAppConfig(update: ScanAppTransform): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.scanApp)
        .replace(cantonConfig.scanApp match {
          case None => None
          case Some(scan) => Some(update(scan))
        })

  def updateSvcAppConfig(update: SvcAppTransform): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.svcApp)
        .replace(cantonConfig.svcApp match {
          case None => None
          case Some(svcApp) => Some(update(svcApp))
        })

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
          case found: SvOnboardingConfig.FoundCollective => update(found)
          case other => other
        })
    )

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

  def updateAllValidatorClientConfigs(
      update: (String, ValidatorAppClientConfig) => ValidatorAppClientConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.validatorAppClients)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllValidatorClientConfigs_(
      update: ValidatorAppClientConfig => ValidatorAppClientConfig
  ): CNNodeConfigTransform =
    updateAllValidatorClientConfigs((_, config) => update(config))

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

  def updateAllDomainConfigs(
      update: (String, CommunityDomainConfig) => CommunityDomainConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.domains)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllDomainConfigs_(
      update: CommunityDomainConfig => CommunityDomainConfig
  ): CNNodeConfigTransform =
    updateAllDomainConfigs((_, config) => update(config))

  def updateRemoteParticipantConfigs_(
      update: RemoteParticipantConfig => RemoteParticipantConfig
  ): CNNodeConfigTransform =
    updateRemoteParticipantConfigs((_, config) => update(config))

  def updateRemoteParticipantConfigs(
      update: (String, RemoteParticipantConfig) => RemoteParticipantConfig
  ): CNNodeConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.remoteParticipants)
        .modify(_.map { case (pName, pConfig) => (pName, update(pName.unwrap, pConfig)) })

  def bumpCantonPortsBy(bump: Int): CNNodeConfigTransform = {

    val transforms = Seq(
      updateSvcAppConfig(_.focus(_.participantClient).modify(portTransform(bump, _))),
      updateAllSvAppConfigs_(_.focus(_.participantClient).modify(portTransform(bump, _))),
      updateScanAppConfig(_.focus(_.participantClient).modify(portTransform(bump, _))),
      updateAllValidatorConfigs_(
        _.focus(_.participantClient).modify(portTransform(bump, _))
      ),
      updateDirectoryAppConfig(
        _.focus(_.participantClient).modify(portTransform(bump, _))
      ),
      updateAllSplitwellAppConfigs_(
        _.focus(_.participantClient).modify(portTransform(bump, _))
      ),
    )

    transforms.foldLeft((c: CNNodeConfig) => c)((f, tf) => f compose tf)

  }

  def bumpDirectoryClientsPortsBy(bump: Int): CNNodeConfigTransform = {
    updateAllDirectoryAppClientConfigs_(
      _.focus(_.ledgerApi).modify(portTransform(bump, _))
    )
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
      updateSvcAppConfig(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllSvAppConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateScanAppConfig(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.svcUser, _))
      }),
      updateDirectoryAppConfig(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllDirectoryAppClientConfigs_(c => {
        c.focus(_.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
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

  private def selfSignedTokenAuthSourceTransform(clockConfig: ClockConfig, secret: String)(
      user: String,
      c: CNLedgerApiClientConfig,
  ): CNLedgerApiClientConfig = {
    val userToken = AuthUtil.LedgerApi.testToken(user = user, secret = secret)
    val adminToken = getAdminToken(clockConfig, c.clientConfig)
    c.copy(
      authConfig = AuthTokenSourceConfig.Static(userToken, Some(adminToken))
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
      tokens.put(parts(0).toInt, parts(1))
    }
    tokenDataSource.close

    tokens.toMap
  }

  def getAdminToken(clockConfig: ClockConfig, config: CNParticipantClientConfig): String = {
    getAdminToken(clockConfig, config.ledgerApi.clientConfig)
  }

  def getAdminToken(clockConfig: ClockConfig, ledgerApi: ClientConfig): String = {
    val port = ledgerApi.port.unwrap
    val token = {
      readTokenDataFile(clockConfig).getOrElse(
        port,
        sys.error(s"No admin token found for ledger API at port $port"),
      )
    }
    token
  }
}
