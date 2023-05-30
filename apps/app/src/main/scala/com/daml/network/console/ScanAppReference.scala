package com.daml.network.console

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import com.daml.network.util.{CNNodeUtil, CoinConfigSchedule, Contract}
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

import java.time.Instant

/** Single scan app reference. Defines the console commands that can be run against a client or backend scan
  * app reference.
  */
abstract class ScanAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  def getSvcPartyId(): PartyId =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetSvcPartyId(List()))
    }

  @Help.Summary(
    "Returns contracts required as inputs for a transfer."
  )
  def getTransferContextWithInstances(
      now: CantonTimestamp
  ): HttpScanAppClient.TransferContextWithInstances = {
    val openAndIssuingRounds = getOpenAndIssuingMiningRounds()
    val openRounds = openAndIssuingRounds._1
    val latestOpenMiningRound = CNNodeUtil.selectLatestOpenMiningRound(now, openRounds)
    val coinRules = getCoinRules()
    TransferContextWithInstances(coinRules, latestOpenMiningRound, openRounds)
  }

  @Help.Summary(
    "Returns last-created open mining round that is open according to the passed time. "
  )
  def getLatestOpenMiningRound(
      now: CantonTimestamp
  ): Contract[OpenMiningRound.ContractId, OpenMiningRound] = {

    val (openRounds, _) = getOpenAndIssuingMiningRounds()
    CNNodeUtil.selectLatestOpenMiningRound(now, openRounds)
  }

  @Help.Summary(
    "Returns the CoinRules."
  )
  def getCoinRules(): Contract[CoinRules.ContractId, CoinRules] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetCoinRules(None))
    }

  @Help.Summary(
    "Returns an upgraded CoinRules (for testing upgrades)"
  )
  def getCoinRulesV1Test(): Contract[CoinRulesV1Test.ContractId, CoinRulesV1Test] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetCoinRulesV1Test(None))
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
  def getUnfeaturedAppTransferContext(now: CantonTimestamp): v1.coin.AppTransferContext = {
    getTransferContextWithInstances(now).toUnfeaturedAppTransferContext()
  }

  @Help.Summary(
    "Lists all closed rounds with their collected statistics"
  )
  def getClosedRounds()
      : Seq[Contract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetClosedRounds)
    }

  @Help.Summary(
    "List the latest open mining round and all issuing mining rounds."
  )
  def getOpenAndIssuingMiningRounds(): (
      Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]],
      Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
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
  def getTotalCoinBalance(): HttpScanAppClient.TotalBalances =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetTotalCoinBalance)
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
      limit: Int
  ): Seq[HttpScanAppClient.ValidatorPurchasedTraffic] =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetTopValidatorsByPurchasedTraffic(limit))
    }

  @Help.Summary("Get the available traffic balance for a validator")
  def getValidatorTrafficBalance(
      validatorParty: PartyId
  ): HttpScanAppClient.ValidatorTrafficBalance =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetValidatorTrafficBalance(validatorParty))
    }

  @Help.Summary(
    "Atomically check if the validator has any traffic balance available and if so, consume 1 unit"
  )
  def checkAndUpdateValidatorTrafficBalance(validatorParty: PartyId): Boolean =
    consoleEnvironment.run {
      httpCommand(
        HttpScanAppClient.CheckAndUpdateValidatorTrafficBalance(validatorParty)
      )
    }
}

final class ScanAppBackendReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
) extends ScanAppReference(cnNodeConsoleEnvironment, name)
    with CNNodeAppBackendReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Scan Backend"

  override def httpClientConfig =
    NetworkAppClientConfig(s"http://127.0.0.1:${config.clientAdminApi.port}")

  protected val nodes = cnNodeConsoleEnvironment.environment.scans

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
