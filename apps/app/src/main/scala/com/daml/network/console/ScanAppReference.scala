package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.history.CoinTransaction
import com.daml.network.scan.admin.api.client.commands.GrpcScanAppClient
import com.daml.network.scan.config.{LocalScanAppConfig, RemoteScanAppConfig}
import com.digitalasset.canton.console.{
  BaseInspection,
  GrpcRemoteInstanceReference,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

/** Single local scan app reference. Defines the console commands that can be run against a local scan
  * app reference.
  */
abstract class ScanAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name) {

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
    "Returns reference data (e.g., mining rounds or coin rules) of the Canton network."
  )
  def getReferenceData(): GrpcScanAppClient.ReferenceData =
    consoleEnvironment.run {
      adminCommand(GrpcScanAppClient.GetReferenceData())
    }
}

final class LocalScanAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends ScanAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Local Scan"

  protected val nodes = consoleEnvironment.environment.scans

  @Help.Summary("Return local scan app config")
  override def config: LocalScanAppConfig =
    consoleEnvironment.environment.config.scansByString(name)
}

/** Remote reference to a scan app in the style of CoinRemoteParticipantReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class RemoteScanAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: RemoteScanAppConfig,
) extends ScanAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Remote Scan"
}
