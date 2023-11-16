package com.daml.network.console

import akka.actor.ActorSystem
import akka.http.scaladsl.model.BodyPartEntity
import com.daml.network.auth.AuthUtil
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.http.v0.definitions
import com.daml.network.util.NodeIdentitiesDump
import com.daml.network.validator.ValidatorApp
import com.daml.network.validator.admin.api.client.commands.{
  HttpAppManagerAdminAppClient,
  HttpAppManagerAppClient,
  HttpAppManagerPublicAppClient,
  HttpValidatorAdminAppClient,
  HttpValidatorAppClient,
  HttpValidatorPublicAppClient,
  UserInfo,
}
import com.daml.network.validator.config.{
  AppManagerAppClientConfig,
  ValidatorAppBackendConfig,
  ValidatorAppClientConfig,
}
import com.daml.network.wallet.automation.UserWalletAutomationService
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.ByteString

/** Console commands that can be executed either through client or backend reference.
  */
abstract class ValidatorAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override def basePath = "/api/validator"
  override protected val instanceType = "Validator"

  @Help.Summary("Get validator user info")
  @Help.Description("Return the user info of the validator operator")
  def getValidatorUserInfo(): UserInfo = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorPublicAppClient.GetValidatorUserInfo
      )
    }
  }

  @Help.Summary("Get validator party id")
  @Help.Description("Return the party id of the validator operator")
  def getValidatorPartyId(): PartyId =
    getValidatorUserInfo().primaryParty

  @Help.Summary("Onboard a new user")
  @Help.Description("""Onboard individual canton-coin user for the given validator party.
                      |Return the newly set up partyId.""".stripMargin)
  def onboardUser(user: String): PartyId = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.OnboardUser(user)
      )
    }
  }

  @Help.Summary("Register a new user identified by token")
  @Help.Description(
    """Register individual canton-coin user for the given validator party, as identified by token.
      |Return the newly set up partyId.""".stripMargin
  )
  def register(): PartyId = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAppClient.Register
      )
    }
  }

  @Help.Summary("List all onboarded users")
  def listUsers(): Seq[String] = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.ListUsers
      )
    }
  }

  @Help.Summary("Offboard user")
  @Help.Description(
    "Offboards a user from the validator by deleting their wallet install contracts, and closing their wallet automations"
  )
  def offboardUser(username: String) = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.OffboardUser(username)
      )
    }
  }

  @Help.Summary("Export participant identities")
  @Help.Description(
    "Exports participant ID, secret keys, and necessary topology transactions for cloning to a new participant"
  )
  def dumpParticipantIdentities(): NodeIdentitiesDump = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.DumpParticipantIdentities()
      )
    }
  }

  def registerApp(
      providerUserId: String,
      configuration: definitions.AppConfiguration,
      release: BodyPartEntity,
  ): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.RegisterApp(providerUserId, configuration, release)
      )
    }

  def publishAppRelease(provider: PartyId, release: BodyPartEntity): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.PublishAppRelease(provider, release)
      )
    }

  def updateAppConfiguration(provider: PartyId, configuration: definitions.AppConfiguration): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.UpdateAppConfiguration(provider, configuration)
      )
    }

  def listRegisteredApps(): Seq[definitions.RegisteredApp] =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.ListRegisteredApps
      )
    }

  def getLatestAppConfiguration(provider: PartyId): definitions.AppConfiguration =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetLatestAppConfiguration(provider)
      )
    }

  def getLatestAppConfigurationByName(name: String): definitions.AppConfiguration =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetLatestAppConfigurationByName(name)
      )
    }

  def getAppRelease(provider: PartyId, version: String): definitions.AppRelease =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetAppRelease(provider, version)
      )
    }

  def getDarFile(darHash: String): ByteString =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetDarFile(darHash)
      )
    }

  def installApp(manifestUrl: String): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.InstallApp(manifestUrl)
      )
    }

  def approveAppReleaseConfiguration(
      provider: PartyId,
      configurationVersion: Long,
      releaseConfigurationIndex: Int,
  ): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.ApproveAppReleaseConfiguration(
          provider,
          configurationVersion,
          releaseConfigurationIndex,
        )
      )
    }

  def listInstalledApps(): Seq[definitions.InstalledApp] =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.ListInstalledApps
      )
    }

  def oauth2Jwks(): definitions.JwksResponse =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.Oauth2Jwks
      )
    }

  def oauth2OpenIdConfiguration(): definitions.OpenIdConfigurationResponse =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.Oauth2OpenIdConfiguration
      )
    }

  def oauth2Token(
      grantType: String,
      code: String,
      redirectUri: String,
      clientId: String,
  ): definitions.TokenResponse =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.Oauth2Token(grantType, code, redirectUri, clientId)
      )
    }
}

final class ValidatorAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends ValidatorAppReference(consoleEnvironment, name)
    with CNNodeAppBackendReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Local Validator"

  override def token: Option[String] = {
    Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = config.ledgerApiUser,
        secret = AuthUtil.testSecret,
      )
    )
  }

  override def httpClientConfig = NetworkAppClientConfig(
    s"http://127.0.0.1:${config.clientAdminApi.port}"
  )

  val nodes = consoleEnvironment.environment.validators

  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  def appState: ValidatorApp.State = _appState[ValidatorApp.State, ValidatorApp]

  @Help.Summary(
    "Returns the automation service for the wallet of the given user. May only be called while the app is running."
  )
  def userWalletAutomation(userName: String): UserWalletAutomationService = {
    appState.walletManager
      .lookupUserWallet(userName)
      .getOrElse(throw new RuntimeException(s"User ${userName} doesn't exist"))
      .automation
  }

  @Help.Summary("Return local validator app config")
  override def config: ValidatorAppBackendConfig =
    consoleEnvironment.environment.config.validatorsByString(name)

  /** Remote participant this validator app is configured to interact with. */
  lazy val participantClient =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this validator app is configured to interact with. Uses admin tokens to bypass auth. */
  val participantClientWithAdminToken =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}

/** Client (aka remote) reference to a validator app in the style of CNParticipantClientReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final case class ValidatorAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
    val config: ValidatorAppClientConfig,
    override val token: Option[String] = None,
) extends ValidatorAppReference(consoleEnvironment, name)
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "Validator Client"
}

final case class AppManagerAppClientReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
    val config: AppManagerAppClientConfig,
) extends HttpCNNodeAppReference
    with BaseInspection[ParticipantNode] {

  override def basePath = "/api/validator"
  override def httpClientConfig = config.adminApi

  override def token: Option[String] = {
    Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = config.ledgerApiUser,
        secret = AuthUtil.testSecret,
      )
    )
  }

  def authorizeApp(
      provider: PartyId
  ): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.AuthorizeApp(provider)
      )
    }

  def checkAppAuthorized(
      provider: PartyId,
      redirectUri: String,
      state: String,
  ): String =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.CheckAppAuthorized(provider, redirectUri, state)
      )
    }

  override protected val instanceType = "App Manager User Client"
}
