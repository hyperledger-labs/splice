package com.daml.network.console

import com.daml.ledger.client.binding.{Primitive, Template, ValueDecoder}
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen, Wallet => walletCodegen}
import com.daml.network.codegen.DA
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.console.LedgerApiUtils
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.scan.config.RemoteScanAppConfig
import com.daml.network.splitwise.admin.api.client.commands.GrpcSplitwiseAppClient
import com.daml.network.splitwise.config.{LocalSplitwiseAppConfig, RemoteSplitwiseAppConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  GrpcRemoteInstanceReference,
  Help,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

/** Single local Splitwise app reference. Defines the console commands that can be run against a local Splitwise
  * app reference.
  */
abstract class SplitwiseAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  // We go through BaseLedgerApiAdministration here rather than creating a
  // ledger connection since that one is already setup to be easily used
  // from the console.
  def ledgerApi: BaseLedgerApiAdministration

  protected val remoteScanConfig: RemoteScanAppConfig

  lazy val remoteScan =
    new RemoteScanAppReference(
      coinConsoleEnvironment,
      s"remote scan for `$name``",
      remoteScanConfig,
    )

  def submitWithResult[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Primitive.Update[T],
      commandId: Option[String] = None,
  )(implicit decoder: ValueDecoder[T]): T =
    LedgerApiUtils.submitWithResult(ledgerApi, actAs, readAs, update, commandId)

  @Help.Summary("Get the primary party of the provider’s daml user specified in the config.")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.GetProviderPartyId())
    }
}

final class RemoteSplitwiseAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SplitwiseAppReference(coinConsoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {
  private val collectionDuration = RelTime(
    10_000_000
  )
  private val acceptDuration = RelTime(
    60_000_000
  )

  override protected val instanceType = "Remote Splitwise"

  override lazy val ledgerApi =
    new ExternalLedgerApiClient(
      config.ledgerApi.address,
      config.ledgerApi.port,
      config.ledgerApi.tls,
    )(consoleEnvironment)

  val userId: String = config.damlUser

  lazy val context =
    GrpcSplitwiseAppClient.SplitwiseContext(getUserPrimaryParty())

  private def installKey(
      user: PartyId
  ): Template.Key[splitwiseCodegen.SplitwiseInstall] = {
    val provider = getProviderPartyId()
    splitwiseCodegen.SplitwiseInstall.key(DA.Types.Tuple2(user.toPrim, provider.toPrim))
  }

  private def groupKey_(owner: PartyId, id: String): splitwiseCodegen.GroupKey = {
    val provider = getProviderPartyId()
    splitwiseCodegen.GroupKey(
      owner.toPrim,
      provider.toPrim,
      splitwiseCodegen.GroupId(id),
    )
  }

  private def getUserPrimaryParty() = LedgerApiUtils.getUserPrimaryParty(ledgerApi, userId)
  private def getUserReadAs() = LedgerApiUtils.getUserReadAs(ledgerApi, userId)

  @Help.Summary("Return remote splitwise app config")
  def config: RemoteSplitwiseAppConfig =
    coinConsoleEnvironment.environment.config.remoteSplitwisesByString(name)

  // Commands for managing installs

  @Help.Summary("Create splitwise install request for given provider party")
  def createInstallRequest(
  ): Primitive.ContractId[splitwiseCodegen.SplitwiseInstallRequest] = {
    val party = getUserPrimaryParty()
    val provider = getProviderPartyId()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      splitwiseCodegen
        .SplitwiseInstallRequest(
          user = party.toPrim,
          provider = provider.toPrim,
        )
        .create,
    )
  }

  // Commands for the group owner

  @Help.Summary("Create group with the given id")
  def createGroup(id: String): Primitive.ContractId[splitwiseCodegen.Group] = {
    val party = getUserPrimaryParty()
    val provider = getProviderPartyId()
    val svc = remoteScan.getSvcPartyId()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_CreateGroup(
        splitwiseCodegen.Group(
          owner = party.toPrim,
          provider = provider.toPrim,
          svc = svc.toPrim,
          members = Seq.empty,
          id = splitwiseCodegen.GroupId(id),
          collectionDuration = collectionDuration,
          acceptDuration = acceptDuration,
        )
      ),
    )
  }

  @Help.Summary(
    "Create invite for the group with the given id and make it visible to the observers"
  )
  def createGroupInvite(
      id: String,
      observers: Seq[PartyId],
  ): Primitive.ContractId[splitwiseCodegen.GroupInvite] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_CreateInvite(
        groupKey_(party, id),
        observers.map(_.toPrim),
      ),
    )
  }

  @Help.Summary("Add the invitee on the accepted group invite to the group")
  def joinGroup(
      acceptedGroupInvite: Primitive.ContractId[splitwiseCodegen.AcceptedGroupInvite]
  ): Primitive.ContractId[splitwiseCodegen.Group] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_Join(
        acceptedGroupInvite
      ),
    )
  }

  // Member invite

  @Help.Summary("Accept the group invite")
  def acceptInvite(
      groupInvite: Primitive.ContractId[splitwiseCodegen.GroupInvite]
  ): Primitive.ContractId[splitwiseCodegen.AcceptedGroupInvite] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_AcceptInvite(
        groupInvite
      ),
    )
  }

  // Member operations

  @Help.Summary(
    "Enter a payment to the group on your behalf. Payment quantity is split equally between current group members."
  )
  def enterPayment(
      key: GrpcSplitwiseAppClient.GroupKey,
      quantity: BigDecimal,
      description: String,
  ): Primitive.ContractId[splitwiseCodegen.BalanceUpdate] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_EnterPayment(
        key.toPrim,
        quantity,
        description,
      ),
    )
  }

  @Help.Summary("Initiate a transfer to the receiver. Must be confirmed in the wallet.")
  def initiateTransfer(
      key: GrpcSplitwiseAppClient.GroupKey,
      receiver: PartyId,
      quantity: BigDecimal,
  ): Primitive.ContractId[walletCodegen.AppPaymentRequest] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_InitiateTransfer(
        key.toPrim,
        receiver.toPrim,
        quantity,
      ),
    )
  }

  @Help.Summary("Initiate a transfer to multiple receiver. Must be confirmed in the wallet.")
  def initiateMultiTransfer(
      key: GrpcSplitwiseAppClient.GroupKey,
      receiverQuantities: Seq[walletCodegen.ReceiverQuantity],
  ): Primitive.ContractId[walletCodegen.AppMultiPaymentRequest] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_InitiateMultiTransfer(
        key.toPrim,
        receiverQuantities,
      ),
    )
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
      key: GrpcSplitwiseAppClient.GroupKey,
      balanceChanges: Map[PartyId, Map[PartyId, BigDecimal]],
  ): Primitive.ContractId[splitwiseCodegen.BalanceUpdate] = {
    val party = getUserPrimaryParty()
    val balanceChangesPrim
        : Primitive.GenMap[Primitive.Party, Primitive.GenMap[Primitive.Party, BigDecimal]] =
      balanceChanges.map { case (k, v) =>
        k.toPrim -> (v
          .map { case (k, v) => k.toPrim -> v }: Primitive.GenMap[Primitive.Party, BigDecimal])
      }
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(party).exerciseSplitwiseInstall_Net(
        key.toPrim,
        balanceChangesPrim,
      ),
    )
  }

  // Read operations

  @Help.Summary("List all groups")
  def listGroups(): Seq[Contract[splitwiseCodegen.Group]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListGroups(context))
    }

  @Help.Summary("List all group invites that you have not already accepted")
  def listGroupInvites(): Seq[Contract[splitwiseCodegen.GroupInvite]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListGroupInvites(context))
    }

  @Help.Summary("List accepted group invites for the given group that can be used in joinGroup")
  def listAcceptedGroupInvites(
      id: String
  ): Seq[Contract[splitwiseCodegen.AcceptedGroupInvite]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListAcceptedGroupInvites(id, context))
    }

  @Help.Summary("List balance updates for the given group")
  def listBalanceUpdates(key: GrpcSplitwiseAppClient.GroupKey): Seq[
    Contract[splitwiseCodegen.BalanceUpdate]
  ] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListBalanceUpdates(key, context))
    }

  @Help.Summary(
    "List balances for the given group. Positive balance means that party owes you, negative balance means you owe that party."
  )
  def listBalances(key: GrpcSplitwiseAppClient.GroupKey): Map[PartyId, BigDecimal] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListBalances(key, context))
    }

  override val remoteScanConfig = config.remoteScan
}

final class LocalSplitwiseAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SplitwiseAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Local Splitwise"

  override protected val nodes = consoleEnvironment.environment.splitwises

  override lazy val ledgerApi = remoteParticipant

  @Help.Summary("Return local splitwise app config")
  def config: LocalSplitwiseAppConfig =
    consoleEnvironment.environment.config.splitwisesByString(name)

  override val remoteScanConfig = config.remoteScan

  /** Remote participant this Wallet app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )
}
