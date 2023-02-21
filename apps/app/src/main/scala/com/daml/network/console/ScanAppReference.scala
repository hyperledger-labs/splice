package com.daml.network.console

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.config.CoinHttpClientConfig

/** Single scan app reference. Defines the console commands that can be run against a client or backend scan
  * app reference.
  */
abstract class ScanAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends HttpCoinAppReference {

  def getSvcPartyId(): PartyId =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetSvcPartyId(List()))
    }

  @Help.Summary(
    "Returns contracts required as inputs for a transfer."
  )
  def getTransferContext(): HttpScanAppClient.TransferContext =
    consoleEnvironment.run {
      httpCommand(HttpScanAppClient.GetTransferContext)
    }

  @Help.Summary(
    "Get the (cached) config schedule for the CoinRules. Note that changes to the config might take some time to propagate due to the client-side caching."
  )
  def getConfigSchedule(): Option[HttpScanAppClient.ConfigSchedule] = {
    getTransferContext().coinRules
      .map { cr =>
        HttpScanAppClient.ConfigSchedule(
          currentConfig = cr.payload.configSchedule.currentValue,
          futureConfigs = cr.payload.configSchedule.futureValues.asScala.map { t =>
            t._1 -> t._2
          }.toMap,
        )
      }
  }

  @Help.Summary(
    "Returns the transfer context required for third-party apps."
  )
  def getUnfeaturedAppTransferContext(): v1.coin.AppTransferContext = {
    def notFound(description: String) = new IllegalStateException(description)

    val transferContext = getTransferContext()
    val openMiningRound = transferContext.latestOpenMiningRound
    val coinRules =
      transferContext.coinRules.getOrElse(throw notFound("No active CoinRules contract"))
    new v1.coin.AppTransferContext(
      coinRules.contractId.toInterface(v1.coin.CoinRules.INTERFACE),
      openMiningRound.contractId.toInterface(v1.round.OpenMiningRound.INTERFACE),
      None.toJava,
    )
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
      httpCommand(HttpScanAppClient.GetOpenAndIssuingMiningRounds(Map(), Map()))
    }
    (result._1.values.toSeq, result._2.values.toSeq)
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
}

final class ScanAppBackendReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends ScanAppReference(coinConsoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Scan Backend"

  override def httpClientConfig = CoinHttpClientConfig.fromClientConfig(
    // For local references, we assume that they are reachable on localhost.
    // TODO (#2019) Reconsider if we want these for local refs at all and if so
    // if we should specify a url here.
    s"http://127.0.0.1:${config.clientAdminApi.port.unwrap + 1000}",
    config.clientAdminApi,
  )

  protected val nodes = coinConsoleEnvironment.environment.scans

  @Help.Summary("Return local scan app config")
  override def config: ScanAppBackendConfig =
    coinConsoleEnvironment.environment.config.scansByString(name)

  /** Remote participant this scan app is configured to interact with. */
  lazy val remoteParticipant =
    new CoinRemoteParticipantReference(
      coinConsoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this scan app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val remoteParticipantWithAdminToken =
    new CoinRemoteParticipantReference(
      coinConsoleEnvironment,
      s"remote participant for `$name`, with admin token",
      name,
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )
}

/** Remote reference to a scan app in the style of CoinRemoteParticipantReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class ScanAppClientReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: ScanAppClientConfig,
) extends ScanAppReference(coinConsoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "Scan Client"
}
