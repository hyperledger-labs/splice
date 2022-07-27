package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.network.wallet.v0
import com.daml.network.wallet.domain.{CantonCoin, PaymentRequest}
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import io.grpc.ManagedChannel
import com.daml.ledger.client.binding.Primitive
import com.digitalasset.network.CC.Coin.Coin

import scala.concurrent.Future

object WalletAppCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = WalletServiceStub
    override def createService(channel: ManagedChannel): WalletServiceStub =
      v0.WalletServiceGrpc.stub(channel)
  }

  case class Initialize(svc: PartyId, validator: PartyId)
      extends BaseCommand[v0.InitializeRequest, v0.InitializeResponse, Unit] {

    override def createRequest(): Either[String, v0.InitializeRequest] =
      Right(
        v0.InitializeRequest(
          svc = Some(svc.toProtoPrimitive),
          validator = Some(validator.toProtoPrimitive),
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

  case class List() extends BaseCommand[v0.ListRequest, v0.ListResponse, Seq[CantonCoin]] {

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
    ): Either[String, Seq[CantonCoin]] =
      response.coins.traverse(coin => CantonCoin.fromProto(coin)).leftMap(_.toString)
  }

  case class Tap(amount: com.daml.lf.data.Numeric)
      extends BaseCommand[v0.TapRequest, v0.TapResponse, Primitive.ContractId[Coin]] {

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
    ): Either[String, Primitive.ContractId[Coin]] =
      Right(Primitive.ContractId.apply[Coin](response.contractId))
  }

  case class ListPaymentRequests()
      extends BaseCommand[v0.ListPaymentRequestsRequest, v0.ListPaymentRequestsResponse, Seq[
        PaymentRequest
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
    ): Either[String, Seq[PaymentRequest]] =
      response.paymentRequests.traverse(req => PaymentRequest.fromProto(req)).leftMap(_.toString)
  }
}
