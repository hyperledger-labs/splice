package com.daml.network.console

import com.daml.ledger.api.v1.CommandsOuterClass
import com.daml.ledger.javaapi.data.codegen.Update
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.LedgerApiExtensions.*
import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient
import com.daml.network.splitwell.config.{SplitwellAppBackendConfig, SplitwellAppClientConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  GrpcRemoteInstanceReference,
  Help,
  LedgerApiCommandRunner,
}
import com.digitalasset.canton.console.commands.{
  BaseLedgerApiAdministration,
  ParticipantAdministration,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{DomainId, PartyId}

import scala.jdk.CollectionConverters.*

/** Splitwell app reference. Defines the console commands that can be run against either a client or backend splitwell reference.
  */
abstract class SplitwellAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends CNNodeAppReference {

  // We go through BaseLedgerApiAdministration here rather than creating a
  // ledger connection since that one is already setup to be easily used
  // from the console.
  def ledgerApi: BaseLedgerApiAdministration with LedgerApiCommandRunner

  def participantAdminApi: ParticipantAdministration

  protected val remoteScanConfig: ScanAppClientConfig

  lazy val remoteScan =
    new ScanAppClientReference(
      cnNodeConsoleEnvironment,
      s"remote scan for `$name``",
      remoteScanConfig,
    )

  @Help.Summary("Get the primary party of the provider’s daml user specified in the config.")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.GetProviderPartyId())
    }

  @Help.Summary("Get the domain ids for the private splitwell app domains")
  def getSplitwellDomainIds(): GrpcSplitwellAppClient.SplitwellDomains =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.GetSplitwellDomainIds())
    }
}

final class SplitwellAppClientReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: SplitwellAppClientConfig, // adding this explicitly for easier overriding
) extends SplitwellAppReference(cnNodeConsoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {
  private val acceptDuration = new RelTime(
    60_000_000
  )

  override protected val instanceType = "Splitwell Client"

  override lazy val ledgerApi =
    new ExternalLedgerApiClient(
      config.remoteParticipant.ledgerApi.clientConfig.address,
      config.remoteParticipant.ledgerApi.clientConfig.port,
      config.remoteParticipant.ledgerApi.clientConfig.tls,
      config.remoteParticipant.ledgerApi.getToken(),
    )(consoleEnvironment)

  override lazy val participantAdminApi =
    new CNRemoteParticipantReference(
      cnNodeConsoleEnvironment,
      "splitwell participant admin api",
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  val userId: String = config.ledgerApiUser

  lazy val context =
    GrpcSplitwellAppClient.SplitwellContext(getUserPrimaryParty())

  private def getSplitwellInstall(): (DomainId, splitwellCodegen.SplitwellInstall.ContractId) = {
    val userParty = getUserPrimaryParty()
    val installs = listSplitwellInstalls()
    installs.toList match {
      case Seq((domain, install)) => (domain, install)
      case _ =>
        throw new IllegalStateException(
          s"Expected exactly one SplitwellInstall contract for user $userParty but got $installs"
        )
    }
  }

  private def getGroup(
      groupKey: splitwellCodegen.GroupKey
  ): splitwellCodegen.Group.ContractId = {
    val userParty = getUserPrimaryParty()
    val groups = ledgerApi.ledger_api_extensions.acs.filterJava(splitwellCodegen.Group.COMPANION)(
      userParty,
      (group: splitwellCodegen.Group.Contract) =>
        group.data.provider == groupKey.provider && group.data.owner == groupKey.owner && group.data.id == groupKey.id,
    )
    groups match {
      case Seq(group) => group.id
      case _ =>
        throw new IllegalStateException(
          s"Expected exactly one Group contract for key $groupKey but got $groups"
        )
    }
  }

  private def groupKey_(owner: PartyId, id: String): splitwellCodegen.GroupKey = {
    val provider = getProviderPartyId()
    new splitwellCodegen.GroupKey(
      owner.toProtoPrimitive,
      provider.toProtoPrimitive,
      new splitwellCodegen.GroupId(id),
    )
  }

  def submitWithResult[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      commandId: Option[String] = None,
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq.empty,
      domainId: Option[DomainId] = None,
  ): T = {
    import LedgerApiExtensions.*
    ledgerApi.ledger_api_extensions.commands.submitWithResult(
      userId,
      actAs,
      readAs,
      update,
      commandId,
      Some(domainId.getOrElse(getSplitwellDomainIds().preferred)),
      disclosedContracts,
    )
  }

  private def getUserPrimaryParty() = ledgerApi.ledger_api_extensions.users.getPrimaryParty(userId)

  // Commands for managing installs

  @Help.Summary("Create splitwell install requests per connected domain")
  def createInstallRequests(
  ): Map[DomainId, splitwellCodegen.SplitwellInstallRequest.ContractId] = {
    val party = getUserPrimaryParty()
    val provider = getProviderPartyId()
    val splitwellDomains = getSplitwellDomainIds()
    val connectedDomains = participantAdminApi.domains.list_connected().map(_.domainId).toSet
    val connectedSplitwellDomains =
      (splitwellDomains.preferred +: splitwellDomains.others).filter(connectedDomains.contains(_))
    // We unconditionally create install contracts on all domains and rely on the provider's backend to reject them
    // for duplicates or if a domain is no longer supported.
    connectedSplitwellDomains.map { domainId =>
      val created = submitWithResult(
        actAs = Seq(party),
        readAs = Seq.empty,
        new splitwellCodegen.SplitwellInstallRequest(
          provider.toProtoPrimitive,
          party.toProtoPrimitive,
        ).create,
        domainId = Some(domainId),
      )
      domainId -> splitwellCodegen.SplitwellInstallRequest.COMPANION.toContractId(
        created.contractId
      )
    }.toMap
  }

  // Commands for the group owner

  @Help.Summary("Request group with the given id")
  def requestGroup(id: String): splitwellCodegen.GroupRequest.ContractId = {
    val party = getUserPrimaryParty()
    val provider = getProviderPartyId()
    val svc = remoteScan.getSvcPartyId()
    val (domain, install) = getSplitwellInstall()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_RequestGroup(
        new splitwellCodegen.Group(
          party.toProtoPrimitive,
          svc.toProtoPrimitive,
          Seq.empty.asJava,
          new splitwellCodegen.GroupId(id),
          provider.toProtoPrimitive,
          acceptDuration,
        )
      ),
      domainId = Some(domain),
    ).exerciseResult
  }

  @Help.Summary(
    "Create invite for the group with the given id and make it visible to the observers"
  )
  def createGroupInvite(
      id: String
  ): Contract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite] = {
    val party = getUserPrimaryParty()
    val (domain, install) = getSplitwellInstall()
    ledgerApi.ledger_api_extensions.commands.submitWithCreate(
      splitwellCodegen.GroupInvite.COMPANION
    )(
      userId,
      Seq(party),
      Seq.empty,
      install.exerciseSplitwellInstall_CreateInvite(
        getGroup(groupKey_(party, id))
      ),
      domainId = Some(domain),
    )
  }

  @Help.Summary("Add the invitee on the accepted group invite to the group")
  def joinGroup(
      acceptedGroupInvite: splitwellCodegen.AcceptedGroupInvite.ContractId
  ): splitwellCodegen.Group.ContractId = {
    val party = getUserPrimaryParty()
    val acceptedInvite =
      ledgerApi.ledger_api_extensions.acs.awaitJava(splitwellCodegen.AcceptedGroupInvite.COMPANION)(
        party,
        (c: splitwellCodegen.AcceptedGroupInvite.Contract) => c.id == acceptedGroupInvite,
      )
    val (domain, install) = getSplitwellInstall()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_Join(
        getGroup(acceptedInvite.data.groupKey),
        acceptedGroupInvite,
      ),
      domainId = Some(domain),
    ).exerciseResult
  }

  // Member invite

  @Help.Summary("Accept the group invite")
  def acceptInvite(
      groupInvite: Contract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
  ): splitwellCodegen.AcceptedGroupInvite.ContractId = {
    val party = getUserPrimaryParty()
    val (domain, install) = getSplitwellInstall()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_AcceptInvite(
        groupInvite.contractId
      ),
      disclosedContracts = Seq(groupInvite.toDisclosedContract),
      domainId = Some(domain),
    ).exerciseResult
  }

  // Member operations

  @Help.Summary(
    "Enter a payment to the group on your behalf. Payment amount is split equally between current group members."
  )
  def enterPayment(
      key: GrpcSplitwellAppClient.GroupKey,
      amount: BigDecimal,
      description: String,
  ): splitwellCodegen.BalanceUpdate.ContractId = {
    val party = getUserPrimaryParty()
    val (domain, install) = getSplitwellInstall()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_EnterPayment(
        getGroup(key.toPrim),
        amount.bigDecimal,
        description,
      ),
      domainId = Some(domain),
    ).exerciseResult
  }

  @Help.Summary("Initiate a transfer. Must be confirmed in the wallet.")
  def initiateTransfer(
      key: GrpcSplitwellAppClient.GroupKey,
      receiverAmounts: Seq[walletCodegen.ReceiverCCAmount],
  ): walletCodegen.AppPaymentRequest.ContractId = {
    val party = getUserPrimaryParty()
    val (domain, install) = getSplitwellInstall()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_InitiateTransfer(
        getGroup(key.toPrim),
        receiverAmounts.asJava,
      ),
      domainId = Some(domain),
    ).exerciseResult
  }

  @Help.Summary("Net balances of the parties in the group.")
  @Help.Description(
    """This allows us to emulate [splitwell simplify debt feature](https://www.splitwell.com/l/sdv/FgPQSo3Bsev).
      |E.g., if Alice owes Bob 10CC, and Charlie owes Alice 10CC, we can net that to Charlie owing Bob 10CC.
      |Note that we do not enforce that the resulting balances are simpler but we do enforce
      |that the total balance of each party stays the same.
      |"""
  )
  def net(
      key: GrpcSplitwellAppClient.GroupKey,
      balanceChanges: Map[PartyId, Map[PartyId, BigDecimal]],
  ): splitwellCodegen.BalanceUpdate.ContractId = {
    val party = getUserPrimaryParty()
    val balanceChangesPrim: java.util.Map[String, java.util.Map[String, java.math.BigDecimal]] =
      balanceChanges.map { case (k, v) =>
        k.toProtoPrimitive -> (v
          .map { case (k, v) => k.toProtoPrimitive -> v.bigDecimal }: Map[
          String,
          java.math.BigDecimal,
        ]).asJava
      }.asJava
    val (domain, install) = getSplitwellInstall()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_Net(
        getGroup(key.toPrim),
        balanceChangesPrim,
      ),
      domainId = Some(domain),
    ).exerciseResult
  }

  // Read operations

  @Help.Summary("List all groups")
  def listGroups(): Seq[Contract[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListGroups(context))
    }

  @Help.Summary("List all group invites that you have not already accepted")
  def listGroupInvites()
      : Seq[Contract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListGroupInvites(context))
    }

  @Help.Summary("List accepted group invites for the given group that can be used in joinGroup")
  def listAcceptedGroupInvites(
      id: String
  ): Seq[
    Contract[splitwellCodegen.AcceptedGroupInvite.ContractId, splitwellCodegen.AcceptedGroupInvite]
  ] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListAcceptedGroupInvites(id, context))
    }

  @Help.Summary("List balance updates for the given group")
  def listBalanceUpdates(key: GrpcSplitwellAppClient.GroupKey): Seq[
    Contract[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
  ] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListBalanceUpdates(key, context))
    }

  @Help.Summary(
    "List balances for the given group. Positive balance means that party owes you, negative balance means you owe that party."
  )
  def listBalances(key: GrpcSplitwellAppClient.GroupKey): Map[PartyId, BigDecimal] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListBalances(key, context))
    }

  def listSplitwellInstalls(): Map[DomainId, splitwellCodegen.SplitwellInstall.ContractId] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListSplitwellInstalls(context))
    }

  override val remoteScanConfig = config.remoteScan
}

final class SplitwellAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
) extends SplitwellAppReference(consoleEnvironment, name)
    with LocalCNNodeAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Splitwell Backend"

  override protected val nodes = consoleEnvironment.environment.splitwells

  override lazy val ledgerApi = remoteParticipant

  override lazy val participantAdminApi = remoteParticipant

  @Help.Summary("Return local splitwell app config")
  def config: SplitwellAppBackendConfig =
    consoleEnvironment.environment.config.splitwellsByString(name)

  override val remoteScanConfig = config.remoteScan

  /** Remote participant this splitwell app is configured to interact with. */
  lazy val remoteParticipant =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this splitwell app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val remoteParticipantWithAdminToken =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )
}
