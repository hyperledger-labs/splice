package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.history.CoinTransaction
import com.daml.network.scan.admin.api.client.commands.GrpcScanAppClient
import com.daml.network.scan.config.LocalScanAppConfig
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

/** Single local scan app reference. Defines the console commands that can be run against a local scan
  * app reference.
  */
class LocalScanAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Scan"

  protected val nodes = consoleEnvironment.environment.scans
  @Help.Summary("Return scan app config")
  override def config: LocalScanAppConfig =
    consoleEnvironment.environment.config.scansByString(name)

  /** Remote participant this scan app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )

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

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)

}
