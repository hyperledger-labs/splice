// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AppTransferContext,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.SpliceConsoleEnvironment
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.GetDsoInfoResponse
import org.lfdecentralizedtrust.splice.scan.{ScanApp, ScanAppBootstrap}
import org.lfdecentralizedtrust.splice.scan.automation.ScanAutomationService
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import org.lfdecentralizedtrust.splice.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  Contract,
  ContractWithState,
  PackageQualifiedName,
  SpliceUtil,
}
import com.digitalasset.canton.console.{BaseInspection, ConsoleCommandResult, Help}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{SynchronizerId, Member, ParticipantId, PartyId}
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1

import java.time.Instant

/** Single scan app reference. Defines the console commands that can be run against a client or backend scan
  * app reference.
  */
abstract class ScanAppReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
) extends HttpAppReference {

  override def basePath = "/api/scan"

  def getDsoPartyId(): PartyId =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetDsoPartyId(List()))
    }

  def getDsoInfo(): GetDsoInfoResponse = {
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetDsoInfo(List()))
    }
  }

  @Help.Summary(
    "Returns contracts required as inputs for a transfer."
  )
  def getTransferContextWithInstances(
      now: CantonTimestamp,
      specificRound: Option[Round] = None,
  ): HttpScanAppClient.TransferContextWithInstances = {
    val openAndIssuingRounds = getOpenAndIssuingMiningRounds()
    val openRounds = openAndIssuingRounds._1
    val latestOpenMiningRound = specificRound match {
      case Some(specifiedRound) =>
        SpliceUtil.selectSpecificOpenMiningRound(now, openRounds, specifiedRound)
      case None =>
        SpliceUtil.selectLatestOpenMiningRound(now, openRounds)
    }
    val amuletRules = getAmuletRules()
    TransferContextWithInstances(amuletRules, latestOpenMiningRound, openRounds)
  }

  @Help.Summary(
    "Returns last-created open mining round that is open according to the passed time. "
  )
  def getLatestOpenMiningRound(
      now: CantonTimestamp
  ): ContractWithState[OpenMiningRound.ContractId, OpenMiningRound] = {

    val (openRounds, _) = getOpenAndIssuingMiningRounds()
    SpliceUtil.selectLatestOpenMiningRound(now, openRounds)
  }

  @Help.Summary(
    "Returns the AmuletRules."
  )
  def getAmuletRules(): ContractWithState[AmuletRules.ContractId, AmuletRules] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetAmuletRules(None))
    }

  @Help.Summary(
    "Returns the ExternalPartyAmuletRules."
  )
  def getExternalPartyAmuletRules()
      : ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetExternalPartyAmuletRules(None))
    }

  @Help.Summary(
    "Returns the AnsRules."
  )
  def getAnsRules(): ContractWithState[AnsRules.ContractId, AnsRules] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetAnsRules(None))
    }

  @Help.Summary("List ans entries")
  @Help.Description(
    "Lists all ans entries whose name is prefixed with the given prefix, up to a given number of entries"
  )
  def listEntries(
      namePrefix: String,
      pageSize: Int,
  ): Seq[definitions.AnsEntry] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.ListAnsEntries(Some(namePrefix), pageSize))
    }

  @Help.Summary("Lookup a ans entry by the party that registered it")
  def lookupEntryByParty(
      party: PartyId
  ): Option[definitions.AnsEntry] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.LookupAnsEntryByParty(party))
    }

  @Help.Summary("Lookup a ans entry by its name")
  def lookupEntryByName(
      name: String
  ): definitions.AnsEntry =
    consoleEnvironment
      .run {
        httpCommand(HttpScanAppClient.LookupAnsEntryByName(name))
          .flatMap(optContract =>
            ConsoleCommandResult.fromEither(optContract.toRight(s"Entry with name $name not found"))
          )
      }

  @Help.Summary("Lookup a TransferPreapproval by the receiver party")
  def lookupTransferPreapprovalByParty(
      party: PartyId
  ): Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.LookupTransferPreapprovalByParty(party))
    }

  @Help.Summary("Lookup a TransferCommandCounter by the receiver party")
  def lookupTransferCommandCounterByParty(
      party: PartyId
  ): Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.LookupTransferCommandCounterByParty(party))
    }

  @Help.Summary("Lookup the status of a TransferCommand")
  def lookupTransferCommandStatus(
      sender: PartyId,
      nonce: Long,
  ): Option[definitions.LookupTransferCommandStatusResponse] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.LookupTransferCommandStatus(sender, nonce))
    }

  @Help.Summary(
    "Get the (cached) amulet config effective now. Note that changes to the config might take some time to propagate due to the client-side caching."
  )
  def getAmuletConfigAsOf(
      now: CantonTimestamp
  ): splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD] = {
    AmuletConfigSchedule(getTransferContextWithInstances(now).amuletRules).getConfigAsOf(now)
  }

  @Help.Summary(
    "Returns the transfer context required for third-party apps."
  )
  def getUnfeaturedAppTransferContext(now: CantonTimestamp): AppTransferContext = {
    getTransferContextWithInstances(now).toUnfeaturedAppTransferContext()
  }

  @Help.Summary(
    "Lists all closed rounds with their collected statistics"
  )
  def getClosedRounds(): Seq[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetClosedRounds)
    }

  @Help.Summary(
    "List the latest open mining round and all issuing mining rounds."
  )
  def getOpenAndIssuingMiningRounds(): (
      Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  ) = {
    val result = consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(Seq(), Seq()))
    }
    (
      result._1.sortBy(_.payload.round.number),
      result._2.sortBy(_.payload.round.number),
    )
  }

  @Help.Summary("List all issued featured app rights")
  def listFeaturedAppRights(): Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.ListFeaturedAppRight)
    }

  def lookupFeaturedAppRight(
      providerPartyId: PartyId
  ): Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.LookupFeaturedAppRight(providerPartyId))
    }

  @Help.Summary("Get the total balance of Amulet in the network")
  def getTotalAmuletBalance(asOfEndOfRound: Long): Option[BigDecimal] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetTotalAmuletBalance(asOfEndOfRound))
    }

  @Help.Summary("Get the Amulet config parameters for a given round")
  def getAmuletConfigForRound(
      round: Long
  ): HttpScanAppClient.AmuletConfig =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetAmuletConfigForRound(round))
    }

  @Help.Summary(
    "Get the latest round number for which aggregated data is available and the ledger effective time at which the round was closed"
  )
  def getRoundOfLatestData(): (Long, Instant) =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetRoundOfLatestData())
    }

  @Help.Summary(
    "Get the total rewards collected ever"
  )
  def getTotalRewardsCollectedEver(): BigDecimal =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetRewardsCollected(None))
    }

  @Help.Summary(
    "Get the total rewards collected in a specific round"
  )
  def getRewardsCollectedInRound(round: Long): BigDecimal =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetRewardsCollected(Some(round)))
    }

  @Help.Summary(
    "Get a list of top-earning app providers, and the total earned app rewards for each"
  )
  def getTopProvidersByAppRewards(round: Long, limit: Int): Seq[(PartyId, BigDecimal)] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.getTopProvidersByAppRewards(round, limit))
    }

  @Help.Summary(
    "Get a list of top-earning validators, and the total earned validator rewards for each"
  )
  def getTopValidatorsByValidatorRewards(round: Long, limit: Int): Seq[(PartyId, BigDecimal)] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.getTopValidatorsByValidatorRewards(round, limit))
    }

  @Help.Summary(
    "Get a list of validators and their domain fees spends, sorted by the amount of extra traffic purchased"
  )
  def getTopValidatorsByPurchasedTraffic(
      round: Long,
      limit: Int,
  ): Seq[HttpScanAppClient.ValidatorPurchasedTraffic] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetTopValidatorsByPurchasedTraffic(round, limit))
    }

  @Help.Summary(
    "Get a member's (participant or mediator) traffic status as reported by the sequencer"
  )
  def getMemberTrafficStatus(
      synchronizerId: SynchronizerId,
      memberId: Member,
  ): definitions.MemberTrafficStatus =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetMemberTrafficStatus(synchronizerId, memberId))
    }

  @Help.Summary(
    "Get the id of the participant hosting a given party"
  )
  def getPartyToParticipant(
      synchronizerId: SynchronizerId,
      partyId: PartyId,
  ): ParticipantId =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetPartyToParticipant(synchronizerId, partyId))
    }

  @Help.Summary(
    "List the DSO sequencers"
  )
  def listDsoSequencers(): Seq[HttpScanAppClient.DomainSequencers] =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ListDsoSequencers()
      )
    }

  import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryResponseItem
  import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest.SortOrder

  def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      pageSize: Int,
  ): Seq[TransactionHistoryResponseItem] =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ListTransactions(pageEndEventId, sortOrder, pageSize)
      )
    }

  def listActivity(
      pageEndEventId: Option[String],
      pageSize: Int,
  ): Seq[TransactionHistoryResponseItem] =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ListTransactions(pageEndEventId, SortOrder.Desc, pageSize)
      )
    }

  def getAcsSnapshot(party: PartyId): ByteString =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetAcsSnapshot(party)
      )
    }

  def forceAcsSnapshotNow() =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ForceAcsSnapshotNow
      )
    }

  def getDateOfMostRecentSnapshotBefore(before: CantonTimestamp, migrationId: Long) =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetDateOfMostRecentSnapshotBefore(
          before.toInstant.atOffset(java.time.ZoneOffset.UTC),
          migrationId,
        )
      )
    }

  def getAcsSnapshotAt(
      at: CantonTimestamp,
      migrationId: Long,
      after: Option[Long] = None,
      pageSize: Int = 100,
      partyIds: Option[Vector[PartyId]] = None,
      templates: Option[Vector[PackageQualifiedName]] = None,
  ) =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetAcsSnapshotAt(
          at.toInstant.atOffset(java.time.ZoneOffset.UTC),
          migrationId,
          after,
          pageSize,
          partyIds,
          templates,
        )
      )
    }

  def getHoldingsStateAt(
      at: CantonTimestamp,
      migrationId: Long,
      partyIds: Vector[PartyId],
      after: Option[Long] = None,
      pageSize: Int = 100,
  ) =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetHoldingsStateAt(
          at.toInstant.atOffset(java.time.ZoneOffset.UTC),
          migrationId,
          partyIds,
          after,
          pageSize,
        )
      )
    }

  def getAggregatedRounds(): Option[ScanAggregator.RoundRange] =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetAggregatedRounds
      )
    }

  def listRoundTotals(start: Long, end: Long) =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ListRoundTotals(start, end)
      )
    }

  def listRoundPartyTotals(start: Long, end: Long) =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ListRoundPartyTotals(start, end)
      )
    }

  @deprecated(message = "Use getUpdateHistory instead", since = "0.2.5")
  def getUpdateHistoryV0(
      count: Int,
      after: Option[(Long, String)],
      lossless: Boolean,
  ) = {
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetUpdateHistoryV0(count, after, lossless)
      )
    }
  }
  def getUpdateHistory(
      count: Int,
      after: Option[(Long, String)],
      encoding: definitions.DamlValueEncoding,
  ) = {
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetUpdateHistory(count, after, encoding)
      )
    }
  }
  def getUpdate(updateId: String, encoding: definitions.DamlValueEncoding) = {
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.GetUpdate(updateId, encoding)
      )
    }
  }

  def getSpliceInstanceNames() = {
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetSpliceInstanceNames())
    }
  }

  def getTransferFactory(choiceArgs: transferinstructionv1.TransferFactory_Transfer) = {
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetTransferFactory(choiceArgs))
    }
  }

}

final class ScanAppBackendReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends ScanAppReference(spliceConsoleEnvironment, name)
    with AppBackendReference
    with BaseInspection[ScanApp] {

  override def runningNode: Option[ScanAppBootstrap] =
    spliceConsoleEnvironment.environment.scans.getRunning(name)

  override def startingNode: Option[ScanAppBootstrap] =
    spliceConsoleEnvironment.environment.scans.getStarting(name)

  override protected val instanceType = "Scan Backend"

  override def httpClientConfig =
    NetworkAppClientConfig(s"http://127.0.0.1:${config.clientAdminApi.port}")

  val nodes: org.lfdecentralizedtrust.splice.environment.ScanApps =
    spliceConsoleEnvironment.environment.scans

  @Help.Summary("Return local scan app config")
  override def config: ScanAppBackendConfig =
    spliceConsoleEnvironment.environment.config.scansByString(name)

  /** Remote participant this scan app is configured to interact with. */
  lazy val participantClient =
    new ParticipantClientReference(
      spliceConsoleEnvironment,
      s"remote participant for `$name``",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this scan app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new ParticipantClientReference(
      spliceConsoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )
  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  def appState: ScanApp.State = _appState[ScanApp.State, ScanApp]

  @Help.Summary(
    "Returns the current Scan automation."
  )
  def automation: ScanAutomationService = {
    appState.automation
  }
}

/** Remote reference to a scan app in the style of ParticipantClientReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class ScanAppClientReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    name: String,
    val config: ScanAppClientConfig,
) extends ScanAppReference(spliceConsoleEnvironment, name) {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "Scan Client"
}
