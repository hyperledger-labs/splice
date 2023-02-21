package com.daml.network.console

import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.svc.admin.api.client.commands.GrpcSvcAppClient
import com.daml.network.svc.config.{SvcAppBackendConfig, SvcAppClientConfig}
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

import java.time.Instant

abstract class SvcAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  def getDebugInfo(): GrpcSvcAppClient.DebugInfo = {
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.GetDebugInfo())
    }
  }
}

class SvcAppClientReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: SvcAppClientConfig,
) extends SvcAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "SVC Client"

  @Help.Summary("Grant a featured app right to an app provider")
  def grantFeaturedAppRight(provider: PartyId): FeaturedAppRight.ContractId = {
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.GrantFeaturedAppRight(provider))
    }
  }

  @Help.Summary("Withdraw a featured app right from an app provider")
  def withdrawFeaturedAppRight(provider: PartyId): Unit = {
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.WithdrawFeaturedAppRight(provider))
    }
  }

  @Help.Summary("Set config schedule for the CoinRules.")
  def setConfigSchedule(configSchedule: Schedule[Instant, CoinConfig[USD]]): Unit =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.SetConfigSchedule(configSchedule))
    }
}

/** Single SVC app backend reference. Defines the console commands that can be run against a backend SVC
  * app.
  */
class SvcAppBackendReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SvcAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "SVC"

  protected val nodes = consoleEnvironment.environment.svcs

  @Help.Summary("Return svc app config")
  def config: SvcAppBackendConfig =
    consoleEnvironment.environment.config.svcsByString(name)

  /** Remote participant this SVC app is configured to interact with. */
  lazy val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this SVC app is configured to interact with. Uses admin tokens to bypass auth. */
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
