package com.daml.network.scan.admin.grpc

import com.daml.ledger.api.v1.transaction.TransactionTree
import com.daml.network.codegen.CC.{Round => roundCodegen}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.store.ScanCCHistoryStore
import com.daml.network.scan.v0
import com.daml.network.scan.v0.{
  GetClosedRoundsResponse,
  GetCoinTransactionDetailsRequest,
  GetCoinTransactionDetailsResponse,
  GetHistoryResponse,
  ScanServiceGrpc,
}
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcScanService(
    ledgerClient: CoinLedgerClient,
    svcUser: String,
    store: ScanCCHistoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScanServiceGrpc.ScanService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcScanService")

  @nowarn("cat=unused")
  override def getSvcPartyId(request: Empty): Future[v0.GetSvcPartyIdResponse] =
    withSpanFromGrpcContext("GrpcScanService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(svcUser)
      } yield v0.GetSvcPartyIdResponse(party.toProtoPrimitive)
    }

  override def getTransferContext(request: Empty): Future[v0.GetTransferContextResponse] =
    withSpanFromGrpcContext("GrpcScanService") { traceContext => span =>
      for {
        svc <- connection.getPrimaryParty(svcUser)
        rounds <- connection.activeContracts(svc, roundCodegen.OpenMiningRound)
      } yield {
        val decodedRounds: Seq[Contract[roundCodegen.OpenMiningRound]] =
          rounds.map(r => Contract.fromCodegenContract[roundCodegen.OpenMiningRound](r))
        v0.GetTransferContextResponse(
          openMiningRounds = decodedRounds.map(_.toProtoV0),
          latestOpenMiningRound = decodedRounds.maxByOption(_.payload.round.number).map(_.toProtoV0),
        )
      }
    }

  override def getHistory(request: Empty): Future[GetHistoryResponse] =
    withSpanFromGrpcContext("GrpcScanService") { traceContext => span =>
      for {
        result <- store.getCCHistory
      } yield v0.GetHistoryResponse(result.map(_.toProtoV0))
    }

  override def getCoinTransactionDetails(
      request: GetCoinTransactionDetailsRequest
  ): Future[GetCoinTransactionDetailsResponse] = withSpanFromGrpcContext("GrpcScanService") {
    traceContext => span =>
      for {
        svc <- connection.getPrimaryParty(svcUser)
        treeO <- connection.transactionTreeById(Seq(svc), request.transactionId)
        tree: TransactionTree = treeO.getOrElse(
          sys.error(
            s"Ledger didn't return a result when querying for the transaction tree of transaction ${request.transactionId}"
          )
        )
      } yield v0.GetCoinTransactionDetailsResponse(Some(tree))
  }

  override def getClosedRounds(request: Empty): Future[GetClosedRoundsResponse] =
    withSpanFromGrpcContext("GrpcScanService") { traceContext => span =>
      for {
        svc <- connection.getPrimaryParty(svcUser)
        rounds <- connection.activeContracts(svc, roundCodegen.ClosedMiningRound)
      } yield {
        val filteredRounds = rounds
          .sortWith(_.value.round.number > _.value.round.number)
        v0.GetClosedRoundsResponse(
          filteredRounds.map(r =>
            Contract.fromCodegenContract[roundCodegen.ClosedMiningRound](r).toProtoV0
          )
        )
      }
    }

}
