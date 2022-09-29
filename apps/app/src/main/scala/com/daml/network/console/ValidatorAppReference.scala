package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.validator.admin.api.client.commands.GrpcValidatorAppClient
import com.daml.network.validator.config.{LocalValidatorAppConfig, RemoteValidatorAppConfig}
import com.digitalasset.canton.console.{
  BaseInspection,
  GrpcRemoteInstanceReference,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

/** Single local validator app reference. Defines the console commands that can be run against a local validator
  * app reference.
  */
abstract class ValidatorAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name) {

  override protected val instanceType = "Validator"

  @Help.Summary("Get validator party id")
  @Help.Description("Return the party id of the validator operator")
  def getValidatorPartyId(): PartyId = {
    consoleEnvironment.run {
      adminCommand(GrpcValidatorAppClient.GetValidatorPartyId())
    }
  }

  @Help.Summary("Onboard a new user")
  @Help.Description("""Onboard individual canton-coin user for the given validator party.
                      |Return the newly set up partyId.""".stripMargin)
  def onboardUser(user: String): PartyId = {
    consoleEnvironment.run {
      adminCommand(GrpcValidatorAppClient.OnboardUserCommand(user))
    }
  }
}

final class LocalValidatorAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends ValidatorAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Local Validator"

  protected val nodes = consoleEnvironment.environment.validators

  @Help.Summary("Return local validator app config")
  override def config: LocalValidatorAppConfig =
    consoleEnvironment.environment.config.validatorsByString(name)

  /** Remote participant this validator app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}

/** Remote reference to a scan app in the style of CoinRemoteParticipantReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class RemoteValidatorAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: RemoteValidatorAppConfig,
) extends ValidatorAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Remote Validator"
}
