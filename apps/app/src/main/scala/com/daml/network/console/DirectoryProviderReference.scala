package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.directory.provider.admin.api.client.commands.DirectoryProviderCommands
import com.daml.network.directory.provider.config.{
  BaseDirectoryProviderAppConfig,
  LocalDirectoryProviderAppConfig,
  RemoteDirectoryProviderAppConfig,
}
import com.daml.network.util.Contract
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  Help,
  LocalInstanceReference,
  GrpcRemoteInstanceReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}

/** Single local Directory Provider app reference. Defines the console commands that can be run against a Directory Provider
  * app reference.
  */
abstract class DirectoryProviderAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with BaseInspection[ParticipantNode] {

  @Help.Summary("List all DirectoryInstallRequest contracts")
  def listInstallRequests(): Seq[Contract[codegen.DirectoryInstallRequest]] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.ListInstallRequests())
    }
  }

  @Help.Summary("Accept a DirectoryInstallRequest creating a DirectoryInstall")
  def acceptInstallRequest(
      cid: Primitive.ContractId[codegen.DirectoryInstallRequest]
  ): Primitive.ContractId[codegen.DirectoryInstall] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.AcceptInstallRequest(cid))
    }
  }

  @Help.Summary("List all DirectoryEntryRequest contracts")
  def listEntryRequests(): Seq[Contract[codegen.DirectoryEntryRequest]] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.ListEntryRequests())
    }
  }

  @Help.Summary("Create an AppPaymentRequest for a given DirectoryEntryRequest")
  def requestEntryPayment(
      cid: Primitive.ContractId[codegen.DirectoryEntryRequest]
  ): Primitive.ContractId[walletCodegen.AppPaymentRequest] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.RequestEntryPayment(cid))
    }
  }

  @Help.Summary("Collect the ApprovedAppPayment and create the DirectoryEntry")
  def collectEntryPayment(
      cid: Primitive.ContractId[walletCodegen.ApprovedAppPayment]
  ): Primitive.ContractId[codegen.DirectoryEntry] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.CollectEntryPayment(cid))
    }
  }

  @Help.Summary("List all directory entries")
  def listEntries(): Seq[Contract[codegen.DirectoryEntry]] =
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.ListEntries())
    }

  @Help.Summary("Lookup a directory entry by the party that registered it")
  def lookupEntryByParty(party: PartyId): Contract[codegen.DirectoryEntry] =
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.LookupEntryByParty(party))
    }

  @Help.Summary("Lookup a directory entry by its name")
  def lookupEntryByName(name: String): Contract[codegen.DirectoryEntry] =
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.LookupEntryByName(name))
    }

  @Help.Summary("Get the party id of the provider operating the directory service")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.GetProviderPartyId())
    }

  @Help.Summary("Return directory provider app config")
  def config: BaseDirectoryProviderAppConfig

  /** Remote participant this Directory Provider app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )
}

class LocalDirectoryProviderAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends DirectoryProviderAppReference(consoleEnvironment, name)
    with LocalInstanceReference {

  protected val nodes = consoleEnvironment.environment.directoryProviders

  @Help.Summary("Return directory provider app config")
  def config: LocalDirectoryProviderAppConfig =
    consoleEnvironment.environment.config.directoryProvidersByString(name)

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}

class RemoteDirectoryProviderAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    config_ : RemoteDirectoryProviderAppConfig,
    name: String,
) extends DirectoryProviderAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference {

  @Help.Summary("Return directory provider app config")
  def config: RemoteDirectoryProviderAppConfig = config_
}
