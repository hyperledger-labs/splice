package com.daml.network.console

import akka.actor.ActorSystem
import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.directory.DirectoryApp
import com.daml.network.directory.admin.api.client.commands.HttpDirectoryAppClient
import com.daml.network.directory.config.{DirectoryAppBackendConfig, DirectoryAppClientConfig}
import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, ExternalLedgerApiClient, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

abstract class DirectoryAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override def basePath = ""

  @Help.Summary("List directory entries")
  @Help.Description(
    "Lists all directory entries whose name is prefixed with the given prefix, up to a given number of entries"
  )
  def listEntries(
      namePrefix: String,
      pageSize: Int,
  ): Seq[Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry]] =
    consoleEnvironment.run {
      httpCommand(HttpDirectoryAppClient.ListEntries(namePrefix, pageSize))
    }

  @Help.Summary("Lookup a directory entry by the party that registered it")
  def lookupEntryByParty(
      party: PartyId
  ): Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry] =
    consoleEnvironment.run {
      httpCommand(HttpDirectoryAppClient.LookupEntryByParty(party))
    }

  @Help.Summary("Lookup a directory entry by its name")
  def lookupEntryByName(
      name: String
  ): Contract[codegen.DirectoryEntry.ContractId, codegen.DirectoryEntry] =
    consoleEnvironment.run {
      httpCommand(HttpDirectoryAppClient.LookupEntryByName(name))
    }

  @Help.Summary("Get the party id of the provider operating the directory service")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      httpCommand(HttpDirectoryAppClient.GetProviderPartyId())
    }
}

/** Single local Directory app reference. Defines the console commands that can be run against a Directory
  * app reference.
  */
class DirectoryAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
)(implicit actorSystem: ActorSystem)
    extends DirectoryAppReference(consoleEnvironment, name)
    with CNNodeAppBackendReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Directory"

  @Help.Summary("Return directory app config")
  def config: DirectoryAppBackendConfig =
    consoleEnvironment.environment.config.directoriesByString(name)

  override def httpClientConfig = NetworkAppClientConfig(
    s"http://127.0.0.1:${config.clientAdminApi.port}"
  )

  val nodes = consoleEnvironment.environment.directories

  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  def appState: DirectoryApp.State = _appState[DirectoryApp.State, DirectoryApp]

  /** Remote participant this directory app is configured to interact with. */
  lazy val participantClient =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this directory app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )
}

class DirectoryAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: DirectoryAppClientConfig, // adding this explicitly for easier overriding
)(implicit actorSystem: ActorSystem)
    extends DirectoryAppReference(consoleEnvironment, name) {

  import LedgerApiExtensions.*

  override def httpClientConfig = config.adminApi
  lazy val ledgerApi = new ExternalLedgerApiClient(
    config.ledgerApi.clientConfig.address,
    config.ledgerApi.clientConfig.port,
    config.ledgerApi.clientConfig.tls,
    config.ledgerApi.getToken().map(_.accessToken),
  )(consoleEnvironment)

  override protected val instanceType = "Remote directory"

  private def getDirectoryInstall(): codegen.DirectoryInstall.ContractId = {
    val providerParty = getProviderPartyId()
    val userParty = ledgerApi.ledger_api_extensions.users.getPrimaryParty(config.ledgerApiUser)
    val allInstalls = ledgerApi.ledger_api_extensions.acs.filterJava(
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
    val userParty = ledgerApi.ledger_api_extensions.users.getPrimaryParty(config.ledgerApiUser)
    val created = ledgerApi.ledger_api_extensions.commands.submitWithResult(
      userId = config.ledgerApiUser,
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
      name: String,
      url: String,
      description: String,
  ): (codegen.DirectoryEntryContext.ContractId, subsCodegen.SubscriptionRequest.ContractId) = {
    val userParty = ledgerApi.ledger_api_extensions.users.getPrimaryParty(config.ledgerApiUser)
    val damlTuple = ledgerApi.ledger_api_extensions.commands
      .submitWithResult(
        userId = config.ledgerApiUser,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = getDirectoryInstall()
          .exerciseDirectoryInstall_RequestEntry(
            name,
            url,
            description,
          ),
      )
      .exerciseResult
    (damlTuple._1, damlTuple._2)
  }
}
