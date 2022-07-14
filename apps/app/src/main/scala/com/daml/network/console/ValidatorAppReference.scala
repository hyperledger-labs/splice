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
import com.digitalasset.canton.topology.ParticipantId

/** Single local validator app reference. Defines the console commands that can be run against a local validator
  * app reference.
  */
class LocalValidatorAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  protected val nodes = consoleEnvironment.environment.validators
  @Help.Summary("Return participant config")
  override def config: LocalValidatorAppConfig =
    consoleEnvironment.environment.config.validatorsByString(name)

  def dummy_command(some_string: String, some_number: Int): Int = {
    consoleEnvironment.run {
      adminCommand(ValidatorAppCommands.DummyCommmand(some_string, some_number))
    }
  }

  /** Remote participant this validator app is configured to interact with. */
  val remoteParticipant =
    new ValidatorAppRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)

}
