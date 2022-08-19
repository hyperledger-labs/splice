package com.daml.network.splitwise.admin.grpc
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

@nowarn("cat=unused")
class GrpcSplitwiseService(
    ledgerClient: CoinLedgerClient,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SplitwiseServiceGrpc.SplitwiseService
    with Spanning
    with NamedLogging {

  private val grpcTimeout = Duration.Inf
}
