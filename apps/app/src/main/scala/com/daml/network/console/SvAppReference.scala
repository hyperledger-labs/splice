package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.sv.admin.api.client.commands.GrpcSvAppClient
import com.daml.network.sv.config.{LocalSvAppConfig, RemoteSvAppConfig}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.DomainId

abstract class SvAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  def getDebugInfo(): GrpcSvAppClient.DebugInfo = {
    consoleEnvironment.run {
      adminCommand(GrpcSvAppClient.GetDebugInfo())
    }
  }

  @Help.Summary("List the connected domains of the participant the app is running on")
  def listConnectedDomains(): Map[DomainAlias, DomainId] =
    consoleEnvironment.run {
      adminCommand(GrpcSvAppClient.ListConnectedDomains())
    }
}

class SvAppClientReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: RemoteSvAppConfig,
) extends SvAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "sv Client"
}

/** Single sv app backend reference. Defines the console commands that can be run against a backend SV
  * app.
  */
class SvAppBackendReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SvAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "sv"

  protected val nodes = consoleEnvironment.environment.svs

  @Help.Summary("Return sv app config")
  def config: LocalSvAppConfig =
    consoleEnvironment.environment.config.svsByString(name)

  /** Remote participant this sv app is configured to interact with. */
  lazy val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this sv app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val remoteParticipantWithAdminToken =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      name,
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
