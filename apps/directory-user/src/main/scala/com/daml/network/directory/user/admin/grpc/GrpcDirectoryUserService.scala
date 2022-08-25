package com.daml.network.directory.user.admin.grpc

import com.daml.network.environment.CoinLedgerClient
import com.daml.network.directory.provider.admin.api.client.DirectoryProviderConnection
import com.daml.network.directory_user.v0
import com.daml.network.directory_user.v0.DirectoryUserServiceGrpc
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.daml.network.codegen.CN.{Directory => codegen}
import com.daml.network.codegen.DA
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

class GrpcDirectoryUserService(
    ledgerClient: CoinLedgerClient,
    providerConnection: DirectoryProviderConnection,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryUserServiceGrpc.DirectoryUserService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcDirectoryUserService")

  private val grpcTimeout = Duration.Inf

  override def requestDirectoryInstall(request: Empty): Future[v0.RequestDirectoryInstallResponse] =
    withSpanFromGrpcContext("GrpcDirectoryUserService") { implicit traceContext => span =>
      for {
        userParty <- connection.getPrimaryParty(damlUser)
        providerParty <- providerConnection.getProviderPartyId()
        cmd = codegen
          .DirectoryInstallRequest(user = userParty.toPrim, provider = providerParty.toPrim)
          .create
        requestCid <- connection.submitWithResult(Seq(userParty), Seq(), cmd)
      } yield v0.RequestDirectoryInstallResponse(Proto.encode(requestCid))
    }

  override def requestDirectoryEntry(
      request: v0.RequestDirectoryEntryRequest
  ): Future[v0.RequestDirectoryEntryResponse] =
    withSpanFromGrpcContext("GrpcDirectoryUserService") { implicit traceContext => span =>
      for {
        userParty <- connection.getPrimaryParty(damlUser)
        providerParty <- providerConnection.getProviderPartyId()
        cmd = codegen.DirectoryInstall
          .key(DA.Types.Tuple2(providerParty.toPrim, userParty.toPrim))
          .exerciseDirectoryInstall_RequestEntry(
            userParty.toPrim,
            codegen.DirectoryEntry(
              provider = providerParty.toPrim,
              user = userParty.toPrim,
              name = request.name,
            ),
          )
        requestCid <- connection.submitWithResult(Seq(userParty), Seq(), cmd)
      } yield v0.RequestDirectoryEntryResponse(Proto.encode(requestCid))
    }
}
