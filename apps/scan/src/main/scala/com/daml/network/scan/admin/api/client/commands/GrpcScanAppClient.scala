package com.daml.network.scan.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.api.v1.transaction.{TransactionTree => ScalaTransactionTree}
import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.network.codegen.java.cc.{coin => coinCodegen, round => roundCodegen}
import com.daml.network.history.CoinTransaction
import com.daml.network.scan.v0
import com.daml.network.scan.v0.GetClosedRoundsResponse
import com.daml.network.scan.v0.ScanServiceGrpc.ScanServiceStub
import com.daml.network.util.{JavaContract as Contract}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcScanAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = ScanServiceStub
    override def createService(channel: ManagedChannel): ScanServiceStub =
      v0.ScanServiceGrpc.stub(channel)
  }

  final case class GetSvcPartyId() extends BaseCommand[Empty, v0.GetSvcPartyIdResponse, PartyId] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())
    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[v0.GetSvcPartyIdResponse] =
      service.getSvcPartyId(req)
    override def handleResponse(response: v0.GetSvcPartyIdResponse): Either[String, PartyId] =
      PartyId.fromProtoPrimitive(response.svcPartyId)
  }

  final case class GetHistory()
      extends BaseCommand[Empty, v0.GetHistoryResponse, Seq[CoinTransaction]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())
    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[v0.GetHistoryResponse] =
      service.getHistory(req)
    override def handleResponse(
        response: v0.GetHistoryResponse
    ): Either[String, Seq[CoinTransaction]] =
      response.transactions.traverse(CoinTransaction.fromProtoV0).leftMap(_.toString)

  }

  case class TransferContext(
      coinRules: Option[
        Contract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules]
      ],
      latestOpenMiningRound: Option[
        Contract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
      ],
      openMiningRounds: Seq[
        Contract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
      ],
  )

  final case class GetTransferContext()
      extends BaseCommand[Empty, v0.GetTransferContextResponse, TransferContext] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())
    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[v0.GetTransferContextResponse] =
      service.getTransferContext(req)
    override def handleResponse(
        response: v0.GetTransferContextResponse
    ): Either[String, TransferContext] =
      for {
        coinRules <- response.coinRules
          .traverse(coinRules => Contract.fromProto(coinCodegen.CoinRules.COMPANION)(coinRules))
          .leftMap(_.toString)
        openMiningRounds <- response.openMiningRounds
          .traverse(round => Contract.fromProto(roundCodegen.OpenMiningRound.COMPANION)(round))
          .leftMap(_.toString)
        latestOpenMiningRound <- response.latestOpenMiningRound
          .traverse(round => Contract.fromProto(roundCodegen.OpenMiningRound.COMPANION)(round))
          .leftMap(_.toString)
      } yield TransferContext(
        coinRules,
        latestOpenMiningRound,
        openMiningRounds,
      )
  }

  final case class GetCoinTransactionDetails(transactionId: String)
      extends BaseCommand[
        v0.GetCoinTransactionDetailsRequest,
        v0.GetCoinTransactionDetailsResponse,
        TransactionTree,
      ] {
    override def createRequest(): Either[String, v0.GetCoinTransactionDetailsRequest] =
      Right(v0.GetCoinTransactionDetailsRequest(transactionId))
    override def submitRequest(
        service: ScanServiceStub,
        req: v0.GetCoinTransactionDetailsRequest,
    ): Future[v0.GetCoinTransactionDetailsResponse] =
      service.getCoinTransactionDetails(req)
    override def handleResponse(
        response: v0.GetCoinTransactionDetailsResponse
    ): Either[String, TransactionTree] =
      response.tree
        .toRight(
          "received no transaction tree in the GetCoinTransactionDetailsResponse response from the server"
        )
        .map(t => TransactionTree.fromProto(ScalaTransactionTree.toJavaProto(t)))
  }

  final case class GetClosedRounds()
      extends BaseCommand[
        Empty,
        v0.GetClosedRoundsResponse,
        Seq[Contract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]],
      ] {

    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[GetClosedRoundsResponse] = service.getClosedRounds(req)

    override def handleResponse(
        response: GetClosedRoundsResponse
    ): Either[String, Seq[
      Contract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]
    ]] = {
      response.rounds
        .traverse(round => Contract.fromProto(roundCodegen.ClosedMiningRound.COMPANION)(round))
        .leftMap(_.toString)
    }
  }
}
