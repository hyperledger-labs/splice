package com.daml.network.admin.api.client

import cats.data.EitherT
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.lifecycle.Lifecycle.CloseableChannel
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.{CantonGrpcUtil, ClientChannelBuilder}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import com.digitalasset.canton.util.EitherTUtil
import io.grpc.{ManagedChannel, Status, StatusRuntimeException}
import io.grpc.stub.AbstractStub

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicReference

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
    ClientChannelBuilder.createChannel(config),
    logger,
    s"$serviceName connection",
  )

  def serviceName: String

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable("channel", channel.close())
  )

  // This adapted from GrpcCtlRunner but keeps the actual grpc exception
  // instead of turning everything into a String.
  protected def runCmd[Req, Res, Result](
      cmd: GrpcAdminCommand[Req, Res, Result]
  )(implicit traceContext: TraceContext): Future[Result] =
    EitherTUtil.toFuture {
      val svc =
        cmd.createService(channel.channel).withInterceptors(TraceContextGrpc.clientInterceptor)
      for {
        req <- EitherT
          .fromEither[Future](cmd.createRequest())
          .leftMap(err => new StatusRuntimeException(Status.INTERNAL.withDescription(err)))
        response <- CantonGrpcUtil
          .sendGrpcRequest(svc, serviceName)(
            send = cmd.submitRequest(_, req),
            requestDescription = cmd.toString,
            timeout = timeouts.network.duration,
            logger = logger,
          )
          .leftMap(err => new StatusRuntimeException(err.status))
        result <- EitherT
          .fromEither[Future](cmd.handleResponse(response))
          .leftMap(err => new StatusRuntimeException(Status.INTERNAL.withDescription(err)))
      } yield result
    }
}
