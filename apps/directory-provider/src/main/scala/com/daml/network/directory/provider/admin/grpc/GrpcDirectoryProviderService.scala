package com.daml.network.directory.provider.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.Primitive
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
import com.digitalasset.network.DA.Time.Types.RelTime

import scala.annotation.nowarn
import scala.language.implicitConversions
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

  // TODO (MK) Make these parameters configurable
  private val entryFee: Primitive.Numeric = 1.0
  private val collectionDuration = RelTime(
    10_000_000
  )
  private val approveDuration = RelTime(
    60_000_000
  )

  private def getParty() =
    for {
      partyO <- connection.getUser(damlUser)
      party = partyO.getOrElse(
        sys.error(s"Unable to find party for user $damlUser")
      )
    } yield party

  @nowarn("cat=unused")
  override def listInstallRequests(request: Empty): Future[v0.ListInstallRequestsResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        activeContracts <- connection.activeContracts(
          CoinLedgerConnection.transactionFilter(partyId, codegen.DirectoryInstallRequest.id)
        )
        installRequestsLAPI = activeContracts._1.flatMap(event =>
          DecodeUtil.decodeCreated(codegen.DirectoryInstallRequest)(event)
        )
      } yield {
        val filteredRequests = installRequestsLAPI.filter(contract =>
          PartyId.tryFromPrim(contract.value.provider) == partyId
        )
        v0.ListInstallRequestsResponse(
          filteredRequests.map(r => DirectoryInstallRequest.fromContract(r).toProtoV0)
        )
      }
    }

  override def acceptInstallRequest(
      request: v0.AcceptInstallRequestRequest
  ): Future[v0.AcceptInstallRequestResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        arg = codegen.DirectoryInstallRequest_Accept(
          svc = ApiTypes.Party(request.svc),
          entryFee = entryFee,
          collectionDuration = collectionDuration,
          approveDuration = approveDuration,
        )
        acceptCmd = Primitive.ContractId
          .apply[codegen.DirectoryInstallRequest](request.contractId)
          .exerciseDirectoryInstallRequest_Accept(partyId.toPrim, arg)
          .command
        tx <- connection.submitCommand(Seq(partyId), Seq(), Seq(acceptCmd))
        installs = DecodeUtil.decodeAllCreated(codegen.DirectoryInstall)(tx.getTransaction)
        _ = require(
          installs.length == 1,
          s"Expected accept to create only one install contract but found ${installs.length} installs $installs",
        )
      } yield {
        v0.AcceptInstallRequestResponse(installs(0).contractId.toString)
      }
    }

}
