package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.util.Contract
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import io.grpc.ManagedChannel
import com.daml.ledger.client.binding.Primitive
import com.digitalasset.network.CC.{Coin => coinCodegen}
import com.digitalasset.network.CN.Wallet.{PaymentRequest => walletCodegen}

import scala.concurrent.Future

object WalletAppCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = WalletServiceStub
    override def createService(channel: ManagedChannel): WalletServiceStub =
      v0.WalletServiceGrpc.stub(channel)
  }

  case class Initialize(validator: PartyId)
      extends BaseCommand[v0.InitializeRequest, v0.InitializeResponse, Unit] {

    override def createRequest(): Either[String, v0.InitializeRequest] =
      Right(
        v0.InitializeRequest(
          validator = Some(validator.toProtoPrimitive)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.InitializeRequest,
    ): Future[v0.InitializeResponse] = service.initialize(request)

    override def handleResponse(
        response: v0.InitializeResponse
    ): Either[String, Unit] = Right(())
  }

  case class List()
      extends BaseCommand[v0.ListRequest, v0.ListResponse, Seq[Contract[coinCodegen.Coin]]] {

    override def createRequest(): Either[String, v0.ListRequest] =
      Right(
        v0.ListRequest()
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListRequest,
    ): Future[v0.ListResponse] = service.list(request)

    override def handleResponse(
        response: v0.ListResponse
    ): Either[String, Seq[Contract[coinCodegen.Coin]]] =
      response.coins
        .traverse(coin => Contract.fromProto(coinCodegen.Coin)(coin))
        .leftMap(_.toString)
  }

  case class Tap(amount: com.daml.lf.data.Numeric)
      extends BaseCommand[v0.TapRequest, v0.TapResponse, Primitive.ContractId[coinCodegen.Coin]] {

    override def createRequest(): Either[String, v0.TapRequest] =
      Right(
        v0.TapRequest(amount = amount.toString)
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.TapRequest,
    ): Future[v0.TapResponse] = service.tap(request)

    override def handleResponse(
        response: v0.TapResponse
    ): Either[String, Primitive.ContractId[coinCodegen.Coin]] =
      Right(Primitive.ContractId.apply[coinCodegen.Coin](response.contractId))
  }

  case class ListPaymentRequests()
      extends BaseCommand[v0.ListPaymentRequestsRequest, v0.ListPaymentRequestsResponse, Seq[
        Contract[walletCodegen.PaymentRequest]
      ]] {

    override def createRequest(): Either[String, v0.ListPaymentRequestsRequest] =
      Right(
        v0.ListPaymentRequestsRequest()
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListPaymentRequestsRequest,
    ): Future[v0.ListPaymentRequestsResponse] = service.listPaymentRequests(request)

    override def handleResponse(
        response: v0.ListPaymentRequestsResponse
    ): Either[String, Seq[Contract[walletCodegen.PaymentRequest]]] =
      response.paymentRequests
        .traverse(req => Contract.fromProto(walletCodegen.PaymentRequest)(req))
        .leftMap(_.toString)
  }

  case class ApprovePaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.PaymentRequest],
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ) extends BaseCommand[
        v0.ApprovePaymentRequestRequest,
        v0.ApprovePaymentRequestResponse,
        Primitive.ContractId[walletCodegen.ApprovedPayment],
      ] {

    override def createRequest(): Either[String, v0.ApprovePaymentRequestRequest] =
      Right(
        v0.ApprovePaymentRequestRequest(
          ApiTypes.ContractId.unwrap(requestId),
          ApiTypes.ContractId.unwrap(coinId),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ApprovePaymentRequestRequest,
    ): Future[v0.ApprovePaymentRequestResponse] = service.approvePaymentRequest(request)

    override def handleResponse(
        response: v0.ApprovePaymentRequestResponse
    ): Either[String, Primitive.ContractId[walletCodegen.ApprovedPayment]] =
      Right(
        Primitive.ContractId[walletCodegen.ApprovedPayment](
          response.approvedPaymentContractId
        )
      )
  }

  case class RejectPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.PaymentRequest]
  ) extends BaseCommand[
        v0.RejectPaymentRequestRequest,
        v0.RejectPaymentRequestResponse,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectPaymentRequestRequest] =
      Right(
        v0.RejectPaymentRequestRequest(
          ApiTypes.ContractId.unwrap(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectPaymentRequestRequest,
    ): Future[v0.RejectPaymentRequestResponse] = service.rejectPaymentRequest(request)

    override def handleResponse(
        response: v0.RejectPaymentRequestResponse
    ): Either[String, Unit] =
      Right(())
  }

}
