package com.daml.network.console

import com.daml.ledger.api.v1.transaction.TransactionTree
import com.daml.network.codegen.CC.{CoinRules => coinRulesCodegen, Round => roundCodegen}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.history.{CoinTransaction, CoinTransactionTreeView}
import com.daml.network.scan.admin.api.client.commands.GrpcScanAppClient
import com.daml.network.scan.config.{LocalScanAppConfig, RemoteScanAppConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

/** Single local scan app reference. Defines the console commands that can be run against a local scan
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
  def getAppTransferContext(): coinRulesCodegen.AppTransferContext = {
    def notFound(description: String) = new IllegalStateException(description)
    val openMiningRound = getTransferContext().latestOpenMiningRound.getOrElse(
      throw notFound("No active OpenMiningRound contract")
    )
    val coinRules =
      getTransferContext().coinRules.getOrElse(throw notFound("No active CoinRules contract"))
    coinRulesCodegen.AppTransferContext(
      coinRules.contractId,
      openMiningRound.contractId,
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
  def getClosedRounds(): Seq[Contract[roundCodegen.ClosedMiningRound]] =
    consoleEnvironment.run {
      adminCommand(GrpcScanAppClient.GetClosedRounds())
    }
}

final class LocalScanAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends ScanAppReference(coinConsoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Local Scan"

  protected val nodes = coinConsoleEnvironment.environment.scans

  @Help.Summary("Return local scan app config")
  override def config: LocalScanAppConfig =
    coinConsoleEnvironment.environment.config.scansByString(name)
}

/** Remote reference to a scan app in the style of CoinRemoteParticipantReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class RemoteScanAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: RemoteScanAppConfig,
) extends ScanAppReference(coinConsoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Remote Scan"
}
