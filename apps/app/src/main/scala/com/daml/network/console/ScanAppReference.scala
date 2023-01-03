package com.daml.network.console

import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.{round => roundCodegen}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.history.{CoinTransaction, CoinTransactionTreeView}
import com.daml.network.scan.admin.api.client.commands.GrpcScanAppClient
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanAppClientConfig}
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

/** Single scan app reference. Defines the console commands that can be run against a client or backend scan
  * app reference.
  */
abstract class ScanAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  def getSvcPartyId(): PartyId =
    consoleEnvironment.run {
      adminCommand(GrpcScanAppClient.GetSvcPartyId())
    }

  @Help.Summary(
    "Returns a list of all transactions that included the creation of archive of a Canton coin. "
  )
  @Help.Description("Transaction are ordered by transaction offset in ascending order.")
  def getTxHistory(): Seq[CoinTransaction] =
    consoleEnvironment.run {
      adminCommand(GrpcScanAppClient.GetHistory())
    }

  @Help.Summary(
    "Returns contracts required as inputs for a transfer."
  )
  def getTransferContext(): GrpcScanAppClient.TransferContext =
    consoleEnvironment.run {
      adminCommand(GrpcScanAppClient.GetTransferContext())
    }

  @Help.Summary(
    "Returns the transfer context required for third-party apps."
  )
  def getAppTransferContext(): v1.coin.AppTransferContext = {
    def notFound(description: String) = new IllegalStateException(description)
    val transferContext = getTransferContext()
    val openMiningRound = transferContext.latestOpenMiningRound.getOrElse(
      throw notFound("No active OpenMiningRound contract")
    )
    val coinRules =
      transferContext.coinRules.getOrElse(throw notFound("No active CoinRules contract"))
    new v1.coin.AppTransferContext(
      coinRules.contractId.toInterface(v1.coin.CoinRules.INTERFACE),
      openMiningRound.contractId.toInterface(v1.round.OpenMiningRound.INTERFACE),
    )
  }

  @Help.Summary(
    """Returns the Daml transaction tree for a Coin transaction as visible to the SVC. """
  )
  def getCoinTransactionTree(transactionId: String): TransactionTree =
    consoleEnvironment.run {
      adminCommand(GrpcScanAppClient.GetCoinTransactionDetails(transactionId))
    }

  @Help.Summary(
    """Same as `getCoinTransactionTree` except that it returns a custom type that contains an ASCII visualization 
      |of the Daml transaction tree. """.stripMargin
  )
  def getCoinTransactionTreePretty(transactionId: String): CoinTransactionTreeView = {
    val tree = getCoinTransactionTree(transactionId)
    CoinTransactionTreeView.fromTree(tree)
  }

  @Help.Summary(
    "Lists all closed rounds with their collected statistics"
  )
  def getClosedRounds()
      : Seq[Contract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]] =
    consoleEnvironment.run {
      adminCommand(GrpcScanAppClient.GetClosedRounds())
    }
}

final class ScanAppBackendReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends ScanAppReference(coinConsoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Scan Backend"

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

  override protected val instanceType = "Scan Client"
}
