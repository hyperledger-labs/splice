package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.svc.admin.api.client.commands.SvcAppCommands
import com.daml.network.svc.config.LocalSvcAppConfig
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode

/** Single local SVC app reference. Defines the console commands that can be run against a local SVC
  * app reference.
  */
class LocalSvcAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  protected val nodes = consoleEnvironment.environment.svcs
  @Help.Summary("Return svc app config")
  def config: LocalSvcAppConfig =
    consoleEnvironment.environment.config.svcsByString(name)

  def initialize(): Unit = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.Initialize())
    }
  }

  def openNextRound(): Unit = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.OpenNextRound())
    }
  }

  //
  @deprecated(
    "This is now automated in SvcAutomationService. We only still have it in case it may be useful.",
    since = "since automation was introduced",
  )
  def acceptValidators(): Unit = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.AcceptValidators())
    }
  }

  def getDebugInfo(): SvcAppCommands.DebugInfo = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.GetDebugInfo())
    }
  }

  def getValidatorConfig: SvcAppCommands.ValidatorConfigInfo = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.GetValidatorConfig())
    }
  }

  /** Remote participant this SVC app is configured to interact with. */
  val remoteParticipant =
    new SvcAppRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
