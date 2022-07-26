package com.daml.network.directory.provider.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.directory.provider.DirectoryInstallRequest
import com.daml.network.directory_provider.v0
import com.daml.network.directory_provider.v0.DirectoryProviderServiceGrpc
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.Coin.Coin
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import com.digitalasset.network.CN.{Directory => codegen}

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

  private def getParty() =
    for {
      partyO <- connection.getUser(damlUser)
      party = partyO.getOrElse(
        sys.error(s"Unable to find party for user $damlUser")
      )
    } yield party

  @nowarn("cat=unused")
  override def listInstallRequests(request: Empty): Future[v0.ListInstallRequestsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        activeContracts <- connection.activeContracts(
          txFilter(partyId, ApiTypes.TemplateId.unwrap(codegen.DirectoryInstallRequest.id))
        )
        installRequestsLAPI = activeContracts._1.flatMap(event =>
          DecodeUtil.decodeCreated(codegen.DirectoryInstallRequest)(event)
        )
      } yield {
        val filteredRequests = installRequestsLAPI.filter(contract =>
          PartyId.tryFromProtoPrimitive(ApiTypes.Party.unwrap(contract.value.provider)) == partyId
        )
        v0.ListInstallRequestsResponse(
          filteredRequests.map(r => DirectoryInstallRequest.fromContract(r).toProtoV0)
        )
      }
    }

  private def txFilter(partyId: PartyId, tplId: Identifier): TransactionFilter = {
    transaction_filter.TransactionFilter(
      Map(
        partyId.toPrim.toString -> Filters(
          Some(
            InclusiveFilters(templateIds = Seq(tplId))
          )
        )
      )
    )
  }

}
