package com.daml.network.console

import com.daml.network.auth.{AuthUtil, JwtCallCredential}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.validator.admin.api.client.UserInfo
import com.daml.network.validator.admin.api.client.commands.GrpcValidatorAppClient
import com.daml.network.validator.config.{ValidatorAppBackendConfig, ValidatorAppClientConfig}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{DomainId, PartyId}

/** Console commands that can be executed either through client or backend reference.
  */
abstract class ValidatorAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  override protected val instanceType = "Validator"

  protected def token: String
  private def callCredentials = Some(new JwtCallCredential(token))

  @Help.Summary("Get validator user info")
  @Help.Description("Return the user info of the validator operator")
  def getValidatorUserInfo(): UserInfo = {
    consoleEnvironment.run {
      adminCommand(GrpcValidatorAppClient.GetValidatorUserInfo(), callCredentials)
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
      adminCommand(GrpcValidatorAppClient.OnboardUserCommand(user), callCredentials)
    }
  }

  @Help.Summary("List the connected domains of the participant the app is running on")
  def listConnectedDomains(): Map[DomainAlias, DomainId] =
    consoleEnvironment.run {
      adminCommand(GrpcValidatorAppClient.ListConnectedDomains(), callCredentials)
    }
}

final class ValidatorAppBackendReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends ValidatorAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Local Validator"

  override def token: String = {
    AuthUtil.testToken(
      audience = AuthUtil.testAudience,
      user = config.damlUser,
    )
  }

  protected val nodes = consoleEnvironment.environment.validators

  @Help.Summary("Return local validator app config")
  override def config: ValidatorAppBackendConfig =
    consoleEnvironment.environment.config.validatorsByString(name)

  /** Remote participant this validator app is configured to interact with. */
  lazy val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`",
      name,
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this validator app is configured to interact with. Uses admin tokens to bypass auth. */
  val remoteParticipantWithAdminToken =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      name,
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}

/** Client (aka remote) reference to a validator app in the style of CoinRemoteParticipantReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class ValidatorAppClientReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: ValidatorAppClientConfig,
) extends ValidatorAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override def token: String = ""

  override protected val instanceType = "Validator Client"
}
