package com.daml.network.directory.provider.admin.grpc

import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.examples.v0
import com.daml.network.examples.v0.DirectoryProviderServiceGrpc
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.Coin.Coin
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcDirectoryProviderService(
    connection: CoinLedgerConnection,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryProviderServiceGrpc.DirectoryProviderService
    with Spanning
    with NamedLogging {

  override def hello(request: v0.HelloRequest): Future[v0.HelloResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      Future {
        logger.info(s"received hello request")
        v0.HelloResponse()
      }
    }

}
