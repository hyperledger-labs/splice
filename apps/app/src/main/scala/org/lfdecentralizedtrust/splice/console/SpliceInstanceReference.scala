// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.digitalasset.daml.lf.archive.DarParser
import org.lfdecentralizedtrust.splice.admin.api.client.HttpAdminAppClient
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.config.{SpliceBackendConfig, NetworkAppClientConfig}
import org.lfdecentralizedtrust.splice.environment.{
  NodeBase,
  SpliceConsoleEnvironment,
  SpliceStatus,
}
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.daml.scalautil.Statement.discard
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.console.commands.{
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
  RemoteSequencerReference,
}
import com.digitalasset.canton.domain.sequencing.config.RemoteSequencerConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.NodeIdentity

import java.io.File
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

/** Copy of Canton ParticipantReference */
trait AppReference extends InstanceReference {

  override val name: String

  override implicit val consoleEnvironment: ConsoleEnvironment = spliceConsoleEnvironment
  implicit val spliceConsoleEnvironment: SpliceConsoleEnvironment

  override def executionContext: ExecutionContext =
    consoleEnvironment.environment.executionContext

  override protected val loggerFactory: NamedLoggerFactory =
    consoleEnvironment.environment.loggerFactory.append("app", name)

  override type Status = SpliceStatus

  // TODO(#736): remove/cleanup all the uninteresting console commands copied from Canton.
  @Help.Summary("Health and diagnostic related commands")
  @Help.Group("Health")
  // Doesn't make sense for splice
  override def health = ???

  // clear_cache exists to invalidate topology caches which we don't have in our apps.
  override def clear_cache(): Unit = ()

  override def topology: TopologyAdministrationGroup = new TopologyAdministrationGroup(
    this,
    // Doesn't make sense for Splice
    None,
    consoleEnvironment,
    loggerFactory,
  )

  // Doesn't make sense for Splice
  override def parties: PartiesAdministrationGroup = ???

  // Doesn't make sense for Splice
  override def id: NodeIdentity = ???
  override def maybeId: Option[NodeIdentity] = None

  @Help.Summary("Wait until initialization has completed")
  def waitForInitialization(
      timeout: NonNegativeDuration = spliceConsoleEnvironment.commandTimeouts.bounded,
      maxBackoff: NonNegativeDuration = NonNegativeDuration.tryFromDuration(20.seconds),
  ): Unit

  // Doesn't make sense for Splice
  override def adminToken = ???
}

trait HttpAppReference extends AppReference with HttpCommandRunner {

  def basePath: String

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

  override protected[splice] def httpCommand[Result](
      httpCommand: HttpCommand[_, Result]
  ): ConsoleCommandResult[Result] =
    spliceConsoleEnvironment.httpCommandRunner.runCommand(
      name,
      httpCommand,
      headers,
      httpClientConfig,
    )

  @Help.Summary("Health and diagnostic related commands (HTTP)")
  @Help.Group("HTTP Health")
  def httpHealth: NodeStatus[SpliceStatus] = {
    consoleEnvironment.run {
      // Map failing HTTP requests to a failed NodeStatus if the status endpoint isn't up yet (e.g. slow app initialization)
      ConsoleCommandResult.fromEither(
        Right(
          httpCommand(
            HttpAdminAppClient.GetHealthStatus[SpliceStatus](basePath, SpliceStatus.fromHttp)
          ).toEither.fold(err => NodeStatus.Failure(err), success => success)
        )
      )
    }
  }

  @Help.Summary("Health and diagnostic related commands (HTTP)")
  @Help.Group("HTTP Liveness")
  def httpLive: Boolean = {
    Try(
      consoleEnvironment.run {
        httpCommand(HttpAdminAppClient.IsLive(basePath))
      }
    ).getOrElse(false)
  }

  @Help.Summary("Health and diagnostic related commands (HTTP)")
  @Help.Group("HTTP Readiness")
  def httpReady: Boolean = {
    Try(
      consoleEnvironment.run {
        httpCommand(HttpAdminAppClient.IsReady(basePath))
      }
    ).getOrElse(false)
  }

  @Help.Summary("Get the version of the node")
  def version: HttpAdminAppClient.VersionInfo =
    consoleEnvironment.run {
      httpCommand(HttpAdminAppClient.GetVersion(basePath))
    }

  private val defaultHealthStatusTimeout: NonNegativeDuration =
    NonNegativeDuration.tryFromDuration(5.minute)
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

trait AppBackendReference extends AppReference with LocalInstanceReference {
  override def config: SpliceBackendConfig

  @Help.Summary("Start node and wait for initialization to complete")
  def startSync(): Unit = {
    this.start()
    waitForInitialization()
  }

  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  protected def _appState[S <: AutoCloseable & HasHealth, NB <: NodeBase[S]](implicit
      tag: ClassTag[NB]
  ): S = {
    (for {
      bootstrap <- nodes
        .getRunning(this.name)
        .toRight(s"NodeBootstrap does not exist.")
      node <- bootstrap.getNode
        .toRight(s"NodeBootstrap doesn't have any running node.")
      cnNode <- node match {
        case n: NB => Right(n)
        case other =>
          Left(
            s"Expected running node to be of type ${tag.runtimeClass.getSimpleName}, but found type ${other.getClass.getSimpleName}."
          )
      }
      state <- cnNode.getState.toRight("Node doesn't have any app state.")
    } yield state).fold(
      reason =>
        throw new RuntimeException(
          s"The app state of ${this.name} is currently not accessible. $reason"
        ),
      x => x,
    )
  }
}

/** Subclass of participantClient that takes the config as an argument
  * instead of relying on remoteParticipantsByName.
  */
class ParticipantClientReference(
    consoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
    override val config: RemoteParticipantConfig,
) extends RemoteParticipantReference(consoleEnvironment, name) {

  // TODO(#5141) Consider removing this once Canton no longer explodes
  // when uploading the same DAR twice.
  def upload_dar_unless_exists(
      path: String
  ): Unit = {
    val hash = DarParser.assertReadArchiveFromFile(new File(path)).main.getHash
    val pkgs = this.ledger_api.packages.list()
    if (!pkgs.map(_.packageId).contains(hash)) {
      discard[String](this.dars.upload(path))
    }
  }
}

class SequencerClientReference(
    consoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
    override val config: RemoteSequencerConfig,
) extends RemoteSequencerReference(consoleEnvironment, name) {}
