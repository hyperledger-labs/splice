package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.directory.provider.admin.api.client.commands.DirectoryProviderCommands
import com.daml.network.directory.provider.config.LocalDirectoryProviderAppConfig
import com.daml.network.directory.provider.{DirectoryEntryRequest, DirectoryInstallRequest}
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

  @Help.Summary("List all DirectoryInstallRequest contracts")
  def listInstallRequests(): Seq[DirectoryInstallRequest] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.ListInstallRequests())
    }
  }

  @Help.Summary("Accept a DirectoryInstallRequest creating a DirectoryInstall")
  def acceptInstallRequest(
      cid: Primitive.ContractId[codegen.DirectoryInstallRequest],
      svc: PartyId,
  ): Primitive.ContractId[codegen.DirectoryInstall] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.AcceptInstallRequest(cid, svc))
    }
  }

  @Help.Summary("List all DirectoryEntryRequest contracts")
  def listEntryRequests(): Seq[DirectoryEntryRequest] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.ListEntryRequests())
    }
  }

  @Help.Summary("Create a PaymentRequest for a given DirectoryEntryRequest")
  def requestEntryPayment(
      cid: Primitive.ContractId[codegen.DirectoryEntryRequest]
  ): Primitive.ContractId[walletCodegen.PaymentRequest.PaymentRequest] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.RequestEntryPayment(cid))
    }
  }

  @Help.Summary("Collect the ApprovedPayment and create the DirectoryEntry")
  def collectEntryPayment(
      cid: Primitive.ContractId[walletCodegen.PaymentRequest.ApprovedPayment]
  ): Primitive.ContractId[codegen.DirectoryEntry] = {
    consoleEnvironment.run {
      adminCommand(DirectoryProviderCommands.CollectEntryPayment(cid))
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
