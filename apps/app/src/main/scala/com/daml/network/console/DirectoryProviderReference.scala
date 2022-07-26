package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.directory.provider.admin.api.client.commands.DirectoryProviderCommands
import com.daml.network.directory.provider.config.LocalDirectoryProviderAppConfig
import com.daml.network.directory.provider.DirectoryInstallRequest
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.participant.ParticipantNode

/** Single local Directory Provider app reference. Defines the console commands that can be run against a local Directory Provider
  * app reference.
  */
class LocalDirectoryProviderAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  protected val nodes = consoleEnvironment.environment.directoryProviders
  @Help.Summary("Return participant config")
  def config: LocalDirectoryProviderAppConfig =
    consoleEnvironment.environment.config.directoryProvidersByString(name)

  def listInstallRequests(): Seq[DirectoryInstallRequest] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.ListInstallRequests())
    }
  }

  /** Remote participant this Directory Provider app is configured to interact with. */
  val remoteParticipant =
    new DirectoryProviderAppRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
