package com.daml.network.console

import com.daml.ledger.client.binding.{
  Contract => CodegenContract,
  Primitive,
  Template,
  ValueDecoder,
}
import com.daml.network.console.LedgerApiUtils
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.scan.config.RemoteScanAppConfig
import com.daml.network.splitwise.admin.api.client.commands.GrpcSplitwiseAppClient
import com.daml.network.splitwise.config.{LocalSplitwiseAppConfig, RemoteSplitwiseAppConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  GrpcRemoteInstanceReference,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CC.Scripts.Util.CCUserHostedAt
import com.daml.network.codegen.CN.{Splitwise => splitCodegen, Wallet => walletCodegen}
import com.daml.network.codegen.DA
import com.daml.network.codegen.DA.Time.Types.RelTime

/** Single local Splitwise app reference. Defines the console commands that can be run against a local Splitwise
  * app reference.
  */
abstract class SplitwiseAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name) {

  // We go through BaseLedgerApiAdministration here rather than creating a
  // ledger connection since that one is already setup to be easily used
  // from the console.
  protected def ledgerApi: BaseLedgerApiAdministration

  protected def userId: String

  protected def context: GrpcSplitwiseAppClient.SplitwiseContext

  protected val remoteScanConfig: RemoteScanAppConfig

  private val collectionDuration = RelTime(
    10_000_000
  )
  private val acceptDuration = RelTime(
    60_000_000
  )

  lazy val remoteScan =
    new RemoteScanAppReference(
      consoleEnvironment,
      s"remote scan for `$name``",
      remoteScanConfig,
    )

  def getUserPrimaryParty() = LedgerApiUtils.getUserPrimaryParty(ledgerApi, userId)

  def submitWithResult[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Primitive.Update[T],
      commandId: Option[String] = None,
  )(implicit decoder: ValueDecoder[T]): T =
    LedgerApiUtils.submitWithResult(ledgerApi, actAs, readAs, update, commandId)

  private def installKey(
      provider: PartyId,
      user: PartyId,
  ): Template.Key[splitCodegen.SplitwiseInstall] =
    splitCodegen.SplitwiseInstall.key(DA.Types.Tuple2(user.toPrim, provider.toPrim))

  private def groupKey_(owner: PartyId, provider: PartyId, id: String): splitCodegen.GroupKey =
    splitCodegen.GroupKey(
      owner.toPrim,
      provider.toPrim,
      splitCodegen.GroupId(id),
    )

  // Commands for managing installs

  @Help.Summary("Create splitwise install proposal for given provider party")
  def createInstallProposal(
      provider: PartyId
  ): Primitive.ContractId[splitCodegen.SplitwiseInstallProposal] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      splitCodegen
        .SplitwiseInstallProposal(
          user = party.toPrim,
          provider = provider.toPrim,
        )
        .create,
    )
  }

  @Help.Summary("Accept splitwise install proposal")
  def acceptInstallProposal(
      proposal: Primitive.ContractId[splitCodegen.SplitwiseInstallProposal]
  ): Primitive.ContractId[splitCodegen.SplitwiseInstall] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      proposal.exerciseSplitwiseInstallProposal_Accept(),
    )
  }

  // Commands for the group owner

  @Help.Summary("Create group with the given id")
  def createGroup(provider: PartyId, id: String): Primitive.ContractId[splitCodegen.Group] = {
    val party = getUserPrimaryParty()
    val svc = remoteScan.getSvcPartyId()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(provider, party).exerciseSplitwiseInstall_CreateGroup(
        splitCodegen.Group(
          owner = party.toPrim,
          provider = provider.toPrim,
          svc = svc.toPrim,
          members = Seq.empty,
          id = splitCodegen.GroupId(id),
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
      provider: PartyId,
      id: String,
      observers: Seq[PartyId],
  ): Primitive.ContractId[splitCodegen.GroupInvite] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(provider, party).exerciseSplitwiseInstall_CreateInvite(
        groupKey_(party, provider, id),
        observers.map(_.toPrim),
      ),
    )
  }

  @Help.Summary("Add the invitee on the accepted group invite to the group")
  def joinGroup(
      provider: PartyId,
      acceptedGroupInvite: Primitive.ContractId[splitCodegen.AcceptedGroupInvite],
  ): Primitive.ContractId[splitCodegen.Group] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(provider, party).exerciseSplitwiseInstall_Join(
        acceptedGroupInvite
      ),
    )
  }

  // Member invite

  @Help.Summary("Accept the group invite")
  def acceptInvite(
      provider: PartyId,
      groupInvite: Primitive.ContractId[splitCodegen.GroupInvite],
  ): Primitive.ContractId[splitCodegen.AcceptedGroupInvite] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(provider, party).exerciseSplitwiseInstall_AcceptInvite(
        groupInvite
      ),
    )
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
  ): Primitive.ContractId[splitCodegen.BalanceUpdate] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(provider, party).exerciseSplitwiseInstall_EnterPayment(
        key.toPrim,
        quantity,
        description,
      ),
    )
  }

  @Help.Summary("Initiate a transfer to the receiver. Must be confirmed in the wallet.")
  def initiateTransfer(
      provider: PartyId,
      key: GrpcSplitwiseAppClient.GroupKey,
      receiver: PartyId,
      quantity: BigDecimal,
  ): Primitive.ContractId[walletCodegen.AppPaymentRequest] = {
    val party = getUserPrimaryParty()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      installKey(provider, party).exerciseSplitwiseInstall_InitiateTransfer(
        key.toPrim,
        receiver.toPrim,
        quantity,
      ),
    )
  }

  @Help.Summary(
    "Complete the transfer by actually transferring the coins and creating a balance update."
  )
  def completeTransfer(
      provider: PartyId,
      key: GrpcSplitwiseAppClient.GroupKey,
      acceptedPayment: Primitive.ContractId[walletCodegen.AcceptedAppPayment],
  ): Primitive.ContractId[splitCodegen.BalanceUpdate] = {
    val party = getUserPrimaryParty()
    // TODO(M1-06) Explicit disclosure workaround
    val hostedAt = ledgerApi.ledger_api.acs.await(
      party,
      CCUserHostedAt,
      predicate = (c: CodegenContract[CCUserHostedAt]) => c.value.user == party.toPrim,
    )
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq(PartyId.tryFromPrim(hostedAt.value.validator)),
      installKey(provider, party).exerciseSplitwiseInstall_CompleteTransfer(
        key.toPrim,
        acceptedPayment,
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
      provider: PartyId,
      key: GrpcSplitwiseAppClient.GroupKey,
      balanceChanges: Map[PartyId, Map[PartyId, BigDecimal]],
  ): Primitive.ContractId[splitCodegen.BalanceUpdate] = {
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
      installKey(provider, party).exerciseSplitwiseInstall_Net(
        key.toPrim,
        balanceChangesPrim,
      ),
    )
  }

  // Read operations

  @Help.Summary("List all groups")
  def listGroups(): Seq[Contract[splitCodegen.Group]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListGroups(context))
    }

  @Help.Summary("List all group invites that you have not already accepted")
  def listGroupInvites(): Seq[Contract[splitCodegen.GroupInvite]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListGroupInvites(context))
    }

  @Help.Summary("List accepted group invites for the given group that can be used in joinGroup")
  def listAcceptedGroupInvites(
      id: String
  ): Seq[Contract[splitCodegen.AcceptedGroupInvite]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.ListAcceptedGroupInvites(id, context))
    }

  @Help.Summary("List balance updates for the given group")
  def listBalanceUpdates(key: GrpcSplitwiseAppClient.GroupKey): Seq[
    Contract[splitCodegen.BalanceUpdate]
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

  @Help.Summary("Get the primary party of the provider’s daml user specified in the config.")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwiseAppClient.GetProviderPartyId())
    }
}

final class RemoteSplitwiseAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SplitwiseAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Remote Splitwise"

  override protected lazy val ledgerApi =
    new ExternalLedgerApiClient(
      config.ledgerApi.address,
      config.ledgerApi.port,
      config.ledgerApi.tls,
    )(consoleEnvironment)

  override protected val userId: String = config.damlUser

  override protected lazy val context =
    GrpcSplitwiseAppClient.SplitwiseContext(getUserPrimaryParty())

  @Help.Summary("Return remote splitwise app config")
  def config: RemoteSplitwiseAppConfig =
    consoleEnvironment.environment.config.remoteSplitwisesByString(name)

  override val remoteScanConfig = config.remoteScan
}

final class LocalSplitwiseAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SplitwiseAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Local Splitwise"

  override protected val nodes = consoleEnvironment.environment.splitwises

  override protected lazy val ledgerApi = remoteParticipant

  override protected val userId: String = config.damlUser

  // TODO(#661) Move commands to remote splitwise reference.
  override protected def context = throw new RuntimeException(
    "Commands must be run through remote splitwise ref"
  )

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
