package com.daml.network.admin.api.client

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.Lifecycle.CloseableChannel
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContextExecutor, Future}

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

  def serviceName: String

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable("channel", channel.close())
  )

  private def toFuture[T](e: Either[String, T]): Future[T] =
    e.fold(
      err => Future.failed(new StatusRuntimeException(Status.INTERNAL.withDescription(err))),
      Future.successful(_),
    )

  // This adapted from GrpcCtlRunner but keeps the actual grpc exception
  // instead of turning everything into a String.
  protected def runCmd[Req, Res, Result](
      cmd: GrpcAdminCommand[Req, Res, Result]
  )(implicit traceContext: TraceContext): Future[Result] = {
    val svc =
      cmd.createService(channel.channel).withInterceptors(TraceContextGrpc.clientInterceptor)
    for {
      req <- toFuture(cmd.createRequest())
      response <- traceContext.intoGrpcContext(cmd.submitRequest(svc, req))
      result <- toFuture(cmd.handleResponse(response))
    } yield result
  }
}
