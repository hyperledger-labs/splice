package com.daml.network.console

import akka.actor.ActorSystem
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
import com.daml.network.store.MultiDomainAcsStore.{ContractWithState, ContractState, ReadyContract}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  GrpcRemoteInstanceReference,
  Help,
  LedgerApiCommandRunner,
}
import com.digitalasset.canton.console.commands.{BaseLedgerApiAdministration}
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

  protected val scanClientConfig: ScanAppClientConfig

  lazy val scanClient =
    new ScanAppClientReference(
      cnNodeConsoleEnvironment,
      s"scan client for `$name``",
      scanClientConfig,
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

  @Help.Summary("Get the domain ids the party is hosted on")
  def getConnectedDomains(partyId: PartyId): Seq[DomainId] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.GetConnectedDomains(partyId))
    }
}

final class SplitwellAppClientReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: SplitwellAppClientConfig, // adding this explicitly for easier overriding
)(implicit actorSystem: ActorSystem)
    extends SplitwellAppReference(cnNodeConsoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {
  private val acceptDuration = new RelTime(
    60_000_000
  )

  override protected val instanceType = "Splitwell Client"

  override lazy val ledgerApi =
    new ExternalLedgerApiClient(
      config.participantClient.ledgerApi.clientConfig.address,
      config.participantClient.ledgerApi.clientConfig.port,
      config.participantClient.ledgerApi.clientConfig.tls,
      config.participantClient.ledgerApi.getToken().map(_.accessToken),
    )(consoleEnvironment)

  val userId: String = config.ledgerApiUser

  lazy val context =
    GrpcSplitwellAppClient.SplitwellContext(getUserPrimaryParty())

  // return the install on the leftmost configured domain (e.g. preferred first)
  private def getFavoredSplitwellInstall()
      : (DomainId, splitwellCodegen.SplitwellInstall.ContractId) = {
    val installs = listSplitwellInstalls()
    installs.toList match {
      case Seq((domain, install)) => (domain, install)
      case Seq() =>
        val userParty = getUserPrimaryParty()
        throw new IllegalStateException(
          s"Expected exactly one SplitwellInstall contract for user $userParty but got $installs"
        )
      case multipleInstalls =>
        val domains = getSplitwellDomainIds()
        // by listSplitwellInstalls's sig, each DomainId is unique
        multipleInstalls.minByOption { case (domain, _) =>
          if (domain == domains.preferred) 0
          else {
            val ix = domains.others indexOf domain
            if (ix == -1) Int.MaxValue else 1 + ix
          }
        } getOrElse sys.error("impossible - Seq() case skipped")
    }
  }

  private def getSplitwellInstall(
      domain: DomainId
  ): splitwellCodegen.SplitwellInstall.ContractId = {
    val installs = listSplitwellInstalls()
    installs.getOrElse(
      domain, {
        val userParty = getUserPrimaryParty()
        throw new IllegalStateException(
          s"Expected a SplitwellInstall contract for user $userParty, domain $domain but got $installs"
        )
      },
    )
  }

  private def getGroup(
      groupKey: splitwellCodegen.GroupKey
  ): (DomainId, splitwellCodegen.Group.ContractId) = {
    val groups = listGroups().collect(Function unlift {
      case ContractWithState(contract, ContractState.Assigned(domain)) =>
        val group = contract.payload
        Option.when(
          group.provider == groupKey.provider && group.owner == groupKey.owner
            && group.id == groupKey.id
        )((domain, contract.contractId))
      case _ => None
    })
    groups match {
      case Seq(group) => group
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
    val connectedDomains = getConnectedDomains(party)
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
    val svc = scanClient.getSvcPartyId()
    val (domain, install) = getFavoredSplitwellInstall()
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
  ): ReadyContract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite] = {
    val party = getUserPrimaryParty()
    val (domain, group) = getGroup(groupKey_(party, id))
    val install = getSplitwellInstall(domain)
    ReadyContract(
      ledgerApi.ledger_api_extensions.commands.submitWithCreate(
        splitwellCodegen.GroupInvite.COMPANION
      )(
        userId,
        Seq(party),
        Seq.empty,
        install.exerciseSplitwellInstall_CreateInvite(group),
        domainId = Some(domain),
      ),
      domain,
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
    val (domain, group) = getGroup(acceptedInvite.data.groupKey)
    val install = getSplitwellInstall(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_Join(
        group,
        acceptedGroupInvite,
      ),
      domainId = Some(domain),
    ).exerciseResult
  }

  // Member invite

  @Help.Summary("Accept the group invite")
  def acceptInvite(
      groupInvite: ReadyContract[
        splitwellCodegen.GroupInvite.ContractId,
        splitwellCodegen.GroupInvite,
      ]
  ): splitwellCodegen.AcceptedGroupInvite.ContractId = {
    val party = getUserPrimaryParty()
    val domain = groupInvite.domain
    val install = getSplitwellInstall(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_AcceptInvite(
        groupInvite.contract.contractId
      ),
      disclosedContracts = Seq(groupInvite.contract.toDisclosedContract),
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
    val (domain, group) = getGroup(key.toPrim)
    val install = getSplitwellInstall(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_EnterPayment(
        group,
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
    val (domain, group) = getGroup(key.toPrim)
    val install = getSplitwellInstall(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_InitiateTransfer(
        group,
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
    val (domain, group) = getGroup(key.toPrim)
    val install = getSplitwellInstall(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      install.exerciseSplitwellInstall_Net(
        group,
        balanceChangesPrim,
      ),
      domainId = Some(domain),
    ).exerciseResult
  }

  // Read operations

  @Help.Summary("List all groups")
  def listGroups()
      : Seq[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]] =
    consoleEnvironment.run {
      adminCommand(GrpcSplitwellAppClient.ListGroups(context))
    }

  @Help.Summary("List all group invites that you have not already accepted")
  def listGroupInvites(): Seq[
    ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
  ] =
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

  override val scanClientConfig = config.scanClient
}

final class SplitwellAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends SplitwellAppReference(consoleEnvironment, name)
    with CNNodeAppBackendReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Splitwell Backend"

  override protected val nodes = consoleEnvironment.environment.splitwells

  override lazy val ledgerApi = participantClient

  @Help.Summary("Return local splitwell app config")
  def config: SplitwellAppBackendConfig =
    consoleEnvironment.environment.config.splitwellsByString(name)

  override val scanClientConfig = config.scanClient

  /** Remote participant this splitwell app is configured to interact with. */
  lazy val participantClient =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this splitwell app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )
}
