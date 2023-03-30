package com.daml.network.console

import com.daml.network.admin.api.client.HttpAdminAppClient
import com.daml.network.auth.AuthUtil
import com.daml.network.config.CNHttpClientConfig
import com.daml.network.environment.{CNNodeStatus, CNNodeConsoleEnvironment}
import com.daml.network.validator.admin.api.client.UserInfo
import com.daml.network.validator.admin.api.client.commands.{
  HttpValidatorAppClient,
  HttpValidatorAdminAppClient,
}
import com.daml.network.validator.config.{ValidatorAppBackendConfig, ValidatorAppClientConfig}
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.console.commands.TopologyAdministrationGroup
import com.digitalasset.canton.console.ConsoleMacros
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.console.ConsoleCommandResult

/** Console commands that can be executed either through client or backend reference.
  */
abstract class ValidatorAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override protected val instanceType = "Validator"

  @Help.Summary("Get validator user info")
  @Help.Description("Return the user info of the validator operator")
  def getValidatorUserInfo(): UserInfo = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAppClient.GetValidatorUserInfo
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

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  @Help.Summary("Health and diagnostic related commands (HTTP)")
  @Help.Group("HTTP Health")
  def httpHealth = {
    consoleEnvironment.run {
      // Map failing HTTP requests to a failed NodeStatus if the status endpoint isn't up yet (e.g. slow app initialization)
      // TODO(#3467) see if we still need this after the initialization order is fixed
      ConsoleCommandResult.fromEither(
        Right(
          httpCommand(
            HttpAdminAppClient.GetHealthStatus[CNNodeStatus](CNNodeStatus.fromJsonV0)
          ).toEither.fold(err => NodeStatus.Failure(err), success => success)
        )
      )
    }
  }

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  // Override topology to avoid using grpc status check
  private lazy val topology_ =
    new TopologyAdministrationGroup(
      this,
      None,
      consoleEnvironment,
      loggerFactory,
    )
  @Help.Summary("Topology management related commands")
  @Help.Group("Topology")
  @Help.Description(
    "This group contains access to the full set of topology management commands."
  )
  override def topology: TopologyAdministrationGroup = topology_

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  override def waitForInitialization(
      timeout: NonNegativeDuration = cnNodeConsoleEnvironment.commandTimeouts.bounded
  ): Unit =
    ConsoleMacros.utils.retry_until_true(timeout)(
      httpHealth.successOption.map(_.active).getOrElse(false)
    )
}

final class ValidatorAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
) extends ValidatorAppReference(consoleEnvironment, name)
    with LocalCNNodeAppReference
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

  override def httpClientConfig = CNHttpClientConfig.fromClientConfig(
    // For local references, we assume that they are reachable on localhost.
    // TODO (#2019) Reconsider if we want these for local refs at all and if so
    // if we should specify a url here.
    s"http://127.0.0.1:${config.clientAdminApi.port.unwrap}",
    config.clientAdminApi,
  )

  protected val nodes = consoleEnvironment.environment.validators

  @Help.Summary("Return local validator app config")
  override def config: ValidatorAppBackendConfig =
    consoleEnvironment.environment.config.validatorsByString(name)

  /** Remote participant this validator app is configured to interact with. */
  lazy val remoteParticipant =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`",
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this validator app is configured to interact with. Uses admin tokens to bypass auth. */
  val remoteParticipantWithAdminToken =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}

/** Client (aka remote) reference to a validator app in the style of CNRemoteParticipantReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class ValidatorAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    override val config: ValidatorAppClientConfig,
) extends ValidatorAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "Validator Client"
}
