package com.daml.network.console

import com.daml.ledger.client.binding.Primitive.ContractId
import com.daml.network.codegen.CC.{Round => roundCodegen}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.svc.admin.api.client.commands.GrpcSvcAppClient
import com.daml.network.svc.config.{LocalSvcAppConfig, RemoteSvcAppConfig}
import com.digitalasset.canton.console.{
  BaseInspection,
  GrpcRemoteInstanceReference,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

abstract class SvcAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name) {

  @deprecated(
    "This is now automated in SvcAutomationService. We only still have it in case it may be useful.",
    since = "since automation was introduced",
  )
  def acceptValidators(): Unit = {
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.AcceptValidators())
    }
  }

  def getDebugInfo(): GrpcSvcAppClient.DebugInfo = {
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.GetDebugInfo())
    }
  }

  def getValidatorConfig: GrpcSvcAppClient.ValidatorConfigInfo = {
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.GetValidatorConfig())
    }
  }

  @Help.Summary("Open a new mining round for all validators")
  def openRound(
      coinPrice: BigDecimal
  ): Map[PartyId, ContractId[roundCodegen.OpenMiningRound]] =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.OpenRound(coinPrice))
    }

  @Help.Summary("Start closing the mining round for all validators")
  def startClosingRound(
      round: Long
  ): Map[PartyId, ContractId[roundCodegen.ClosingMiningRound]] =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.StartClosingRound(round))
    }

  @Help.Summary("Open the given mining round for issuance for all validators")
  def startIssuingRound(
      round: Long
  ): GrpcSvcAppClient.StartIssuingRoundResponse =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.StartIssuingRound(round))
    }

  @Help.Summary("Close the given mining round for all validators")
  def closeRound(
      round: Long
  ): Map[PartyId, ContractId[roundCodegen.ClosedMiningRound]] =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.CloseRound(round))
    }

  @Help.Summary("Archive the given mining round for all validators")
  def archiveRound(
      round: Long
  ): Unit =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.ArchiveRound(round))
    }

}

class RemoteSvcAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: RemoteSvcAppConfig,
) extends SvcAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Remote SVC"
}

/** Single local SVC app reference. Defines the console commands that can be run against a local SVC
  * app reference.
  */
class LocalSvcAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SvcAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "SVC"

  protected val nodes = consoleEnvironment.environment.svcs

  @Help.Summary("Return svc app config")
  def config: LocalSvcAppConfig =
    consoleEnvironment.environment.config.svcsByString(name)

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
