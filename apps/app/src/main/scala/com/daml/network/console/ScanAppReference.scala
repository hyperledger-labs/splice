package com.daml.network.console

import org.apache.pekko.actor.ActorSystem
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinrules.{AppTransferContext, CoinRules}
import com.daml.network.codegen.java.cc.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
}
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsRules}
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.http.v0.definitions
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import com.daml.network.util.{CNNodeUtil, CoinConfigSchedule, Contract, ContractWithState}
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}

import java.time.Instant

/** Single scan app reference. Defines the console commands that can be run against a client or backend scan
  * app reference.
  */
abstract class ScanAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override def basePath = "/api/scan"

  def getSvcPartyId(): PartyId =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetSvcPartyId(List()))
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
        CNNodeUtil.selectSpecificOpenMiningRound(now, openRounds, specifiedRound)
      case None =>
        CNNodeUtil.selectLatestOpenMiningRound(now, openRounds)
    }
    val coinRules = getCoinRules()
    TransferContextWithInstances(coinRules, latestOpenMiningRound, openRounds)
  }

  @Help.Summary(
    "Returns last-created open mining round that is open according to the passed time. "
  )
  def getLatestOpenMiningRound(
      now: CantonTimestamp
  ): ContractWithState[OpenMiningRound.ContractId, OpenMiningRound] = {

    val (openRounds, _) = getOpenAndIssuingMiningRounds()
    CNNodeUtil.selectLatestOpenMiningRound(now, openRounds)
  }

  @Help.Summary(
    "Returns the CoinRules."
  )
  def getCoinRules(): ContractWithState[CoinRules.ContractId, CoinRules] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetCoinRules(None))
    }

  @Help.Summary(
    "Returns the CnsRules."
  )
  def getCnsRules(): ContractWithState[CnsRules.ContractId, CnsRules] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetCnsRules(None))
    }

  @Help.Summary("List cns entries")
  @Help.Description(
    "Lists all cns entries whose name is prefixed with the given prefix, up to a given number of entries"
  )
  def listEntries(
      namePrefix: String,
      pageSize: Int,
  ): Seq[Contract[CnsEntry.ContractId, CnsEntry]] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.ListCnsEntries(namePrefix, pageSize))
    }

  @Help.Summary("Lookup a cns entry by the party that registered it")
  def lookupEntryByParty(
      party: PartyId
  ): Contract[CnsEntry.ContractId, CnsEntry] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.LookupCnsEntryByParty(party))
    }

  @Help.Summary("Lookup a cns entry by its name")
  def lookupEntryByName(
      name: String
  ): Contract[CnsEntry.ContractId, CnsEntry] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.LookupCnsEntryByName(name))
    }

  @Help.Summary(
    "Get the (cached) coin config effective now. Note that changes to the config might take some time to propagate due to the client-side caching."
  )
  def getCoinConfigAsOf(now: CantonTimestamp): cc.coinconfig.CoinConfig[cc.coinconfig.USD] = {
    CoinConfigSchedule(getTransferContextWithInstances(now).coinRules).getConfigAsOf(now)
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

  @Help.Summary("Get the total balance of Canton Coin in the network")
  def getTotalCoinBalance(asOfEndOfRound: Long): BigDecimal =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetTotalCoinBalance(asOfEndOfRound))
    }

  @Help.Summary("Get the Canton Coin config parameters for a given round")
  def getCoinConfigForRound(
      round: Long
  ): HttpScanAppClient.CoinConfig =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetCoinConfigForRound(round))
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
      domainId: DomainId,
      memberId: Member,
  ): definitions.MemberTrafficStatus =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetMemberTrafficStatus(domainId, memberId))
    }

  @Help.Summary(
    "List the import crates available for a party"
  )
  def listImportCrates(
      party: PartyId
  ): Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]] =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ListImportCrates(party)
      )
    }

  @Help.Summary(
    "List the SVC sequencers"
  )
  def listSvcSequencers(): Seq[HttpScanAppClient.DomainSequencers] =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.ListSvcSequencers()
      )
    }

  import com.daml.network.http.v0.definitions.TransactionHistoryResponseItem
  import com.daml.network.http.v0.definitions.TransactionHistoryRequest.SortOrder

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
}

final class ScanAppBackendReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends ScanAppReference(cnNodeConsoleEnvironment, name)
    with CNNodeAppBackendReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Scan Backend"

  override def httpClientConfig =
    NetworkAppClientConfig(s"http://127.0.0.1:${config.clientAdminApi.port}")

  val nodes = cnNodeConsoleEnvironment.environment.scans

  @Help.Summary("Return local scan app config")
  override def config: ScanAppBackendConfig =
    cnNodeConsoleEnvironment.environment.config.scansByString(name)

  /** Remote participant this scan app is configured to interact with. */
  lazy val participantClient =
    new CNParticipantClientReference(
      cnNodeConsoleEnvironment,
      s"remote participant for `$name``",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this scan app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new CNParticipantClientReference(
      cnNodeConsoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )
}

/** Remote reference to a scan app in the style of CNParticipantClientReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class ScanAppClientReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: ScanAppClientConfig,
) extends ScanAppReference(cnNodeConsoleEnvironment, name)
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "Scan Client"
}
