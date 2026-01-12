// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import org.apache.pekko.actor.ActorSystem
import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.javaapi.data.codegen.Update
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.*
import org.lfdecentralizedtrust.splice.environment.SpliceConsoleEnvironment
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.splitwell.{SplitwellApp, SplitwellAppBootstrap}
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import org.lfdecentralizedtrust.splice.splitwell.automation.SplitwellAutomationService
import org.lfdecentralizedtrust.splice.splitwell.config.{
  SplitwellAppBackendConfig,
  SplitwellAppClientConfig,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract, ContractWithState}
import com.digitalasset.canton.console.{
  BaseInspection,
  ExternalLedgerApiClient,
  Help,
  LedgerApiCommandRunner,
}
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.topology.{SynchronizerId, PartyId}

import scala.jdk.CollectionConverters.*

/** Splitwell app reference. Defines the console commands that can be run against either a client or backend splitwell reference.
  */
abstract class SplitwellAppReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
) extends HttpAppReference {

  override def basePath = "/api/splitwell"
  // We go through BaseLedgerApiAdministration here rather than creating a
  // ledger connection since that one is already setup to be easily used
  // from the console.
  def ledgerApi: BaseLedgerApiAdministration with LedgerApiCommandRunner

  protected val scanClientConfig: ScanAppClientConfig

  lazy val scanClient =
    new ScanAppClientReference(
      spliceConsoleEnvironment,
      s"scan client for `$name``",
      scanClientConfig,
    )

  @Help.Summary("Get the primary party of the provider’s daml user specified in the config.")
  def getProviderPartyId(): PartyId =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.GetProviderPartyId())
    }

  @Help.Summary("Get the domain ids for the private splitwell app domains")
  def getSplitwellSynchronizerIds(): HttpSplitwellAppClient.SplitwellDomains =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.GetSplitwellDomainIds())
    }

  @Help.Summary("Get the domain ids the party is hosted on")
  def getConnectedDomains(partyId: PartyId): Seq[SynchronizerId] =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.GetConnectedDomains(partyId))
    }
}

final class SplitwellAppClientReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    name: String,
    val config: SplitwellAppClientConfig, // adding this explicitly for easier overriding
)(implicit actorSystem: ActorSystem)
    extends SplitwellAppReference(spliceConsoleEnvironment, name) {
  private val acceptDuration = new RelTime(
    60_000_000
  )

  override protected val instanceType = "Splitwell Client"

  override def httpClientConfig = config.adminApi

  override lazy val ledgerApi: com.digitalasset.canton.console.ExternalLedgerApiClient =
    new ExternalLedgerApiClient(
      config.participantClient.ledgerApi.clientConfig.address,
      config.participantClient.ledgerApi.clientConfig.port,
      config.participantClient.ledgerApi.clientConfig.tls,
      config.participantClient.ledgerApi.getToken().map(_.accessToken),
    )(consoleEnvironment)

  val userId: String = config.ledgerApiUser

  lazy val userParty = getUserPrimaryParty()

  // return the install on the leftmost configured domain (e.g. preferred first)
  private def getFavoredSplitwellRules(): (
      SynchronizerId,
      Contract[splitwellCodegen.SplitwellRules.ContractId, splitwellCodegen.SplitwellRules],
  ) = {
    val rules = listSplitwellRules()
    val connectedDomains = getConnectedDomains(userParty)
    val filteredRules = rules.filter(c => connectedDomains.contains(c._1))
    filteredRules.toList match {
      case Seq((domain, domainRules)) => (domain, domainRules)
      case Seq() =>
        throw new IllegalStateException(
          s"Expected exactly one SplitwellRules contract for user $userParty but got $rules for connected domains $connectedDomains"
        )
      case multipleRules =>
        val domains = getSplitwellSynchronizerIds()
        multipleRules.minByOption { case (domain, _) =>
          if (domain == domains.preferred) 0
          else {
            val ix = domains.others indexOf domain
            if (ix == -1) Int.MaxValue else 1 + ix
          }
        } getOrElse sys.error("impossible - Seq() case skipped")
    }
  }

  private def getSplitwellRules(
      domain: SynchronizerId
  ): Contract[splitwellCodegen.SplitwellRules.ContractId, splitwellCodegen.SplitwellRules] = {
    val rules = listSplitwellRules()
    rules.getOrElse(
      domain, {
        throw new IllegalStateException(
          s"Expected a SplitwellRules contract for domain $domain but got $rules"
        )
      },
    )
  }

  private def getGroup(
      groupKey: splitwellCodegen.GroupKey
  ): (SynchronizerId, splitwellCodegen.Group.ContractId) =
    getGroup(
      HttpSplitwellAppClient.GroupKey(
        groupKey.id.unpack,
        PartyId.tryFromProtoPrimitive(groupKey.owner),
      )
    )

  private def getGroup(
      groupKey: HttpSplitwellAppClient.GroupKey
  ): (SynchronizerId, splitwellCodegen.Group.ContractId) = {
    val groups = listGroups().collect(Function unlift {
      case ContractWithState(contract, ContractState.Assigned(domain)) =>
        val group = contract.payload
        Option.when(
          group.owner == groupKey.owner.toProtoPrimitive && group.id.unpack == groupKey.id
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

  def submitWithResult[T](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      commandId: Option[String] = None,
      disclosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq.empty,
      synchronizerId: Option[SynchronizerId] = None,
  ): T = {
    import LedgerApiExtensions.*
    ledgerApi.ledger_api_extensions.commands.submitWithResult(
      userId,
      actAs,
      readAs,
      update,
      commandId,
      Some(synchronizerId.getOrElse(getSplitwellSynchronizerIds().preferred)),
      disclosedContracts,
    )
  }

  private def getUserPrimaryParty() = ledgerApi.ledger_api_extensions.users.getPrimaryParty(userId)

  // Commands for managing installs

  @Help.Summary("Create splitwell install requests per connected domain")
  def createInstallRequests(
  ): Map[SynchronizerId, splitwellCodegen.SplitwellInstallRequest.ContractId] = {
    val party = getUserPrimaryParty()
    val splitwellDomains = getSplitwellSynchronizerIds()
    val connectedDomains = getConnectedDomains(party)
    val connectedSplitwellDomains =
      (splitwellDomains.preferred +: splitwellDomains.others).filter(connectedDomains.contains(_))
    // We unconditionally create install contracts on all domains and rely on the provider's backend to reject them
    // for duplicates or if a domain is no longer supported.
    connectedSplitwellDomains.map { synchronizerId =>
      val rules = getSplitwellRules(synchronizerId)
      val exercised = submitWithResult(
        actAs = Seq(party),
        readAs = Seq.empty,
        rules.contractId.exerciseSplitwellRules_RequestInstall(
          party.toProtoPrimitive
        ),
        synchronizerId = Some(synchronizerId),
        disclosedContracts = Seq(rules.toDisclosedContract),
      )
      synchronizerId -> splitwellCodegen.SplitwellInstallRequest.COMPANION.toContractId(
        exercised.exerciseResult
      )
    }.toMap
  }

  // Commands for the group owner

  @Help.Summary("Request group with the given id")
  def requestGroup(id: String): splitwellCodegen.GroupRequest.ContractId = {
    val party = getUserPrimaryParty()
    val provider = getProviderPartyId()
    val dso = scanClient.getDsoPartyId()
    val (domain, rules) = getFavoredSplitwellRules()
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      rules.contractId.exerciseSplitwellRules_RequestGroup(
        new splitwellCodegen.Group(
          party.toProtoPrimitive,
          dso.toProtoPrimitive,
          Seq.empty.asJava,
          new splitwellCodegen.GroupId(id),
          provider.toProtoPrimitive,
          acceptDuration,
        ),
        party.toProtoPrimitive,
      ),
      synchronizerId = Some(domain),
      disclosedContracts = Seq(rules.toDisclosedContract),
    ).exerciseResult
  }

  @Help.Summary(
    "Create invite for the group with the given id and make it visible to the observers"
  )
  def createGroupInvite(
      id: String
  ): AssignedContract[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite] = {
    val party = getUserPrimaryParty()
    val (domain, group) = getGroup(HttpSplitwellAppClient.GroupKey(id, party))
    val rules = getSplitwellRules(domain)
    AssignedContract(
      ledgerApi.ledger_api_extensions.commands.submitWithCreate(
        splitwellCodegen.GroupInvite.COMPANION
      )(
        userId,
        Seq(party),
        Seq.empty,
        rules.contractId.exerciseSplitwellRules_CreateInvite(group, party.toProtoPrimitive),
        synchronizerId = Some(domain),
        disclosedContracts = Seq(rules.toDisclosedContract),
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
    val rules = getSplitwellRules(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      rules.contractId.exerciseSplitwellRules_Join(
        group,
        acceptedGroupInvite,
        party.toProtoPrimitive,
      ),
      synchronizerId = Some(domain),
      disclosedContracts = Seq(rules.toDisclosedContract),
    ).exerciseResult
  }

  // Member invite

  @Help.Summary("Accept the group invite")
  def acceptInvite(
      groupInvite: AssignedContract[
        splitwellCodegen.GroupInvite.ContractId,
        splitwellCodegen.GroupInvite,
      ]
  ): splitwellCodegen.AcceptedGroupInvite.ContractId = {
    val party = getUserPrimaryParty()
    val domain = groupInvite.domain
    val rules = getSplitwellRules(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      rules.contractId.exerciseSplitwellRules_AcceptInvite(
        groupInvite.contractId,
        party.toProtoPrimitive,
      ),
      disclosedContracts = Seq(rules.toDisclosedContract, groupInvite.contract.toDisclosedContract),
      synchronizerId = Some(domain),
    ).exerciseResult
  }

  // Member operations

  @Help.Summary(
    "Enter a payment to the group on your behalf. Payment amount is split equally between current group members."
  )
  def enterPayment(
      key: HttpSplitwellAppClient.GroupKey,
      amount: BigDecimal,
      description: String,
  ): splitwellCodegen.BalanceUpdate.ContractId = {
    val party = getUserPrimaryParty()
    val (domain, group) = getGroup(key)
    val rules = getSplitwellRules(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      rules.contractId.exerciseSplitwellRules_EnterPayment(
        group,
        amount.bigDecimal,
        description,
        party.toProtoPrimitive,
      ),
      disclosedContracts = Seq(rules.toDisclosedContract),
      synchronizerId = Some(domain),
    ).exerciseResult
  }

  @Help.Summary("Initiate a transfer. Must be confirmed in the wallet.")
  def initiateTransfer(
      key: HttpSplitwellAppClient.GroupKey,
      receiverAmounts: Seq[walletCodegen.ReceiverAmuletAmount],
  ): walletCodegen.AppPaymentRequest.ContractId = {
    val party = getUserPrimaryParty()
    val (domain, group) = getGroup(key)
    val rules = getSplitwellRules(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      rules.contractId.exerciseSplitwellRules_InitiateTransfer(
        group,
        receiverAmounts.asJava,
        party.toProtoPrimitive,
      ),
      synchronizerId = Some(domain),
      disclosedContracts = Seq(rules.toDisclosedContract),
    ).exerciseResult._2
  }

  @Help.Summary("Net balances of the parties in the group.")
  @Help.Description(
    """This allows us to emulate [splitwell simplify debt feature](https://www.splitwell.com/l/sdv/FgPQSo3Bsev).
      |E.g., if Alice owes Bob 10Amulet, and Charlie owes Alice 10Amulet, we can net that to Charlie owing Bob 10Amulet.
      |Note that we do not enforce that the resulting balances are simpler but we do enforce
      |that the total balance of each party stays the same.
      |"""
  )
  def net(
      key: HttpSplitwellAppClient.GroupKey,
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
    val (domain, group) = getGroup(key)
    val rules = getSplitwellRules(domain)
    submitWithResult(
      actAs = Seq(party),
      readAs = Seq.empty,
      rules.contractId.exerciseSplitwellRules_Net(
        group,
        balanceChangesPrim,
        party.toProtoPrimitive,
      ),
      synchronizerId = Some(domain),
      disclosedContracts = Seq(rules.toDisclosedContract),
    ).exerciseResult
  }

  // Read operations

  @Help.Summary("List all groups")
  def listGroups()
      : Seq[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]] =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.ListGroups(userParty))
    }

  @Help.Summary("List all group invites that you have not already accepted")
  def listGroupInvites(): Seq[
    ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.ListGroupInvites(userParty))
    }

  @Help.Summary("List accepted group invites for the given group that can be used in joinGroup")
  def listAcceptedGroupInvites(
      id: String
  ): Seq[
    Contract[splitwellCodegen.AcceptedGroupInvite.ContractId, splitwellCodegen.AcceptedGroupInvite]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.ListAcceptedGroupInvites(userParty, id))
    }

  @Help.Summary("List balance updates for the given group")
  def listBalanceUpdates(key: HttpSplitwellAppClient.GroupKey): Seq[
    ContractWithState[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.ListBalanceUpdates(userParty, key))
    }

  @Help.Summary(
    "List balances for the given group. Positive balance means that party owes you, negative balance means you owe that party."
  )
  def listBalances(key: HttpSplitwellAppClient.GroupKey): Map[PartyId, BigDecimal] =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.ListBalances(userParty, key))
    }

  def listSplitwellInstalls(): Map[SynchronizerId, splitwellCodegen.SplitwellInstall.ContractId] =
    consoleEnvironment.run {
      httpCommand(HttpSplitwellAppClient.ListSplitwellInstalls(userParty))
    }

  def listSplitwellRules(): Map[SynchronizerId, Contract[
    splitwellCodegen.SplitwellRules.ContractId,
    splitwellCodegen.SplitwellRules,
  ]] =
    consoleEnvironment
      .run {
        httpCommand(HttpSplitwellAppClient.ListSplitwellRules)
      }
      .map(c => c.domain -> c.contract)
      .toMap

  override val scanClientConfig = config.scanClient
}

final class SplitwellAppBackendReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends SplitwellAppReference(consoleEnvironment, name)
    with AppBackendReference
    with BaseInspection[SplitwellApp] {

  override def runningNode: Option[SplitwellAppBootstrap] =
    consoleEnvironment.environment.splitwells.getRunning(name)

  override def startingNode: Option[SplitwellAppBootstrap] =
    consoleEnvironment.environment.splitwells.getStarting(name)

  override protected val instanceType = "Splitwell Backend"

  override def httpClientConfig = NetworkAppClientConfig(
    s"http://127.0.0.1:${config.clientAdminApi.port}"
  )

  override val nodes: org.lfdecentralizedtrust.splice.environment.SplitwellApps =
    consoleEnvironment.environment.splitwells

  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  def appState: SplitwellApp.State = _appState[SplitwellApp.State, SplitwellApp]

  @Help.Summary(
    "Returns the automation service for the splitwell application. May only be called while the app is running."
  )
  def splitwellAutomation: SplitwellAutomationService = appState.automation

  override lazy val ledgerApi: SplitwellAppBackendReference.this.participantClient.type =
    participantClient

  @Help.Summary("Return local splitwell app config")
  def config: SplitwellAppBackendConfig =
    consoleEnvironment.environment.config.splitwellsByString(name)

  override val scanClientConfig = config.scanClient

  /** Remote participant this splitwell app is configured to interact with. */
  lazy val participantClient =
    new ParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this splitwell app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new ParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )
}
