package com.daml.network.console

import com.daml.network.codegen.java.cn.wallet.{subscriptions => subsCodegen}
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.directory.admin.api.client.commands.GrpcDirectoryAppClient
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.util.{JavaContract as Contract}
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  GrpcRemoteInstanceReference,
  Help,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

abstract class DirectoryAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {
  @Help.Summary("List all directory entries")
  def listEntries(): Seq[Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]] =
    consoleEnvironment.run {
      adminCommand(GrpcDirectoryAppClient.ListEntries())
    }

  @Help.Summary("Lookup a directory entry by the party that registered it")
  def lookupEntryByParty(
      party: PartyId
  ): Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry] =
    consoleEnvironment.run {
      adminCommand(GrpcDirectoryAppClient.LookupEntryByParty(party))
    }

  @Help.Summary("Lookup a directory entry by its name")
  def lookupEntryByName(
      name: String
  ): Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry] =
    consoleEnvironment.run {
      adminCommand(GrpcDirectoryAppClient.LookupEntryByName(name))
    }

  @Help.Summary("Get the party id of the provider operating the directory service")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      adminCommand(GrpcDirectoryAppClient.GetProviderPartyId())
    }
}

/** Single local Directory app reference. Defines the console commands that can be run against a Directory
  * app reference.
  */
class LocalDirectoryAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends DirectoryAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Directory"

  @Help.Summary("Return directory app config")
  def config: LocalDirectoryAppConfig =
    consoleEnvironment.environment.config.directoriesByString(name)

  protected val nodes = consoleEnvironment.environment.directories

  /** Remote participant this Directory app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )
}

class RemoteDirectoryAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends DirectoryAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference {

  val ledgerApi = new ExternalLedgerApiClient(
    config.ledgerApi.clientConfig.address,
    config.ledgerApi.clientConfig.port,
    config.ledgerApi.clientConfig.tls,
    // TODO(#1627): Use actual ledger API auth
    config.ledgerApi.authConfig.adminToken,
  )(consoleEnvironment)

  override protected val instanceType = "Remote directory"

  @Help.Summary("Return directory app config")
  def config: RemoteDirectoryAppConfig =
    consoleEnvironment.environment.config.remoteDirectoriesByString(name)

  private def getDirectoryInstall(): codegen.DirectoryInstall.ContractId = {
    val providerParty = getProviderPartyId()
    val userParty = LedgerApiUtils.getUserPrimaryParty(ledgerApi, config.damlUser)
    val allInstalls = ledgerApi.ledger_api.acs.filterJava(
      codegen.DirectoryInstall.COMPANION
    )(
      userParty,
      (install: codegen.DirectoryInstall.Contract) =>
        install.data.user == userParty.toProtoPrimitive && install.data.provider == providerParty.toProtoPrimitive,
    )
    allInstalls match {
      case Seq(install) => install.id
      case _ =>
        throw new IllegalStateException(
          s"Expected exactly one DirectoryInstall contract for user $userParty but got $allInstalls"
        )
    }
  }

  @Help.Summary("Request DirectoryInstall contract")
  def requestDirectoryInstall(): codegen.DirectoryInstallRequest.ContractId = {
    val providerParty = getProviderPartyId()
    val userParty = LedgerApiUtils.getUserPrimaryParty(ledgerApi, config.damlUser)
    val created = LedgerApiUtils.submitWithResult(
      ledgerApi,
      actAs = Seq(userParty),
      readAs = Seq.empty,
      update = new codegen.DirectoryInstallRequest(
        providerParty.toProtoPrimitive,
        userParty.toProtoPrimitive,
      ).create,
    )
    codegen.DirectoryInstallRequest.COMPANION.toContractId(created.contractId)
  }

  @Help.Summary("Request DirectoryEntry with the given name, financed via subscription payments")
  def requestDirectoryEntry(
      name: String
  ): (codegen.DirectoryEntryContext.ContractId, subsCodegen.SubscriptionRequest.ContractId) = {
    val userParty = LedgerApiUtils.getUserPrimaryParty(ledgerApi, config.damlUser)
    val damlTuple = LedgerApiUtils
      .submitWithResult(
        ledgerApi,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = getDirectoryInstall()
          .exerciseDirectoryInstall_RequestEntry(
            name
          ),
      )
      .exerciseResult
    (damlTuple._1, damlTuple._2)
  }
}
