package com.daml.network.console

import com.daml.ledger.api.v1.CommandsOuterClass
import com.daml.ledger.javaapi.data.codegen.Update
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.LedgerApiExtensions.*
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient
import com.daml.network.splitwell.config.{SplitwellAppBackendConfig, SplitwellAppClientConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  GrpcRemoteInstanceReference,
  LedgerApiCommandRunner,
  Help,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{DomainId, PartyId}

import scala.jdk.CollectionConverters.*

/** Splitwell app reference. Defines the console commands that can be run against either a client or backend splitwell reference.
  */
abstract class SplitwellAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  // We go through BaseLedgerApiAdministration here rather than creating a
  // ledger connection since that one is already setup to be easily used
  // from the console.
  def ledgerApi: BaseLedgerApiAdministration with LedgerApiCommandRunner

  protected val remoteScanConfig: ScanAppClientConfig

  lazy val remoteScan =
    new ScanAppClientReference(
      coinConsoleEnvironment,
      s"remote scan for `$name``",
      remoteScanConfig,
    )

  @Help.Summary("Get the primary party of the provider’s daml user specified in the config.")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.GetProviderPartyId())
    }

  @Help.Summary("List the connected domains of the participant the app is running on")
  def listConnectedDomains(): Map[DomainAlias, DomainId] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListConnectedDomains())
    }

  @Help.Summary("Get the domain id for the private splitwell app domain")
  def getSplitwellDomainId(): DomainId =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.GetSplitwellDomainId())
    }
}

final class SplitwellAppClientReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    name: String,
    val config: SplitwellAppClientConfig, // adding this explicitly for easier overriding
) extends SplitwellAppReference(coinConsoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {
  private val collectionDuration = new RelTime(
    10_000_000
  )
  private val acceptDuration = new RelTime(
    60_000_000
  )

  override protected val instanceType = "Splitwell Client"

  override lazy val ledgerApi =
    new ExternalLedgerApiClient(
      config.ledgerApi.clientConfig.address,
      config.ledgerApi.clientConfig.port,
      config.ledgerApi.clientConfig.tls,
      config.ledgerApi.getToken(),
    )(consoleEnvironment)

  val userId: String = config.ledgerApiUser

  lazy val context =
    GrpcSplitwellAppClient.SplitwellContext(getUserPrimaryParty())

  private def getSplitwellInstall(): splitwellCodegen.SplitwellInstall.ContractId = {
    val providerParty = getProviderPartyId()
    val userParty = getUserPrimaryParty()
    val installs =
      ledgerApi.ledger_api_extensions.acs.filterJava(splitwellCodegen.SplitwellInstall.COMPANION)(
        userParty,
        (install: splitwellCodegen.SplitwellInstall.Contract) =>
          install.data.user == userParty.toProtoPrimitive && install.data.provider == providerParty.toProtoPrimitive,
      )
    installs match {
      case Seq(install) => install.id
      case _ =>
        throw new IllegalStateException(
          s"Expected exactly one DirectoryInstall contract for user $userParty but got $installs"
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
  ): T = {
    import LedgerApiExtensions.*
    ledgerApi.ledger_api_extensions.commands.submitWithResult(
      userId,
      actAs,
      readAs,
      update,
      commandId,
      Some(getSplitwellDomainId()),
      disclosedContracts,
    )
  }

  private def getUserPrimaryParty() = ledgerApi.ledger_api_extensions.users.getPrimaryParty(userId)

  // Commands for managing installs

  @Help.Summary("Create splitwell install request for given provider party")
  def createInstallRequest(
  ): splitwellCodegen.SplitwellInstallRequest.ContractId = {
    val party = getUserPrimaryParty()
    val provider = getProviderPartyId()
    val created = submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      new splitwellCodegen.SplitwellInstallRequest(
        provider.toProtoPrimitive,
        party.toProtoPrimitive,
      ).create,
    )
    splitwellCodegen.SplitwellInstallRequest.COMPANION.toContractId(created.contractId)
  }

  // Commands for the group owner

  @Help.Summary("Request group with the given id")
  def requestGroup(id: String): splitwellCodegen.GroupRequest.ContractId = {
    val party = getUserPrimaryParty()
    val provider = getProviderPartyId()
    val svc = remoteScan.getSvcPartyId()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      getSplitwellInstall().exerciseSplitwellInstall_RequestGroup(
        new splitwellCodegen.Group(
          party.toProtoPrimitive,
          svc.toProtoPrimitive,
          Seq.empty.asJava,
          new splitwellCodegen.GroupId(id),
          provider.toProtoPrimitive,
          collectionDuration,
          acceptDuration,
        )
      ),
    ).exerciseResult
  }

  @Help.Summary(
    "Create invite for the group with the given id and make it visible to the observers"
  )
  def createGroupInvite(
      id: String
  ): Contract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite] = {
    val party = getUserPrimaryParty()
    ledgerApi.ledger_api_extensions.commands.submitWithCreate(
      splitwellCodegen.GroupInvite.COMPANION
    )(
      userId,
      Seq(party),
      Seq.empty,
      getSplitwellInstall().exerciseSplitwellInstall_CreateInvite(
        getGroup(groupKey_(party, id))
      ),
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
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      getSplitwellInstall().exerciseSplitwellInstall_Join(
        getGroup(acceptedInvite.data.groupKey),
        acceptedGroupInvite,
      ),
    ).exerciseResult
  }

  // Member invite

  @Help.Summary("Accept the group invite")
  def acceptInvite(
      groupInvite: Contract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
  ): splitwellCodegen.AcceptedGroupInvite.ContractId = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      getSplitwellInstall().exerciseSplitwellInstall_AcceptInvite(
        groupInvite.contractId
      ),
      disclosedContracts = Seq(groupInvite.toDisclosedContract),
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
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      getSplitwellInstall().exerciseSplitwellInstall_EnterPayment(
        getGroup(key.toPrim),
        amount.bigDecimal,
        description,
      ),
    ).exerciseResult
  }

  @Help.Summary("Initiate a transfer. Must be confirmed in the wallet.")
  def initiateTransfer(
      key: GrpcSplitwellAppClient.GroupKey,
      receiverAmounts: Seq[walletCodegen.ReceiverCCAmount],
  ): walletCodegen.AppPaymentRequest.ContractId = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      getSplitwellInstall().exerciseSplitwellInstall_InitiateTransfer(
        getGroup(key.toPrim),
        receiverAmounts.asJava,
      ),
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
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      getSplitwellInstall().exerciseSplitwellInstall_Net(
        getGroup(key.toPrim),
        balanceChangesPrim,
      ),
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

  override val remoteScanConfig = config.remoteScan
}

final class SplitwellAppBackendReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SplitwellAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Splitwell Backend"

  override protected val nodes = consoleEnvironment.environment.splitwells

  override lazy val ledgerApi = remoteParticipant

  @Help.Summary("Return local splitwell app config")
  def config: SplitwellAppBackendConfig =
    consoleEnvironment.environment.config.splitwellsByString(name)

  override val remoteScanConfig = config.remoteScan

  /** Remote participant this splitwell app is configured to interact with. */
  lazy val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this splitwell app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val remoteParticipantWithAdminToken =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      name,
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )
}
