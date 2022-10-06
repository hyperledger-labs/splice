package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.{Directory => codegen}
import com.daml.network.codegen.DA
import com.daml.network.directory.admin.api.client.commands.GrpcDirectoryAppClient
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.util.Contract
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
  def listEntries(): Seq[Contract[codegen.DirectoryEntry]] =
    consoleEnvironment.run {
      adminCommand(GrpcDirectoryAppClient.ListEntries())
    }

  @Help.Summary("Lookup a directory entry by the party that registered it")
  def lookupEntryByParty(party: PartyId): Contract[codegen.DirectoryEntry] =
    consoleEnvironment.run {
      adminCommand(GrpcDirectoryAppClient.LookupEntryByParty(party))
    }

  @Help.Summary("Lookup a directory entry by its name")
  def lookupEntryByName(name: String): Contract[codegen.DirectoryEntry] =
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
      config.remoteParticipant,
    )
}

class RemoteDirectoryAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends DirectoryAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference {

  private val ledgerApi = new ExternalLedgerApiClient(
    config.ledgerApi.address,
    config.ledgerApi.port,
    config.ledgerApi.tls,
  )(consoleEnvironment)

  override protected val instanceType = "Remote directory"

  @Help.Summary("Return directory app config")
  def config: RemoteDirectoryAppConfig =
    consoleEnvironment.environment.config.remoteDirectoriesByString(name)

  @Help.Summary("Request DirectoryInstall contract")
  def requestDirectoryInstall(): Primitive.ContractId[codegen.DirectoryInstallRequest] = {
    val providerParty = getProviderPartyId()
    val userParty = LedgerApiUtils.getUserPrimaryParty(ledgerApi, config.damlUser)
    LedgerApiUtils.submitWithResult(
      ledgerApi,
      actAs = Seq(userParty),
      readAs = Seq.empty,
      update = codegen
        .DirectoryInstallRequest(user = userParty.toPrim, provider = providerParty.toPrim)
        .create,
    )
  }

  @Help.Summary("Lookup a user's DirectoryInstall contract")
  def lookupInstall(user: PartyId): Option[Contract[codegen.DirectoryInstall]] = {
    consoleEnvironment.run {
      adminCommand(GrpcDirectoryAppClient.LookupInstall(user))
    }
  }

  @Help.Summary("Request DirectoryEntry with the given name")
  def requestDirectoryEntry(name: String): Primitive.ContractId[codegen.DirectoryEntryRequest] = {
    val providerParty = getProviderPartyId()
    val userParty = LedgerApiUtils.getUserPrimaryParty(ledgerApi, config.damlUser)
    LedgerApiUtils.submitWithResult(
      ledgerApi,
      actAs = Seq(userParty),
      readAs = Seq.empty,
      update = codegen.DirectoryInstall
        .key(DA.Types.Tuple2(providerParty.toPrim, userParty.toPrim))
        .exerciseDirectoryInstall_RequestEntry(
          codegen.DirectoryEntry(
            provider = providerParty.toPrim,
            user = userParty.toPrim,
            name = name,
          )
        ),
    )
  }
}
