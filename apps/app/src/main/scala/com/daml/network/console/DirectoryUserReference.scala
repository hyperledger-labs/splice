package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.directory.user.admin.api.client.commands.DirectoryUserCommands
import com.daml.network.directory.user.config.LocalDirectoryUserAppConfig
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CN.{Directory => codegen}

/** Single local Directory User app reference. Defines the console commands that can be run against a local Directory User
  * app reference.
  */
class LocalDirectoryUserAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Directory user"

  protected val nodes = consoleEnvironment.environment.directoryUsers
  @Help.Summary("Return directory user app config")
  def config: LocalDirectoryUserAppConfig =
    consoleEnvironment.environment.config.directoryUsersByString(name)

  @Help.Summary("Request DirectoryInstall contract")
  def requestDirectoryInstall(): Primitive.ContractId[codegen.DirectoryInstallRequest] =
    consoleEnvironment.run {
      adminCommand(DirectoryUserCommands.RequestDirectoryInstall())
    }

  @Help.Summary("Request DirectoryEntry with the given name")
  def requestDirectoryEntry(name: String): Primitive.ContractId[codegen.DirectoryEntryRequest] =
    consoleEnvironment.run {
      adminCommand(DirectoryUserCommands.RequestDirectoryEntry(name))
    }

  @Help.Summary("List all directory entries")
  def listEntries(): Seq[Contract[codegen.DirectoryEntry]] =
    remoteDirectoryProvider.listEntries()

  @Help.Summary("Lookup a directory entry by the party that registered it")
  def lookupEntryByParty(party: PartyId): Contract[codegen.DirectoryEntry] =
    remoteDirectoryProvider.lookupEntryByParty(party)

  @Help.Summary("Lookup a directory entry by its name")
  def lookupEntryByName(name: String): Contract[codegen.DirectoryEntry] =
    remoteDirectoryProvider.lookupEntryByName(name)

  /** Remote participant this Directory User app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )

  /** Remote directory provider app user is configured to interact with. We make this
    * private since we only want to expose a subset of the methods.
    */
  private val remoteDirectoryProvider =
    new RemoteDirectoryProviderAppReference(
      consoleEnvironment,
      config.remoteDirectoryProvider,
      s"remote directory provider for `$name``",
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
