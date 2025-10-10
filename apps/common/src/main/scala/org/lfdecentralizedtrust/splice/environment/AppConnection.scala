// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import org.lfdecentralizedtrust.splice.admin.api.client.TraceContextPropagation.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.admin.api.client.{
  ApiClientRequestLogger,
  GrpcClientMetrics,
  GrpcMetricsClientInterceptor,
  HttpAdminAppClient,
}
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand.{
  CustomClientTimeout,
  DefaultBoundedTimeout,
  DefaultUnboundedTimeout,
  ServerEnforcedTimeout,
  TimeoutType,
}
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.lifecycle.LifeCycle.CloseableChannel
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import com.digitalasset.canton.util.EitherTUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{CallCredentials, Deadline, Status}
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, Uri}
import org.apache.pekko.stream.Materializer

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

abstract class BaseAppConnection(
    override val loggerFactory: NamedLoggerFactory
) extends FlagCloseableAsync
    with NamedLogging {

  def serviceName: String

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq()

  protected def toFuture[T](e: Either[String, T]): Future[T] =
    e.fold(
      // RetryProvider retries UNAVAILABLE by default
      err => Future.failed(Status.UNAVAILABLE.withDescription(err).asRuntimeException()),
      Future.successful,
    )

  protected def runHttpCmd[Res, Result](
      url: Uri,
      command: HttpCommand[Res, Result],
      headers: List[HttpHeader] = List.empty[HttpHeader],
  )(implicit
      templateDecoder: TemplateJsonDecoder,
      httpClient: HttpClient,
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Result] = {
    val client: command.Client = command.createClient(url.toString())
    for {
      response <- EitherTUtil.toFuture(
        command.submitRequest(client, tc.propagate(headers)).leftMap[Throwable] {
          case Left(throwable) => throwable
          case Right(response) => new BaseAppConnection.UnexpectedHttpResponse(response)
        }
      )
      result <- toFuture(command.handleResponse(response))
    } yield result
  }
}

object BaseAppConnection {
  final class UnexpectedHttpResponse(val response: HttpResponse)
      extends Throwable(s"Unexpected Http Response: $response")
}

/** Base class for connecting and calling Canton gRPC APIs.
  */
abstract class AppConnection(
    config: ClientConfig,
    apiLoggingConfig: ApiLoggingConfig,
    override val loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
)(implicit ec: ExecutionContextExecutor)
    extends BaseAppConnection(loggerFactory)
    with FlagCloseableAsync
    with NamedLogging {
  private val channel = new CloseableChannel(
    ClientChannelBuilder.createChannelBuilderToTrustedServer(config).build(),
    logger,
    s"$serviceName connection",
  )

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable(
      "channel", {
        channel.channel.shutdownNow()
        channel.close()
      },
    )
  )

  // This adapted from GrpcCtlRunner but keeps the actual grpc exception
  // instead of turning everything into a String.
  protected def runCmd[Req, Res, Result](
      cmd: GrpcAdminCommand[Req, Res, Result],
      credentials: Option[CallCredentials] = None,
      timeoutOverride: Option[TimeoutType] = None,
  )(implicit traceContext: TraceContext): Future[Result] = {
    val dso =
      cmd
        .createServiceInternal(channel.channel)
        .withInterceptors(
          new ApiClientRequestLogger(
            loggerFactory,
            apiLoggingConfig,
          ),
          new GrpcMetricsClientInterceptor(grpcClientMetrics),
        )

    val dsoAuth = credentials match {
      case Some(creds) => dso.withCallCredentials(creds)
      case None => dso
    }

    val timeout = timeoutOverride.getOrElse(cmd.timeoutType) match {
      case ServerEnforcedTimeout =>
        None
      case CustomClientTimeout(timeout) =>
        Some(timeout)
      case DefaultBoundedTimeout =>
        Some(timeouts.default)
      case DefaultUnboundedTimeout =>
        Some(timeouts.unbounded)
    }
    val withDeadline = timeout.map(_.duration) match {
      case Some(finite: FiniteDuration) =>
        dsoAuth.withDeadline(Deadline.after(finite.length, finite.unit))
      case _ => dsoAuth
    }

    for {
      req <- toFuture(cmd.createRequestInternal())
      response <- TraceContextGrpc.withGrpcContext(traceContext)(
        cmd.submitRequestInternal(withDeadline, req)
      )
      result <- toFuture(cmd.handleResponseInternal(response))
    } yield result
  }

}

/** Base class for connecting and calling the HTTP/Admin API exposed by a Splice App.
  */
abstract class HttpAppConnection(
    config: NetworkAppClientConfig,
    upgradesConfig: UpgradesConfig,
    override val serviceName: String,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    templateDecoder: TemplateJsonDecoder,
    httpClient: HttpClient,
) extends BaseAppConnection(loggerFactory)
    with RetryProvider.Has
    with FlagCloseableAsync
    with NamedLogging {

  val basePath = s"/api/${serviceName}"

  @SuppressWarnings(Array("org.wartremover.warts.Product"))
  implicit private val versionInfoPretty: Pretty[HttpAdminAppClient.VersionInfo] =
    Pretty.adHocPrettyInstance

  def getStatus(): Future[NodeStatus[SpliceStatus]] =
    runHttpCmd(
      config.url,
      HttpAdminAppClient.GetHealthStatus[SpliceStatus](basePath, SpliceStatus.fromHttp),
    )

  // Fails the future if the node is not active for easy use in waitUntil
  def checkActive(): Future[Unit] =
    getStatus().map { status =>
      if (!status.isActive.getOrElse(false)) {
        throw Status.FAILED_PRECONDITION
          .withDescription(show"Node is not active, current status $status")
          .asRuntimeException()
      }
    }

  private def getHttpAppVersionInfo(): Future[HttpAdminAppClient.VersionInfo] = {
    runHttpCmd(
      config.url,
      HttpAdminAppClient.GetVersion(basePath),
      List(),
    )
  }

  def checkVersionCompatibility(retryConnectionOnInitialFailure: Boolean): Future[Unit] = {
    for {
      versionInfo <-
        if (retryConnectionOnInitialFailure) {
          retryProvider.getValueWithRetries(
            RetryFor.WaitingOnInitDependency,
            "app_version",
            s"app version of ${config.url}${basePath}",
            getHttpAppVersionInfo(),
            logger,
          )
        } else {
          getHttpAppVersionInfo()
        }
    } yield {
      logger.debug(s"Found app version: $versionInfo")(TraceContext.empty)
      val myVersion = BuildInfo.compiledVersion
      val compatibleVersion = BuildInfo.compatibleVersion
      if (versionInfo.version != myVersion && versionInfo.version != compatibleVersion) {
        val errorMsg = s"Version mismatch detected, please download the latest bundle. " +
          s"Your executable is on $myVersion, while the application you are connecting to is on ${versionInfo.version}"
        if (upgradesConfig.failOnVersionMismatch)
          sys.error(errorMsg)
        else
          logger.info(errorMsg)(TraceContext.empty)
      } else {
        logger.debug(
          s"Version verification passed for $serviceName, server is on the same version as mine, or a compatible one: ${versionInfo}"
        )(
          TraceContext.empty
        )
      }
    }
  }
}

object HttpAppConnection {
  private[splice] def checkVersionOrClose(
      conn: HttpAppConnection,
      retryConnectionOnInitialFailure: Boolean,
  )(implicit ec: ExecutionContext): Future[conn.type] =
    conn
      .checkVersionCompatibility(retryConnectionOnInitialFailure)
      .transform {
        case Success(_) => Success(conn)
        case Failure(e) =>
          conn.close()
          Failure(e)
      }
}
