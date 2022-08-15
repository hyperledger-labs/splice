package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.scan.admin.api.client.commands.ScanCommands
import com.daml.network.scan.config.LocalScanAppConfig
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.commands._
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  ConsoleEnvironment,
  FeatureFlag,
  Help,
  InstanceReference,
  LedgerApiCommandRunner,
  LocalInstanceReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.health.admin.data.ParticipantStatus
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

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
      adminCommand(ScanCommands.GetSvcPartyId())
    }

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)

}
