package com.daml.network.directory.user.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.{Contract => CodegenContract, Primitive, TemplateCompanion}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.directory_user.v0
import com.daml.network.directory_user.v0.DirectoryUserServiceGrpc
import com.daml.network.util.{Contract, CoinUtil}
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.Coin.Coin
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}
import com.digitalasset.network.DA
import com.digitalasset.network.DA.Time.Types.RelTime

import scala.annotation.nowarn
import scala.language.implicitConversions
import scala.concurrent.{ExecutionContext, Future}

@nowarn("cat=unused")
class GrpcDirectoryUserService(
    connection: CoinLedgerConnection,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryUserServiceGrpc.DirectoryUserService
    with Spanning
    with NamedLogging {}
