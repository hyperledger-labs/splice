package com.daml.network.scan.admin.grpc

import com.daml.ledger.api.v1.transaction.TransactionTree
import com.daml.network.codegen.java.cc.{coinrules as coinRulesCodegen, round as roundCodegen}
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
import com.daml.network.util.JavaContract
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
        coinRules <- connection
          .activeContracts(svc, coinRulesCodegen.CoinRules.COMPANION)
          .map(_.headOption)
        rounds <- connection.activeContracts(svc, roundCodegen.OpenMiningRound.COMPANION)
      } yield {
        val decodedCoinRules =
          coinRules.map(c =>
            JavaContract.fromCodegenContract[
              coinRulesCodegen.CoinRules.ContractId,
              coinRulesCodegen.CoinRules,
            ](c)
          )
        val decodedRounds: Seq[
          JavaContract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
        ] =
          rounds.map(r =>
            JavaContract.fromCodegenContract[
              roundCodegen.OpenMiningRound.ContractId,
              roundCodegen.OpenMiningRound,
            ](r)
          )
        v0.GetTransferContextResponse(
          coinRules = decodedCoinRules.map(_.toProtoV0),
          latestOpenMiningRound =
            decodedRounds.maxByOption(_.payload.round.number).map(_.toProtoV0),
          openMiningRounds = decodedRounds.map(_.toProtoV0),
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
        tree <- connection.tryGetTransactionTreeById(Seq(svc), request.transactionId)
      } yield v0.GetCoinTransactionDetailsResponse(
        Some(TransactionTree.fromJavaProto(tree.toProto))
      )
  }

  override def getClosedRounds(request: Empty): Future[GetClosedRoundsResponse] =
    withSpanFromGrpcContext("GrpcScanService") { traceContext => span =>
      for {
        svc <- connection.getPrimaryParty(svcUser)
        rounds <- connection.activeContracts(svc, roundCodegen.ClosedMiningRound.COMPANION)
      } yield {
        val filteredRounds = rounds
          .sortWith(_.data.round.number > _.data.round.number)
        v0.GetClosedRoundsResponse(
          filteredRounds.map(r =>
            JavaContract
              .fromCodegenContract[
                roundCodegen.ClosedMiningRound.ContractId,
                roundCodegen.ClosedMiningRound,
              ](r)
              .toProtoV0
          )
        )
      }
    }

}
