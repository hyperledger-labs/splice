package com.daml.network.admin.api.client

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.admin.api.client.version.GrpcVersionClient
import com.daml.network.environment.BuildInfo
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.Lifecycle.CloseableChannel
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import com.digitalasset.canton.util.EitherTUtil
import io.grpc.{CallCredentials, Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

/** Base class for connecting and calling the gRPC/Admin API exposed by a CN App.
  */
abstract class AppConnection(
    config: ClientConfig,
    override val timeouts: ProcessingTimeout,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends FlagCloseableAsync
    with NamedLogging {
  private val channel = new CloseableChannel(
    ClientChannelBuilder.createChannelToTrustedServer(config),
    logger,
    s"$serviceName connection",
  )
  checkVersionCompatibility()

  private def checkVersionCompatibility() = {
    val _ = for {
      version <- getAppVersion()(TraceContext.empty)
    } yield {
      logger.debug(s"Found app version: ${version}")(TraceContext.empty)
      val myVersion = BuildInfo.compiledVersion
      if (version != myVersion) {
        logger.error(
          s"Versions do not match for $serviceName: server is on $version, but mine is: $myVersion"
        )(TraceContext.empty)
      } else {
        logger.debug(
          s"Version verification passed for $serviceName, server is on the same version as mine: ${version}"
        )(
          TraceContext.empty
        )
      }
    }
  }

  def serviceName: String

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable("channel", channel.close())
  )

  private def toFuture[T](e: Either[String, T]): Future[T] =
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
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Result] = {
    val client: command.Client = command.createClient(url)
    for {
      response <- EitherTUtil.toFuture(command.submitRequest(client, headers).leftMap[Throwable] {
        case Left(throwable) => throwable
        case Right(response) => new AppConnection.UnexpectedHttpResponse(response)
      })
      result <- toFuture(command.handleResponse(response))
    } yield result
  }

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

  protected def getAppVersion()(implicit traceContext: TraceContext): Future[String] =
    runCmd(GrpcVersionClient.GetVersion())
}

object AppConnection {
  final class UnexpectedHttpResponse(response: HttpResponse)
      extends Throwable(s"Unexpected Http Response: $response")
}
