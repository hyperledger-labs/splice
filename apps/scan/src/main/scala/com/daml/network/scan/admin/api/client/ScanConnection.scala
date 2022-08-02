package com.daml.network.scan.admin.api.client

import cats.data.EitherT
import com.daml.network.scan.v0.ScanServiceGrpc
import com.daml.network.scan.v0.ScanServiceGrpc.ScanServiceStub
import com.daml.network.scan.admin.api.client.commands.ScanCommands
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.lifecycle.Lifecycle.CloseableChannel
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.{CantonGrpcUtil, ClientChannelBuilder}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import com.digitalasset.canton.util.EitherTUtil
import io.grpc.{ManagedChannel, Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicReference

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the SVC party id.

  */
final class ScanConnection private (
  private val config: ClientConfig,
  protected val timeouts: ProcessingTimeout,
  protected val loggerFactory: NamedLoggerFactory)(implicit ec: ExecutionContextExecutor) extends FlagCloseableAsync with NamedLogging {
  private val channel = new CloseableChannel(ClientChannelBuilder.createChannel(config), logger, "scan connection")
  private val stub = ScanServiceGrpc.stub(channel.channel).withInterceptors(TraceContextGrpc.clientInterceptor)
  // cached SVC reference.
  private val svcRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable("channel", channel.close()))

  /** Query for the SVC party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getSvcPartyId()(implicit traceContext: TraceContext): Future[PartyId] = {
    val prev = svcRef.get()
    prev match {
      case Some(partyId) => Future.successful(partyId)
      case None =>
        for {
          partyId <- runCmd(ScanCommands.GetSvcPartyId())
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          svcRef.set(Some(partyId))
          partyId
        }
    }
  }

 // This adapted from GrpcCtlRunner but keeps the actual grpc exception
  // instead of turning everything into a String.
  private def runCmd[Req, Res, Result](
      cmd: ScanCommands.BaseCommand[Req, Res, Result]
  )(implicit traceContext: TraceContext): Future[Result] =
    EitherTUtil.toFuture {
      for {
        req <- EitherT
          .fromEither[Future](cmd.createRequest())
          .leftMap(err => new StatusRuntimeException(Status.INTERNAL.withDescription(err)))
        response <- CantonGrpcUtil
          .sendGrpcRequest(stub, "scan")(
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

object ScanConnection {
  def fromClientAdminApi(config: ClientConfig, timeout: ProcessingTimeout, loggerFactory: NamedLoggerFactory)(implicit ec: ExecutionContextExecutor): ScanConnection = {
    new ScanConnection(config, timeout, loggerFactory)
  }
}
