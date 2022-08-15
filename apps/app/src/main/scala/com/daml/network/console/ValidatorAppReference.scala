package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.validator.admin.api.client.commands.ValidatorAppCommands
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.commands._
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  ConsoleEnvironment,
  FeatureFlag,
  Help,
  InstanceReference,
  LedgerApiCommandRunner,
  LocalInstanceReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.health.admin.data.ParticipantStatus
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

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
      adminCommand(ValidatorAppCommands.SetupValidatorCommand())
    }
  }

  @Help.Summary("Onboard a new user")
  @Help.Description("""Onboard individual canton-coin user for the given validator party.
                      |Return the newly set up partyId.""".stripMargin)
  def onboardUser(user: String): PartyId = {
    consoleEnvironment.run {
      adminCommand(ValidatorAppCommands.OnboardUserCommand(user))
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
