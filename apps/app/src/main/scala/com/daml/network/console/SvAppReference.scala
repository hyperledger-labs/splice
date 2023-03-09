package com.daml.network.console

import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.config.CoinHttpClientConfig
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.sv.config.{LocalSvAppConfig, RemoteSvAppConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration.FiniteDuration

abstract class SvAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends HttpCoinAppReference {

  override protected val instanceType = "SV Client"

  def onboardValidator(validator: PartyId, secret: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.OnboardValidator(validator, secret))
    }

  def onboardSv(token: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.OnboardSv(token))
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
}

class SvAppClientReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
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
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SvAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "SV"

  override def httpClientConfig = CoinHttpClientConfig.fromClientConfig(
    // For local references, we assume that they are reachable on localhost.
    // TODO (#2019) Reconsider if we want these for local refs at all and if so
    // if we should specify a url here. Also remove the "+ 1000" once we
    // disabled Canton's `CommunityAdminServer`.
    s"http://127.0.0.1:${config.clientAdminApi.port.unwrap + 1000}",
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
