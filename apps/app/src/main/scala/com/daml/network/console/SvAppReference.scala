package com.daml.network.console

import akka.util.ByteString
import com.daml.network.admin.api.client.HttpAdminAppClient
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.config.CNHttpClientConfig
import com.daml.network.environment.{CNNodeStatus, CNNodeConsoleEnvironment}
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.sv.config.{LocalSvAppConfig, RemoteSvAppConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.console.commands.TopologyAdministrationGroup
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  ConsoleMacros,
  GrpcRemoteInstanceReference,
  Help,
}
import com.digitalasset.canton.health.admin.data.NodeStatus
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

  def onboardSv(token: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.OnboardSv(token))
    }

  def getSvOnboardingStatus(candidate: PartyId): HttpSvAppClient.SvOnboardingStatus =
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

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  @Help.Summary("Health and diagnostic related commands (HTTP)")
  @Help.Group("HTTP Health")
  def httpHealth = {
    consoleEnvironment.run {
      // Map failing HTTP requests to a failed NodeStatus if the status endpoint isn't up yet (e.g. slow app initialization)
      // TODO(#3467) see if we still need this after the initialization order is fixed
      ConsoleCommandResult.fromEither(
        Right(
          httpCommand(
            HttpAdminAppClient.GetHealthStatus[CNNodeStatus](CNNodeStatus.fromJsonV0)
          ).toEither.fold(err => NodeStatus.Failure(err), success => success)
        )
      )
    }
  }

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  // Override topology to avoid using grpc status check
  private lazy val topology_ =
    new TopologyAdministrationGroup(
      this,
      None,
      consoleEnvironment,
      loggerFactory,
    )
  @Help.Summary("Topology management related commands")
  @Help.Group("Topology")
  @Help.Description(
    "This group contains access to the full set of topology management commands."
  )
  override def topology: TopologyAdministrationGroup = topology_

  // TODO(#3490): extract this to HttpCNNodeAppReference for all HTTP-based apps
  override def waitForInitialization(
      timeout: NonNegativeDuration = cnNodeConsoleEnvironment.commandTimeouts.bounded
  ): Unit =
    ConsoleMacros.utils.retry_until_true(timeout)(
      httpHealth.successOption.map(_.active).getOrElse(false)
    )
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

  override def httpClientConfig = CNHttpClientConfig.fromClientConfig(
    // For local references, we assume that they are reachable on localhost.
    // TODO (#2019) Reconsider if we want these for local refs at all and if so
    // if we should specify a url here. Also remove the "+ 1000" once we
    // disabled Canton's `CommunityAdminServer`.
    s"http://127.0.0.1:${config.clientAdminApi.port.unwrap}",
    config.clientAdminApi,
  )

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
