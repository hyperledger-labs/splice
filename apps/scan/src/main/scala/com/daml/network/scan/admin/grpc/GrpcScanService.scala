package com.daml.network.scan.admin.grpc

import com.daml.ledger.api.v1.transaction.TransactionTree
import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.v0
import com.daml.network.scan.v0.*
import com.daml.network.store.AcsStore.QueryResult
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcScanService(
    ledgerClient: CoinLedgerClient,
    store: ScanStore,
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
      Future.successful(v0.GetSvcPartyIdResponse(store.svcParty.toProtoPrimitive))
    }

  override def getTransferContext(request: Empty): Future[v0.GetTransferContextResponse] =
    withSpanFromGrpcContext("GrpcScanService") { traceContext => span =>
      for {
        QueryResult(_, coinRules) <- store.lookupCoinRules()
        QueryResult(_, rounds) <- store.listContracts(roundCodegen.OpenMiningRound.COMPANION)
      } yield {
        v0.GetTransferContextResponse(
          coinRules = coinRules.map(_.toProtoV0),
          latestOpenMiningRound = rounds.maxByOption(_.payload.round.number).map(_.toProtoV0),
          openMiningRounds = rounds.map(_.toProtoV0),
        )
      }
    }

  override def getHistory(request: Empty): Future[GetHistoryResponse] =
    withSpanFromGrpcContext("GrpcScanService") { traceContext => span =>
      for {
        result <- store.history.getCCHistory
      } yield v0.GetHistoryResponse(result.map(_.toProtoV0))
    }

  override def getCoinTransactionDetails(
      request: GetCoinTransactionDetailsRequest
  ): Future[GetCoinTransactionDetailsResponse] = withSpanFromGrpcContext("GrpcScanService") {
    traceContext => span =>
      for {
        tree <- connection.tryGetTransactionTreeById(Seq(store.svcParty), request.transactionId)
      } yield v0.GetCoinTransactionDetailsResponse(
        Some(TransactionTree.fromJavaProto(tree.toProto))
      )
  }

  override def getClosedRounds(request: Empty): Future[GetClosedRoundsResponse] =
    withSpanFromGrpcContext("GrpcScanService") { traceContext => span =>
      for {
        QueryResult(_, rounds) <- store.listContracts(roundCodegen.ClosedMiningRound.COMPANION)
      } yield {
        val filteredRounds = rounds.sortWith(_.payload.round.number > _.payload.round.number)
        v0.GetClosedRoundsResponse(filteredRounds.map(r => r.toProtoV0))
      }
    }
}
