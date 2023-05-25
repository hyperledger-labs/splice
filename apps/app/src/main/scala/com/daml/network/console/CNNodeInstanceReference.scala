package com.daml.network.console

import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.daml.network.admin.api.client.HttpAdminAppClient
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.config.{CNNodeBackendConfig, NetworkAppClientConfig}
import com.daml.network.environment.{CNNodeConsoleEnvironment, CNNodeStatus}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.console.commands.{
  HealthAdministration,
  KeyAdministrationGroup,
  PartiesAdministrationGroup,
  TopologyAdministrationGroup,
}
import com.digitalasset.canton.console.{
  ConsoleCommandResult,
  ConsoleEnvironment,
  ConsoleMacros,
  Help,
  InstanceReference,
  LocalInstanceReference,
  RemoteParticipantReference,
  RemoteParticipantReferenceX,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.{NodeIdentity, ParticipantId}

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Copy of Canton ParticipantReference */
trait CNNodeAppReference extends InstanceReference {

  override val name: String

  override implicit val consoleEnvironment: ConsoleEnvironment = cnNodeConsoleEnvironment
  implicit val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment

  override protected val loggerFactory: NamedLoggerFactory =
    consoleEnvironment.environment.loggerFactory.append("Wallet", name)

  override type Status = SimpleStatus

  // TODO(#736): remove/cleanup all the uninteresting console commands copied from Canton.
  @Help.Summary("Health and diagnostic related commands")
  @Help.Group("Health")
  override def health =
    new HealthAdministration[SimpleStatus](
      this,
      consoleEnvironment,
      SimpleStatus.fromProtoV0,
    )

  @Help.Summary(
    "Yields the globally unique id of this participant. " +
      "Throws an exception, if the id has not yet been allocated (e.g., the participant has not yet been started)."
  )
  override def id: NodeIdentity = topology.idHelper(ParticipantId(_))

  private lazy val topology_ =
    new TopologyAdministrationGroup(
      this,
      health.status.successOption.map(_.topologyQueue),
      consoleEnvironment,
      loggerFactory,
    )

  @Help.Summary("Topology management related commands")
  @Help.Group("Topology")
  @Help.Description(
    "This group contains access to the full set of topology management commands."
  )
  def topology: TopologyAdministrationGroup = topology_

  @Help.Summary("Inspect and manage parties")
  @Help.Group("Parties")
  override def parties: PartiesAdministrationGroup = partiesGroup

  @Help.Summary("Wait until initialization has completed")
  def waitForInitialization(
      timeout: NonNegativeDuration = cnNodeConsoleEnvironment.commandTimeouts.bounded,
      maxBackoff: NonNegativeDuration = NonNegativeDuration.tryFromDuration(10.seconds),
  ): Unit =
    try {
      ConsoleMacros.utils.retry_until_true(timeout, maxBackoff)(
        health.status.successOption.exists(_.active)
      )
    } catch {
      case NonFatal(e) =>
        noTracingLogger.error(s"Timeout while waiting for initialization of ${name}", e)
        throw e
    }

  // TODO(#736): slightly adapted compared to Canton.
  // above command needs to be def such that `Help` works.
  lazy private val partiesGroup =
    new PartiesAdministrationGroup(this, consoleEnvironment)

  def runningNode: Option[CantonNodeBootstrap[ParticipantNode]] =
    consoleEnvironment.environment.participants.getRunning(name)
}

trait HttpCNNodeAppReference extends CNNodeAppReference with HttpCommandRunner {

  // TODO (#4606): Refactor so that these two methods don't need to be implemented
  override def keys: KeyAdministrationGroup = noGrpcError()

  override def adminCommand[Result](
      grpcCommand: GrpcAdminCommand[_, _, Result]
  ): ConsoleCommandResult[Result] = noGrpcError()

  private def noGrpcError() = throw new NotImplementedError(
    "This app is not supposed to be used via gRPC."
  )

  def token: Option[String] = None

  def headers =
    token.map(t => List(Authorization(OAuth2BearerToken(t)))).getOrElse(List.empty[HttpHeader])

  def httpClientConfig: NetworkAppClientConfig

  override protected[console] def httpCommand[Result](
      httpCommand: HttpCommand[_, Result]
  ): ConsoleCommandResult[Result] =
    cnNodeConsoleEnvironment.httpCommandRunner.runCommand(
      name,
      httpCommand,
      headers,
      httpClientConfig,
    )

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

  private val defaultHealthStatusTimeout: NonNegativeDuration =
    NonNegativeDuration.tryFromDuration(2.minute)
  private val defaultHealthStatusMaxBackoff: NonNegativeDuration =
    NonNegativeDuration.tryFromDuration(5.seconds)

  override def waitForInitialization(
      timeout: NonNegativeDuration = defaultHealthStatusTimeout,
      maxBackoff: NonNegativeDuration = defaultHealthStatusMaxBackoff,
  ): Unit =
    try {
      ConsoleMacros.utils.retry_until_true(
        timeout,
        maxBackoff,
      )(
        httpHealth.successOption.exists(_.active)
      )
    } catch {
      case NonFatal(e) =>
        noTracingLogger.error(s"Timeout while waiting for initialization of ${name}", e)
        throw e
    }
}

trait CNNodeAppBackendReference extends CNNodeAppReference with LocalInstanceReference {
  override def config: CNNodeBackendConfig

  @Help.Summary("Start node and wait for initialization to complete")
  def startSync(): Unit = {
    this.start()
    waitForInitialization()
  }
}

/** Subclass of participantClient that takes the config as an argument
  * instead of relying on remoteParticipantsByName.
  */
class CNParticipantClientReference(
    consoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
    override val config: RemoteParticipantConfig,
) extends RemoteParticipantReference(consoleEnvironment, name) {
  val config_ = config
  // TODO (M3-47) Remove workaround once we have fully switched to CantonX nodes
  val participantX = new RemoteParticipantReferenceX(consoleEnvironment, name) {
    override val config = config_
  }
}
