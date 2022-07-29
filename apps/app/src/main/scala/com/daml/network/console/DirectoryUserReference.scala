package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.directory.user.admin.api.client.commands.DirectoryUserCommands
import com.daml.network.directory.user.config.LocalDirectoryUserAppConfig
import com.daml.network.util.Contract
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}

/** Single local Directory User app reference. Defines the console commands that can be run against a local Directory User
  * app reference.
  */
class LocalDirectoryUserAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  protected val nodes = consoleEnvironment.environment.directoryUsers
  @Help.Summary("Return directory user app config")
  def config: LocalDirectoryUserAppConfig =
    consoleEnvironment.environment.config.directoryUsersByString(name)

  /** Remote participant this Directory User app is configured to interact with. */
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
