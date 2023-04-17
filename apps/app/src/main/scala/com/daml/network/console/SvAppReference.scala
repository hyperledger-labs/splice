package com.daml.network.console

import akka.util.ByteString
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.sv.config.{LocalSvAppConfig, RemoteSvAppConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

import scala.concurrent.duration.FiniteDuration

abstract class SvAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override protected val instanceType = "SV Client"

  def onboardValidator(validator: PartyId, secret: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.OnboardValidator(validator, secret))
    }

  def approveSvIdentity(name: String, key: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.ApproveSvIdentity(name, key))
    }

  def onboardSv(token: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.OnboardSv(token))
    }

  def getSvOnboardingStatus(candidate: PartyId): HttpSvAppClient.SvOnboardingStatus =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.getSvOnboardingStatus(candidate.toProtoPrimitive))
    }

  def getSvOnboardingStatus(candidate: String): HttpSvAppClient.SvOnboardingStatus =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.getSvOnboardingStatus(candidate))
    }

  @Help.Summary("Prepare a validator onboarding and return an onboarding secret (via client API)")
  def devNetOnboardValidatorPrepare(): String =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.DevNetOnboardValidatorPrepare())
    }

  def getDebugInfo(): HttpSvAppClient.DebugInfo =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.GetDebugInfo)
    }

  def onboardSvPartyMigrationAuthorize(participantId: ParticipantId): ByteString =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.OnboardSvPartyMigrationAuthorize(participantId))
    }
}

class SvAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    override val config: RemoteSvAppConfig,
) extends SvAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi
}

/** Single sv app backend reference. Defines the console commands that can be run against a backend SV
  * app.
  */
class SvAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
) extends SvAppReference(consoleEnvironment, name)
    with LocalCNNodeAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "SV"

  override def httpClientConfig = ClientConfig("http://127.0.0.1", config.clientAdminApi.port)

  protected val nodes = consoleEnvironment.environment.svs

  @Help.Summary("Return sv app config")
  def config: LocalSvAppConfig =
    consoleEnvironment.environment.config.svsByString(name)

  def listOngoingValidatorOnboardings()
      : Seq[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]] =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAppClient.ListOngoingValidatorOnboardings
      )
    }

  @Help.Summary("Prepare a validator onboarding and return an onboarding secret (via admin API)")
  def prepareValidatorOnboarding(expiresIn: FiniteDuration): String =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAppClient.PrepareValidatorOnboarding(expiresIn)
      )
    }

  /** Remote participant this sv app is configured to interact with. */
  lazy val remoteParticipant =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this sv app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val remoteParticipantWithAdminToken =
    new CNRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
