package com.daml.network.wallet.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.examples.v0
import com.daml.network.examples.v0.WalletServiceGrpc
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.ledger.api.client.LedgerConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.`Package IDs`
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcWalletService(
    connection: CoinLedgerConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {
  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        partyId <- connection.bootstrapUser("testuser")
        _ = logger.info(s"received partyid for user testuser: $partyId")
        activeContracts <- connection.activeContracts(construct_list_filter(partyId))
        // TODO(i207): persist response to store
      } yield v0.ListResponse(activeContracts.toString())
    }

  private def construct_list_filter(partyId: PartyId): TransactionFilter = {
    transaction_filter.TransactionFilter(
      Map(
        partyId.toPrim.toString -> Filters(
          Some(
            InclusiveFilters(templateIds = Seq(CoinUtil.coinTemplateId))
          )
        )
      )
    )
  }
}
