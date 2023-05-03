package com.daml.network.environment

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.admin.api.client.{GrpcVersionClient, HttpAdminAppClient}
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.config.CNHttpClientConfig.*
import com.daml.network.environment.{BuildInfo, RetryProvider}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.lifecycle.Lifecycle.CloseableChannel
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import com.digitalasset.canton.util.EitherTUtil
import io.grpc.{CallCredentials, Status, StatusRuntimeException}

import java.time.Instant
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

abstract class BaseAppConnection(
    override val timeouts: ProcessingTimeout,
    override val loggerFactory: NamedLoggerFactory,
) extends FlagCloseableAsync
    with NamedLogging {

  protected def checkVersionCompatibility(): Future[Unit];

  def serviceName: String

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq()

  protected def toFuture[T](e: Either[String, T]): Future[T] =
    e.fold(
      err => Future.failed(new StatusRuntimeException(Status.INTERNAL.withDescription(err))),
      Future.successful,
    )

  protected def runHttpCmd[Res, Result](
      url: String,
      command: HttpCommand[Res, Result],
      headers: List[HttpHeader] = List.empty[HttpHeader],
  )(implicit
      templateDecoder: TemplateJsonDecoder,
      httpClient: HttpRequest => Future[HttpResponse],
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Result] = {
    val client: command.Client = command.createClient(url)
    for {
      response <- EitherTUtil.toFuture(command.submitRequest(client, headers).leftMap[Throwable] {
        case Left(throwable) => throwable
        case Right(response) => new BaseAppConnection.UnexpectedHttpResponse(response)
      })
      result <- toFuture(command.handleResponse(response))
    } yield result
  }
}

object BaseAppConnection {
  final class UnexpectedHttpResponse(response: HttpResponse)
      extends Throwable(s"Unexpected Http Response: $response")
}

/** Base class for connecting and calling the gRPC/Admin API exposed by a CN App.
  */
abstract class AppConnection(
    config: ClientConfig,
    override val timeouts: ProcessingTimeout,
    override val loggerFactory: NamedLoggerFactory,
    enableVersionCompatCheck: Boolean = true,
)(implicit ec: ExecutionContextExecutor)
    extends BaseAppConnection(timeouts, loggerFactory)
    with FlagCloseableAsync
    with NamedLogging {
  private val channel = new CloseableChannel(
    ClientChannelBuilder.createChannelToTrustedServer(config),
    logger,
    s"$serviceName connection",
  )
  if (enableVersionCompatCheck) {
    runVersionCompatCheck()
  }

  private def runVersionCompatCheck() = {
    val _ = for {
      // TODO(#3597) -- add retries here
      _ <- checkVersionCompatibility()
    } yield {}
  }

  override def checkVersionCompatibility() = {
    for {
      versionInfo <- getAppVersionInfo()(TraceContext.empty)
    } yield {
      logger.debug(s"Found app version: ${versionInfo}")(TraceContext.empty)
      val myVersion = BuildInfo.compiledVersion
      if (versionInfo.version != myVersion) {
        val myCommitTs = Instant.ofEpochSecond(BuildInfo.commitUnixTimestamp.toLong)
        logger.error(
          s"Version mismatch detected, please download the latest bundle. Your executable is from $myCommitTs, while the cloud applications you are connecting to are from ${versionInfo.commitTs}"
        )(TraceContext.empty)
      } else {
        logger.debug(
          s"Version verification passed for $serviceName, server is on the same version as mine: ${versionInfo}"
        )(
          TraceContext.empty
        )
      }
    }
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable("channel", channel.close())
  )

  // This adapted from GrpcCtlRunner but keeps the actual grpc exception
  // instead of turning everything into a String.
  protected def runCmd[Req, Res, Result](
      cmd: GrpcAdminCommand[Req, Res, Result],
      credentials: Option[CallCredentials] = None,
  )(implicit traceContext: TraceContext): Future[Result] = {
    val svc =
      cmd
        .createService(channel.channel)
        .withInterceptors(TraceContextGrpc.clientInterceptor)

    val svcAuth = credentials match {
      case Some(creds) => svc.withCallCredentials(creds)
      case None => svc
    }

    for {
      req <- toFuture(cmd.createRequest())
      response <- TraceContextGrpc.withGrpcContext(traceContext)(cmd.submitRequest(svcAuth, req))
      result <- toFuture(cmd.handleResponse(response))
    } yield result
  }

  protected def getAppVersionInfo()(implicit
      traceContext: TraceContext
  ): Future[GrpcVersionClient.VersionInfo] =
    runCmd(GrpcVersionClient.GetVersion())
}

/** Base class for connecting and calling the HTTP/Admin API exposed by a CN App.
  */
abstract class HttpAppConnection(
    config: ClientConfig,
    retryProvider: RetryProvider,
    override val timeouts: ProcessingTimeout,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    templateDecoder: TemplateJsonDecoder,
    httpClient: HttpRequest => Future[HttpResponse],
) extends BaseAppConnection(timeouts, loggerFactory)
    with FlagCloseableAsync
    with NamedLogging {

  private def getHttpAppVersionInfo(url: String): Future[HttpAdminAppClient.VersionInfo] = {
    retryProvider.retryForAutomation(
      "get version",
      runHttpCmd(url, HttpAdminAppClient.GetVersion(), List()),
      logger,
    )
  }

  override def checkVersionCompatibility(): Future[Unit] = {
    for {
      versionInfo <- getHttpAppVersionInfo(config.url)
    } yield {
      logger.debug(s"Found app version: ${versionInfo}")(TraceContext.empty)
      val myVersion = BuildInfo.compiledVersion
      if (versionInfo.version != myVersion) {
        val myCommitTs = Instant.ofEpochSecond(BuildInfo.commitUnixTimestamp.toLong)
        logger.error(
          s"Version mismatch detected, please download the latest bundle. Your executable is from $myCommitTs, while the cloud applications you are connecting to are from ${versionInfo.commitTs}"
        )(TraceContext.empty)
      } else {
        logger.debug(
          s"Version verification passed for $serviceName, server is on the same version as mine: ${versionInfo}"
        )(
          TraceContext.empty
        )
      }
    }
  }
}

object HttpAppConnection {
  private[network] def checkVersionOrClose(
      conn: HttpAppConnection
  )(implicit ec: ExecutionContext): Future[conn.type] =
    conn
      .checkVersionCompatibility()
      .transform {
        case Success(_) => Success(conn)
        case Failure(e) =>
          conn.close()
          Failure(e)
      }
}
