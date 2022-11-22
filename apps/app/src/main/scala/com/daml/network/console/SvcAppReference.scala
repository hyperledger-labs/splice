package com.daml.network.console

import com.daml.network.codegen.java.cc.{round => roundCodegen}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.svc.admin.api.client.commands.GrpcSvcAppClient
import com.daml.network.svc.config.{LocalSvcAppConfig, RemoteSvcAppConfig}
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode

abstract class SvcAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  def getDebugInfo(): GrpcSvcAppClient.DebugInfo = {
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.GetDebugInfo())
    }
  }

  @Help.Summary("Open a new mining round for all validators")
  def openRound(
      round: Long,
      coinPrice: BigDecimal,
  ): roundCodegen.OpenMiningRound.ContractId =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.OpenRound(round, coinPrice))
    }

  @Help.Summary("Start summarizing the mining round for all validators")
  def startSummarizingRound(
      round: Long
  ): roundCodegen.SummarizingMiningRound.ContractId =
    consoleEnvironment.run {
      adminCommand(GrpcSvcAppClient.StartSummarizingRound(round))
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
  ): roundCodegen.ClosedMiningRound.ContractId =
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
    with LocalCoinAppReference
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
