package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.splitwise.admin.api.client.commands.GrpcSplitwiseAppClient
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CN.{Splitwise => splitCodegen, Wallet => walletCodegen}

/** Single local Splitwise app reference. Defines the console commands that can be run against a local Splitwise
  * app reference.
  */
class LocalSplitwiseAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Splitwise"

  protected val nodes = consoleEnvironment.environment.splitwises
  @Help.Summary("Return splitwise app config")
  def config: LocalSplitwiseAppConfig =
    consoleEnvironment.environment.config.splitwisesByString(name)

  /** Remote participant this Splitwise app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )

  // Commands for init
  @Help.Summary("Initialize splitwise with the validator party")
  def initialize(validator: PartyId): Unit =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.Initialize(validator))
    }

  // Commands for managing installs

  @Help.Summary("Create splitwise install proposal for given provider party")
  def createInstallProposal(
      provider: PartyId
  ): Primitive.ContractId[splitCodegen.SplitwiseInstallProposal] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.CreateInstallProposal(provider))
    }

  @Help.Summary("Accept splitwise install proposal")
  def acceptInstallProposal(
      proposal: Primitive.ContractId[splitCodegen.SplitwiseInstallProposal]
  ): Primitive.ContractId[splitCodegen.SplitwiseInstall] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.AcceptInstallProposal(proposal))
    }

  // Commands for the group owner

  @Help.Summary("Create group with the given id")
  def createGroup(provider: PartyId, id: String): Primitive.ContractId[splitCodegen.Group] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.CreateGroup(provider, id))
    }

  @Help.Summary(
    "Create invite for the group with the given id and make it visible to the observers"
  )
  def createGroupInvite(
      provider: PartyId,
      id: String,
      observers: Seq[PartyId],
  ): Primitive.ContractId[splitCodegen.GroupInvite] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.CreateGroupInvite(provider, id, observers))
    }

  @Help.Summary("Add the invitee on the accepted group invite to the group")
  def joinGroup(
      provider: PartyId,
      acceptedGroupInvite: Primitive.ContractId[splitCodegen.AcceptedGroupInvite],
  ): Primitive.ContractId[splitCodegen.Group] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.JoinGroup(provider, acceptedGroupInvite))
    }

  // Member invite

  @Help.Summary("Accept the group invite")
  def acceptInvite(
      provider: PartyId,
      groupInvite: Primitive.ContractId[splitCodegen.GroupInvite],
  ): Primitive.ContractId[splitCodegen.AcceptedGroupInvite] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.AcceptInvite(provider, groupInvite))
    }

  // Member operations

  @Help.Summary(
    "Enter a payment to the group on your behalf. Payment quantity is split equally between current group members."
  )
  def enterPayment(
      provider: PartyId,
      key: GrpcSplitwiseAppClient.GroupKey,
      quantity: BigDecimal,
      description: String,
  ): Primitive.ContractId[splitCodegen.BalanceUpdate] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.EnterPayment(provider, key, quantity, description))
    }

  @Help.Summary("Initiate a transfer to the receiver. Must be confirmed in the wallet.")
  def initiateTransfer(
      provider: PartyId,
      key: GrpcSplitwiseAppClient.GroupKey,
      receiver: PartyId,
      quantity: BigDecimal,
  ): Primitive.ContractId[walletCodegen.AppPaymentRequest] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.InitiateTransfer(provider, key, receiver, quantity))
    }

  @Help.Summary(
    "Complete the transfer by actually transferring the coins and creating a balance update."
  )
  def completeTransfer(
      provider: PartyId,
      key: GrpcSplitwiseAppClient.GroupKey,
      acceptedPayment: Primitive.ContractId[walletCodegen.AcceptedAppPayment],
  ): Primitive.ContractId[splitCodegen.BalanceUpdate] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.CompleteTransfer(provider, key, acceptedPayment))
    }

  @Help.Summary("Net balances of the parties in the group.")
  @Help.Description(
    """This allows us to emulate [splitwise simplify debt feature](https://www.splitwise.com/l/sdv/FgPQSo3Bsev).
      |E.g., if Alice owes Bob 10CC, and Charlie owes Alice 10CC, we can net that to Charlie owing Bob 10CC.
      |Note that we do not enforce that the resulting balances are simpler but we do enforce
      |that the total balance of each party stays the same.
      |"""
  )
  def net(
      provider: PartyId,
      key: GrpcSplitwiseAppClient.GroupKey,
      balanceChanges: Map[PartyId, Map[PartyId, BigDecimal]],
  ): Primitive.ContractId[splitCodegen.BalanceUpdate] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.Net(provider, key, balanceChanges))
    }

  // Read operations

  @Help.Summary("List all groups")
  def listGroups(): Seq[Contract[splitCodegen.Group]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListGroups())
    }

  @Help.Summary("List all group invites that you have not already accepted")
  def listGroupInvites(): Seq[Contract[splitCodegen.GroupInvite]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListGroupInvites())
    }

  @Help.Summary("List accepted group invites for the given group that can be used in joinGroup")
  def listAcceptedGroupInvites(
      provider: PartyId,
      id: String,
  ): Seq[Contract[splitCodegen.AcceptedGroupInvite]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListAcceptedGroupInvites(provider, id))
    }

  @Help.Summary("List balance updates for the given group")
  def listBalanceUpdates(key: GrpcSplitwiseAppClient.GroupKey): Seq[
    Contract[splitCodegen.BalanceUpdate]
  ] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListBalanceUpdates(key))
    }

  @Help.Summary(
    "List balances for the given group. Positive balance means that party owes you, negative balance means you owe that party."
  )
  def listBalances(key: GrpcSplitwiseAppClient.GroupKey): Map[PartyId, BigDecimal] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListBalances(key))
    }

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
