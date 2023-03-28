package com.daml.network.console

import com.daml.network.admin.api.client.HttpAdminAppClient
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.config.CNHttpClientConfig
import com.daml.network.directory.admin.api.client.commands.HttpDirectoryAppClient
import com.daml.network.directory.config.{LocalDirectoryAppConfig, RemoteDirectoryAppConfig}
import com.daml.network.environment.{CNNodeStatus, CNNodeConsoleEnvironment}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  GrpcRemoteInstanceReference,
  Help,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.console.commands.TopologyAdministrationGroup
import com.digitalasset.canton.console.ConsoleMacros
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.console.ConsoleCommandResult

abstract class DirectoryAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

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

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  @Help.Summary("Health and diagnostic related commands (HTTP)")
  @Help.Group("HTTP Health")
  def httpHealth = {
    consoleEnvironment.run {
      // Map failing HTTP requests to a failed NodeStatus if the status endpoint isn't up yet (e.g. slow app initialization)
      // TODO(#3467) see if we still need this after the initialization order is fixed
      ConsoleCommandResult.fromEither(
        Right(
          httpCommand(
            HttpAdminAppClient.GetHealthStatus[CNNodeStatus](CNNodeStatus.fromJsonV0)
          ).toEither.fold(err => NodeStatus.Failure(err), success => success)
        )
      )
    }
  }

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  // Override topology to avoid using grpc status check
  private lazy val topology_ =
    new TopologyAdministrationGroup(
      this,
      None,
      consoleEnvironment,
      loggerFactory,
    )
  @Help.Summary("Topology management related commands")
  @Help.Group("Topology")
  @Help.Description(
    "This group contains access to the full set of topology management commands."
  )
  override def topology: TopologyAdministrationGroup = topology_

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  override def waitForInitialization(
      timeout: NonNegativeDuration = cnNodeConsoleEnvironment.commandTimeouts.bounded
  ): Unit =
    ConsoleMacros.utils.retry_until_true(timeout)(
      httpHealth.successOption.map(_.active).getOrElse(false)
    )
}

/** Single local Directory app reference. Defines the console commands that can be run against a Directory
  * app reference.
  */
class LocalDirectoryAppReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends DirectoryAppReference(consoleEnvironment, name)
    with LocalCNNodeAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Directory"

  @Help.Summary("Return directory app config")
  def config: LocalDirectoryAppConfig =
    consoleEnvironment.environment.config.directoriesByString(name)

  override def httpClientConfig = CNHttpClientConfig.fromClientConfig(
    // For local references, we assume that they are reachable on localhost.
    // TODO (#2019) Reconsider if we want these for local refs at all and if so
    // if we should specify a url here.
    s"http://127.0.0.1:${config.clientAdminApi.port.unwrap}",
    config.clientAdminApi,
  )

  protected val nodes = consoleEnvironment.environment.directories

  /** Remote participant this directory app is configured to interact with. */
  lazy val remoteParticipant =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this directory app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val remoteParticipantWithAdminToken =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )
}

class RemoteDirectoryAppReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: RemoteDirectoryAppConfig, // adding this explicitly for easier overriding
) extends DirectoryAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference {

  import LedgerApiExtensions.*

  override def httpClientConfig = config.adminApi
  lazy val ledgerApi = new ExternalLedgerApiClient(
    config.ledgerApi.clientConfig.address,
    config.ledgerApi.clientConfig.port,
    config.ledgerApi.clientConfig.tls,
    config.ledgerApi.getToken(),
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
      name: String
  ): (codegen.DirectoryEntryContext.ContractId, subsCodegen.SubscriptionRequest.ContractId) = {
    val userParty = ledgerApi.ledger_api_extensions.users.getPrimaryParty(config.ledgerApiUser)
    val damlTuple = ledgerApi.ledger_api_extensions.commands
      .submitWithResult(
        userId = config.ledgerApiUser,
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
