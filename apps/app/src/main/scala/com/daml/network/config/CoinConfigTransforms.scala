package com.daml.network.config

import com.digitalasset.canton.DomainAlias
import com.daml.network.auth.AuthUtil
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.splitwise.config.{SplitwiseAppBackendConfig, SplitwiseAppClientConfig}
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.validator.config.ValidatorAppBackendConfig
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
    * val validatorUserName = validatorApp.config.ledgerApiUser
    * // validatorUserName will have the name with the suffix applied
    * val validatorParty = validatorParticipant.parties.enable(validatorUserName)
    */
  def addDamlNameSuffix(context: String): CoinConfigTransform = { config =>
    val suffix = context.toLowerCase

    val transforms = Seq(
      updateSvcAppConfig(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateAllSvAppConfigs_(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateScanAppConfig(c => c.copy(svcUser = s"${c.svcUser}-$suffix")),
      updateAllValidatorConfigs_(c =>
        c.copy(
          ledgerApiUser = s"${c.ledgerApiUser}-$suffix",
          walletServiceUser = s"${c.walletServiceUser}-$suffix",
          appInstances = c.appInstances.view
            .mapValues(i =>
              i.copy(
                serviceUser = i.serviceUser.map(u => s"${u}-$suffix"),
                walletUser = s"${i.walletUser}-$suffix",
              )
            )
            .toMap,
        )
      ),
      updateAllWalletAppBackendConfigs_(c => c.copy(serviceUser = s"${c.serviceUser}-$suffix")),
      updateAllWalletAppClientConfigs_(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateDirectoryAppConfig(c => c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")),
      updateAllSplitwiseAppConfigs_(c => c.copy(providerUser = s"${c.providerUser}-$suffix")),
      updateAllRemoteSplitwiseAppConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
      updateAllRemoteDirectoryAppConfigs_(c =>
        c.copy(ledgerApiUser = s"${c.ledgerApiUser}-$suffix")
      ),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }

  def reducePollingInterval = setPollingInterval(time.NonNegativeFiniteDuration.ofSeconds(1))

  def updateAllAutomationConfigs(transform: AutomationConfigTransform): CoinConfigTransform = {
    config =>
      val transforms = Seq(
        updateSvcAppConfig(c => c.focus(_.automation).modify(transform)),
        updateAllSvAppConfigs_(c => c.focus(_.automation).modify(transform)),
        updateScanAppConfig(c => c.focus(_.automation).modify(transform)),
        updateAllValidatorConfigs_(c => c.focus(_.automation).modify(transform)),
        updateAllWalletAppBackendConfigs_(c => c.focus(_.automation).modify(transform)),
        updateDirectoryAppConfig(c => c.focus(_.automation).modify(transform)),
        updateAllSplitwiseAppConfigs_(c => c.focus(_.automation).modify(transform)),
      )
      transforms.foldLeft(config)((c, tf) => tf(c))
  }

  def setPollingInterval(newInterval: time.NonNegativeFiniteDuration): CoinConfigTransform = {
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
      useSelfSignedTokensForWalletValidatorApiAuth("test"),
      reducePollingInterval,
    )
  }

  type CnAppConfigTransform[A <: NodeConfig] = A => A
  type DirectoryAppTransform = CnAppConfigTransform[LocalDirectoryAppConfig]
  type RemoteDirectoryAppTransform = CnAppConfigTransform[RemoteDirectoryAppConfig]
  type ValidatorAppTransform = CnAppConfigTransform[ValidatorAppBackendConfig]
  type WalletAppBackendTransform = CnAppConfigTransform[WalletAppBackendConfig]
  type WalletAppClientTransform = CnAppConfigTransform[WalletAppClientConfig]
  type SvcAppTransform = CnAppConfigTransform[SvcAppBackendConfig]
  type ScanAppTransform = CnAppConfigTransform[ScanAppBackendConfig]
  type SplitwiseAppTransform = CnAppConfigTransform[SplitwiseAppBackendConfig]
  type RemoteSplitwiseAppTransform = CnAppConfigTransform[SplitwiseAppClientConfig]
  type AutomationConfigTransform = AutomationConfig => AutomationConfig

  def setCoinPrice(price: BigDecimal): CoinConfigTransform =
    config =>
      Seq(
        updateSvcAppConfig(c => c.focus(_.coinPrice).replace(price)),
        updateAllSvAppConfigs_(c => c.focus(_.coinPrice).replace(price)),
      ).foldLeft(config)((c, tf) => tf(c))

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

  def updateAllSvAppConfigs(
      update: (String, LocalSvAppConfig) => LocalSvAppConfig
  ): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.svApps)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllSvAppConfigs_(
      update: LocalSvAppConfig => LocalSvAppConfig
  ): CoinConfigTransform =
    updateAllSvAppConfigs((_, config) => update(config))

  def updateAllValidatorConfigs(
      update: (String, ValidatorAppBackendConfig) => ValidatorAppBackendConfig
  ): CoinConfigTransform =
    cantonConfig =>
      cantonConfig
        .focus(_.validatorApps)
        .modify(_.map { case (dName, dConfig) => (dName, update(dName.unwrap, dConfig)) })

  def updateAllValidatorConfigs_(
      update: ValidatorAppBackendConfig => ValidatorAppBackendConfig
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
    _.focus(_.splitwiseAppClients).modify(_.map { case (name, config) =>
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

    val transforms = Seq(
      updateAllDomainConfigs_(
        _.focus(_.adminApi)
          .modify(portTransform(bump, _))
          .focus(_.publicApi)
          .modify(portTransform(bump, _))
      ),
      updateAllParticipantConfigs_(
        _.focus(_.adminApi)
          .modify(portTransform(bump, _))
          .focus(_.ledgerApi)
          .modify(portTransform(bump, _))
      ),
      updateSvcAppConfig(_.focus(_.remoteParticipant).modify(portTransform(bump, _))),
      updateAllSvAppConfigs_(_.focus(_.remoteParticipant).modify(portTransform(bump, _))),
      updateScanAppConfig(_.focus(_.remoteParticipant).modify(portTransform(bump, _))),
      updateAllValidatorConfigs_(
        _.focus(_.remoteParticipant).modify(portTransform(bump, _))
      ),
      updateAllWalletAppBackendConfigs_(
        _.focus(_.remoteParticipant).modify(portTransform(bump, _))
      ),
      updateDirectoryAppConfig(
        _.focus(_.remoteParticipant).modify(portTransform(bump, _))
      ),
      updateAllSplitwiseAppConfigs_(
        _.focus(_.remoteParticipant).modify(portTransform(bump, _))
      ),
    )

    transforms.foldLeft((c: CoinConfig) => c)((f, tf) => f compose tf)

  }

  def bumpRemoteDirectoryPortsBy(bump: Int): CoinConfigTransform = {
    updateAllRemoteDirectoryAppConfigs_(
      _.focus(_.ledgerApi).modify(portTransform(bump, _))
    )
  }

  def bumpRemoteSplitwisePortsBy(bump: Int): CoinConfigTransform = {
    updateAllRemoteSplitwiseAppConfigs_(
      _.focus(_.ledgerApi).modify(portTransform(bump, _))
    )
  }

  def bumpSelfHostedParticipantPortsBy(bump: Int): CoinConfigTransform = {
    val transforms = Seq(
      updateAllWalletAppBackendConfigs_(
        _.focus(_.remoteParticipant).modify(portTransform(bump, _))
      ),
      updateAllValidatorConfigs_(
        _.focus(_.remoteParticipant).modify(portTransform(bump, _))
      ),
    )
    transforms.foldLeft((c: CoinConfig) => c)((f, tf) => f compose tf)
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
      enableAuth = selfSignedTokenAuthSourceTransform(config.parameters.clock, secret)
    )(config)
  }

  private def updateAllLedgerApiClientConfigs(
      enableAuth: (String, CoinLedgerApiClientConfig) => CoinLedgerApiClientConfig
  ): CoinConfigTransform = { config =>
    val transforms: Seq[CoinConfigTransform] = Seq(
      updateAllValidatorConfigs_(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllWalletAppBackendConfigs_(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(enableAuth(c.serviceUser, _))
      }),
      updateSvcAppConfig(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllSvAppConfigs_(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateScanAppConfig(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(enableAuth(c.svcUser, _))
      }),
      updateDirectoryAppConfig(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllRemoteDirectoryAppConfigs_(c => {
        c.focus(_.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      updateAllSplitwiseAppConfigs_(c => {
        c.focus(_.remoteParticipant.ledgerApi).modify(enableAuth(c.providerUser, _))
      }),
      updateAllRemoteSplitwiseAppConfigs_(c => {
        c.focus(_.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
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

  def useSelfSignedTokensForWalletValidatorApiAuth(
      secret: String
  ): CoinConfigTransform = {
    updateAllWalletAppBackendConfigs_(c => {
      val userToken = AuthUtil.testToken(AuthUtil.testAudience, c.serviceUser)
      c.copy(validatorAuth = AuthTokenSourceConfig.Static(userToken, None))
    })
  }

  def useSeparateSplitwiseDomain(): CoinConfigTransform =
    updateAllSplitwiseAppConfigs_(c =>
      c.focus(_.domains.splitwise).replace(DomainAlias.tryCreate("splitwise"))
    )

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
