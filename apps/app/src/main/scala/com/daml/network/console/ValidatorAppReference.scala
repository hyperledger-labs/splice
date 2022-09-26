package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.validator.admin.api.client.commands.GrpcValidatorAppClient
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

/** Single local validator app reference. Defines the console commands that can be run against a local validator
  * app reference.
  */
class LocalValidatorAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Validator"

  protected val nodes = consoleEnvironment.environment.validators
  @Help.Summary("Return validator app config")
  override def config: LocalValidatorAppConfig =
    consoleEnvironment.environment.config.validatorsByString(name)

  @Help.Summary("Set up a new validator")
  @Help.Description("""Create `CoinProposal` and sets up party for the validator.
                      |Return the party set up for the validator""".stripMargin)
  def initialize(): PartyId = {
    consoleEnvironment.run {
      adminCommand(GrpcValidatorAppClient.SetupValidatorCommand())
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

  @Help.Summary("Install wallet for validator operator")
  @Help.Description(
    """This installs the wallet for the operator user. For all other users this is done as part of onboardUser.
                      |Must be called after both the wallet and the validator app have been initialized.""".stripMargin
  )
  def installWalletForValidator(): Unit = {
    consoleEnvironment.run {
      adminCommand(GrpcValidatorAppClient.InstallWalletForValidator())
    }
  }

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
