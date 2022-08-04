package com.daml.network.console

import com.daml.ledger.client.binding.Primitive.ContractId
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.svc.admin.api.client.commands.SvcAppCommands
import com.daml.network.svc.config.LocalSvcAppConfig
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.{Round => roundCodegen}

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

  @Help.Summary("Initialize the SVC app. ")
  def initialize(): PartyId = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.Initialize())
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

  @Help.Summary("Open a new mining round for all validators")
  def openRound(
      coinPrice: BigDecimal
  ): Map[PartyId, ContractId[roundCodegen.OpenMiningRound]] =
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.OpenRound(coinPrice))
    }

  @Help.Summary("Start closing the mining round for all validators")
  def startClosingRound(
      round: Long
  ): Map[PartyId, ContractId[roundCodegen.ClosingMiningRound]] =
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.StartClosingRound(round))
    }

  @Help.Summary("Open the given mining round for issuance for all validators")
  def startIssuingRound(
      round: Long
  ): SvcAppCommands.StartIssuingRoundResponse =
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.StartIssuingRound(round))
    }

  @Help.Summary("Close the given mining round for all validators")
  def closeRound(
      round: Long
  ): Map[PartyId, ContractId[roundCodegen.ClosedMiningRound]] =
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.CloseRound(round))
    }

  @Help.Summary("Archive the given mining round for all validators")
  def archiveRound(
      round: Long
  ): Unit =
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.ArchiveRound(round))
    }

  /** Remote participant this SVC app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
