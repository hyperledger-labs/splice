package com.daml.network.config

import com.daml.network.auth.AuthUtil
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.splitwise.config.{LocalSplitwiseAppConfig, RemoteSplitwiseAppConfig}
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.wallet.config.{WalletAppBackendConfig, WalletAppClientConfig}
import com.digitalasset.canton.config.RequireTypes.NonEmptyString
import com.digitalasset.canton.config.*
import com.digitalasset.canton.domain.config.{
  CommunityDomainConfig,
  CommunityPublicServerConfig,
  RemoteDomainConfig,
}
import com.digitalasset.canton.participant.config.{
  AuthServiceConfig,
  CommunityParticipantConfig,
  LedgerApiServerConfig,
  RemoteParticipantConfig,
}
import com.digitalasset.canton.time
import monocle.macros.syntax.lens.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.io.Source

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

  def addConfigName(context: String): CoinConfigTransform = { config =>
    config.copy(name = Some(context))
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
      val suffix = context.toLowerCase

      val config1 = updateSvcAppConfig(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(config)
      val config2 = updateScanAppConfig(c => c.copy(svcUser = s"${c.svcUser}-$suffix"))(config1)
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
        updateAllWalletAppBackendConfigs_(c => c.copy(serviceUser = s"${c.serviceUser}-$suffix"))(
          config3
        )
      val config5 =
        updateAllWalletAppClientConfigs_(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(
          config4
        )
      val config6 =
        updateDirectoryAppConfig(c => c.copy(damlUser = s"${c.damlUser}-$suffix"))(
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

  def reducePollingInterval: CoinConfigTransform = { config =>
    {
      val config1 =
        updateSvcAppConfig(c => c.focus(_.automation).modify(reducePollingInterval))(config)
      val config2 =
        updateScanAppConfig(c => c.focus(_.automation).modify(reducePollingInterval))(config1)
      val config3 = updateAllValidatorConfigs_(c =>
        c.focus(_.automation).modify(reducePollingInterval)
      )(config2)
      val config4 =
        updateAllWalletAppBackendConfigs_(c => c.focus(_.automation).modify(reducePollingInterval))(
          config3
        )
      val config5 =
        updateDirectoryAppConfig(c => c.focus(_.automation).modify(reducePollingInterval))(config4)
      val config6 =
        updateAllSplitwiseAppConfigs_(c => c.focus(_.automation).modify(reducePollingInterval))(
          config5
        )
      config6
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
    val id = (new scala.util.Random).nextInt().toHexString
    addConfigName(id)(addDamlNameSuffix(id)(config))
  }

  /** Default transforms to apply to tests using a [[CoinEnvironmentDefinition]].
    * Covers the primary ways that distinct concurrent environments may unintentionally
    * collide, and adds a suffix to Daml user names that is specific to a given test
    * context.
    */
  def defaults(testContextNameSuffix: String): Seq[CoinConfigTransform] = {
    Seq(
      makeAllTimeoutsBounded,
      ensureNovelDamlNames(),
      enableLedgerApiAuthForLocalParticipants("test"),
      useSelfSignedTokensForLedgerApiAuth("test"),
      reducePollingInterval,
    )
  }

  type CnAppConfigTransform[A <: NodeConfig] = A => A
  type DirectoryAppTransform = CnAppConfigTransform[LocalDirectoryAppConfig]
  type RemoteDirectoryAppTransform = CnAppConfigTransform[RemoteDirectoryAppConfig]
  type ValidatorAppTransform = CnAppConfigTransform[LocalValidatorAppConfig]
  type WalletAppBackendTransform = CnAppConfigTransform[WalletAppBackendConfig]
  type WalletAppClientTransform = CnAppConfigTransform[WalletAppClientConfig]
  type SvcAppTransform = CnAppConfigTransform[LocalSvcAppConfig]
  type ScanAppTransform = CnAppConfigTransform[LocalScanAppConfig]
  type SplitwiseAppTransform = CnAppConfigTransform[LocalSplitwiseAppConfig]
  type RemoteSplitwiseAppTransform = CnAppConfigTransform[RemoteSplitwiseAppConfig]
  type AutomationConfigTransform = AutomationConfig => AutomationConfig

  def reducePollingInterval(config: AutomationConfig): AutomationConfig =
    config.focus(_.pollingInterval).replace(time.NonNegativeFiniteDuration.ofSeconds(1))

  def updateDirectoryAppConfig(update: DirectoryAppTransform): CoinConfigTransform =
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

  def updateAllWalletAppBackendConfigs_(
      update: WalletAppBackendTransform
  ): CoinConfigTransform =
    _.focus(_.walletAppBackends).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllWalletAppClientConfigs_(
      update: WalletAppClientTransform
  ): CoinConfigTransform =
    _.focus(_.walletAppClients).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateAllAppConfigs_(
      update: WalletAppBackendTransform
  ): CoinConfigTransform =
    _.focus(_.walletAppBackends).modify(_.map { case (name, config) =>
      (name, update(config))
    })

  def updateScanAppConfig(update: ScanAppTransform): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.scanApp)
        .replace(cantonConfig.scanApp match {
          case None => None
          case Some(scan) => Some(update(scan))
        })

  def updateSvcAppConfig(update: SvcAppTransform): CoinConfigTransform =
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

  def updateRemoteParticipantConfigs_(
      update: RemoteParticipantConfig => RemoteParticipantConfig
  ): CoinConfigTransform =
    updateRemoteParticipantConfigs((_, config) => update(config))

  def updateRemoteParticipantConfigs(
      update: (String, RemoteParticipantConfig) => RemoteParticipantConfig
  ): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.remoteParticipants)
        .modify(_.map { case (pName, pConfig) => (pName, update(pName.unwrap, pConfig)) })

  def updateAllParticipantConfigs_(
      update: CommunityParticipantConfig => CommunityParticipantConfig
  ): CoinConfigTransform =
    updateAllParticipantConfigs((_, config) => update(config))

  def bumpCantonPortsBy(bump: Int): CoinConfigTransform = {
    val domain = updateAllDomainConfigs_(
      _.focus(_.adminApi)
        .modify(portTransform(bump, _))
        .focus(_.publicApi)
        .modify(portTransform(bump, _))
    )
    val participant = updateAllParticipantConfigs_(
      _.focus(_.adminApi)
        .modify(portTransform(bump, _))
        .focus(_.ledgerApi)
        .modify(portTransform(bump, _))
    )
    val svc = updateSvcAppConfig(_.focus(_.remoteParticipant).modify(portTransform(bump, _)))
    val scan = updateScanAppConfig(_.focus(_.remoteParticipant).modify(portTransform(bump, _)))
    val validator = updateAllValidatorConfigs_(
      _.focus(_.remoteParticipant).modify(portTransform(bump, _))
    )
    val wallet = updateAllWalletAppBackendConfigs_(
      _.focus(_.remoteParticipant).modify(portTransform(bump, _))
    )
    val directory = updateDirectoryAppConfig(
      _.focus(_.remoteParticipant).modify(portTransform(bump, _))
    )
    val splitwise = updateAllSplitwiseAppConfigs_(
      _.focus(_.remoteParticipant).modify(portTransform(bump, _))
    )

    domain compose participant compose svc compose scan compose validator compose
      wallet compose directory compose splitwise
  }

  def bumpRemoteDirectoryPortsBy(bump: Int): CoinConfigTransform = {
    updateAllRemoteDirectoryAppConfigs_(
      _.focus(_.ledgerApi).modify(portTransform(bump, _))
    )
  }

  def bumpSvcParticipantPortsBy(bump: Int): CoinConfigTransform = {
    val participant = updateAllParticipantConfigs { case (name, conf) =>
      if (name == "svc_participant") {
        conf
          .focus(_.adminApi)
          .modify(portTransform(bump, _))
          .focus(_.ledgerApi)
          .modify(portTransform(bump, _))
      } else conf
    }
    val svc = updateSvcAppConfig(_.focus(_.remoteParticipant).modify(portTransform(bump, _)))
    val scan = updateScanAppConfig(_.focus(_.remoteParticipant).modify(portTransform(bump, _)))
    participant compose svc compose scan
  }

  private def portTransform(bump: Int, c: CommunityAdminServerConfig): CommunityAdminServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p + bump))
  private def portTransform(
      bump: Int,
      c: CommunityPublicServerConfig,
  ): CommunityPublicServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p + bump))
  private def portTransform(bump: Int, c: ClientConfig): ClientConfig =
    c.copy(port = c.port + bump)
  private def portTransform(bump: Int, c: CoinLedgerApiClientConfig): CoinLedgerApiClientConfig =
    c.focus(_.clientConfig).modify(portTransform(bump, _))
  private def portTransform(bump: Int, c: LedgerApiServerConfig): LedgerApiServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p + bump))
  private def portTransform(
      bump: Int,
      c: CoinRemoteParticipantConfig,
  ): CoinRemoteParticipantConfig =
    c.focus(_.adminApi)
      .modify(portTransform(bump, _))
      .focus(_.ledgerApi)
      .modify(portTransform(bump, _))

  /** All local participants require auth tokens for their ledger API.
    * The tokens are expected to by signed by hmac256 with the given secret.
    */
  def enableLedgerApiAuthForLocalParticipants(secret: String): CoinConfigTransform =
    updateAllParticipantConfigs { case (_, c) =>
      c.focus(_.ledgerApi.authServices)
        .replace(Seq(AuthServiceConfig.UnsafeJwtHmac256(NonEmptyString.tryCreate(secret))))
    }

  /** Auth-enabled CN apps use self-signed tokens with the given secret for their ledger API connections.
    * Other CN apps use canton admin tokens for their ledger API connections.
    */
  def useSelfSignedTokensForLedgerApiAuth(secret: String): CoinConfigTransform = { config =>
    updateAllLedgerApiClientConfigs(
      readyForAuth = selfSignedTokenAuthSourceTransform(config.parameters.clock, secret),
      notReadyForAuth = adminTokenAuthSourceTransform(config.parameters.clock),
    )(config)
  }

  // TODO(#1627): Remove notReadyForAuth once all apps are auth-ready
  private def updateAllLedgerApiClientConfigs(
      readyForAuth: (String, CoinLedgerApiClientConfig) => CoinLedgerApiClientConfig,
      notReadyForAuth: (String, CoinLedgerApiClientConfig) => CoinLedgerApiClientConfig,
  ): CoinConfigTransform = { config =>
    val transforms: Seq[CoinConfigTransform] = Seq(
      // Validator and wallet apps are ready for auth:
      // - all of their ledger API commands are submitted by the party associated with the app service user
      // - they only read data for parties for which their service user has readAd rights
      // - they don't use admin endpoints unless their service user has admin rights (validator app only)
      updateAllValidatorConfigs_(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(readyForAuth(c.damlUser, _))
      }),
      updateAllWalletAppBackendConfigs_(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(readyForAuth(c.serviceUser, _))
      }),
      // Other apps may not be ready for auth
      updateSvcAppConfig(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(notReadyForAuth(c.damlUser, _))
      }),
      updateScanAppConfig(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(notReadyForAuth(c.svcUser, _))
      }),
      updateDirectoryAppConfig(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(notReadyForAuth(c.damlUser, _))
      }),
      updateAllRemoteDirectoryAppConfigs_(c => {
        c.focus(_.ledgerApi).modify(notReadyForAuth(c.damlUser, _))
      }),
      updateAllSplitwiseAppConfigs_(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(notReadyForAuth(c.providerUser, _))
      }),
      updateAllRemoteSplitwiseAppConfigs_(c => {
        c.focus(_.ledgerApi).modify(notReadyForAuth(c.damlUser, _))
      }),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }

  private def adminTokenAuthSourceTransform(clockConfig: ClockConfig)(
      _user: String,
      c: CoinLedgerApiClientConfig,
  ): CoinLedgerApiClientConfig = {
    val adminToken = getAdminToken(clockConfig, c.clientConfig)
    c.copy(
      authConfig = AuthTokenSourceConfig.Static(adminToken, Some(adminToken))
    )
  }

  private def selfSignedTokenAuthSourceTransform(clockConfig: ClockConfig, secret: String)(
      user: String,
      c: CoinLedgerApiClientConfig,
  ): CoinLedgerApiClientConfig = {
    val userToken = AuthUtil.LedgerApi.testToken(user = user, secret = secret)
    val adminToken = getAdminToken(clockConfig, c.clientConfig)
    c.copy(
      authConfig = AuthTokenSourceConfig.Static(userToken, Some(adminToken))
    )
  }

  /** Canton has a built in authorizer that accepts "canton admin tokens",
    * see [[com.digitalasset.canton.participant.ledger.api.CantonAdminTokenAuthService]]
    * These are 128 character random strings (not JWTs), generated independently for each local participant node at canton startup.
    * Attaching an admin token to a ledger API request allows you to bypass auth, i.e., to act as any party and perform all admin operations.
    * There is (intentionally) no way of getting the admin tokens from an external canton process,
    * so we export them to a file in our canton bootstrap script (see `bootstrap-canton.canton`).
    */
  private def readTokenDataFile(clockConfig: ClockConfig): Map[Int, String] = {
    val tokens: mutable.Map[Int, String] = mutable.Map.empty

    val tokenDataSource = clockConfig match {
      case ClockConfig.SimClock => Source.fromFile("canton-simtime.tokens")
      case _ => Source.fromFile("canton.tokens")
    }
    for (line <- tokenDataSource.getLines()) {
      val parts = line.split(" ")
      tokens.put(parts(0).toInt, parts(1))
    }
    tokenDataSource.close

    tokens.toMap
  }

  def getAdminToken(clockConfig: ClockConfig, config: CoinRemoteParticipantConfig): String = {
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

  /** This transforms canton configs, not coin configs, but we need it for the Canton coin project:
    * We use a long running canton process that is running in the background.
    * Sometimes you want to attach a Canton console to that process for debugging.
    * Since the canton process uses an authenticated ledger API, we need a way of automatically
    * generating a config that contains the right access tokens.
    */
  def remoteCantonConfigWithAdminTokens: CantonCommunityConfig => CantonCommunityConfig = {
    config: CantonCommunityConfig =>
      {
        config.copy(
          domains = Map.empty,
          participants = Map.empty,
          remoteDomains = config.domains.view
            .mapValues(d =>
              RemoteDomainConfig(
                adminApi = d.adminApi.clientConfig,
                publicApi = SequencerConnectionConfig.Grpc(d.publicApi.address, d.publicApi.port),
              )
            )
            .toMap,
          remoteParticipants = config.participants.view
            .mapValues(p =>
              RemoteParticipantConfig(
                adminApi = p.adminApi.clientConfig,
                ledgerApi = p.ledgerApi.clientConfig,
                token = Some(getAdminToken(config.parameters.clock, p.ledgerApi.clientConfig)),
              )
            )
            .toMap,
          features = config.features.copy(enableTestingCommands = true),
        )
      }
  }
}
