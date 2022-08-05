package com.daml.network.directory.user.admin.grpc

import cats.data.EitherT
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.{Contract => CodegenContract, Primitive, TemplateCompanion}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.directory.provider.admin.api.client.DirectoryProviderConnection
import com.daml.network.directory_user.v0
import com.daml.network.directory_user.v0.DirectoryUserServiceGrpc
import com.daml.network.util.{Contract, CoinUtil}
import com.digitalasset.canton.console.CommandErrors.GenericCommandError
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.CantonGrpcUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.EitherTUtil
import com.digitalasset.network.CC.Coin.Coin
import com.google.protobuf.empty.Empty
import io.grpc.{ManagedChannel, Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}
import com.digitalasset.network.DA
import com.digitalasset.network.DA.Time.Types.RelTime

import scala.annotation.nowarn
import scala.language.implicitConversions
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

@nowarn("cat=unused")
class GrpcDirectoryUserService(
    connection: CoinLedgerConnection,
    providerConnection: DirectoryProviderConnection,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryUserServiceGrpc.DirectoryUserService
    with Spanning
    with NamedLogging {

  private val grpcTimeout = Duration.Inf

  override def requestDirectoryInstall(request: Empty): Future[v0.RequestDirectoryInstallResponse] =
    withSpanFromGrpcContext("GrpcDirectoryUserService") { implicit traceContext => span =>
      for {
        userParty <- getParty()
        providerParty <- providerConnection.getProviderPartyId()
        cmd = codegen
          .DirectoryInstallRequest(user = userParty.toPrim, provider = providerParty.toPrim)
          .create
          .command
        tx <- connection.submitCommand(Seq(userParty), Seq(), Seq(cmd))
        requests = DecodeUtil.decodeAllCreated(codegen.DirectoryInstallRequest)(tx.getTransaction)
        _ = require(
          requests.length == 1,
          s"Expected one DirectoryInstallRequest but got ${requests.length} requests $requests",
        )
      } yield v0.RequestDirectoryInstallResponse(ApiTypes.ContractId.unwrap(requests(0).contractId))
    }

  override def requestDirectoryEntry(
      request: v0.RequestDirectoryEntryRequest
  ): Future[v0.RequestDirectoryEntryResponse] =
    withSpanFromGrpcContext("GrpcDirectoryUserService") { implicit traceContext => span =>
      for {
        userParty <- getParty()
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
          .command
        tx <- connection.submitCommand(Seq(userParty), Seq(), Seq(cmd))
        requests = DecodeUtil.decodeAllCreated(codegen.DirectoryEntryRequest)(tx.getTransaction)
        _ = require(
          requests.length == 1,
          s"Expected one DirectoryEntryRequest but got ${requests.length} requests $requests",
        )
      } yield v0.RequestDirectoryEntryResponse(ApiTypes.ContractId.unwrap(requests(0).contractId))
    }

  private def getParty() =
    for {
      partyO <- connection.getUser(damlUser)
      party = partyO.getOrElse(
        sys.error(s"Unable to find party for user $damlUser")
      )
    } yield party
}
