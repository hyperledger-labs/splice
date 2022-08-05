package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.lf.data.Numeric
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.util.Contract
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import io.grpc.ManagedChannel
import com.daml.ledger.client.binding.Primitive
import com.digitalasset.network.CC.{Coin => coinCodegen}
import com.digitalasset.network.CN.{Wallet => walletCodegen}

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

  case class Tap(amount: BigDecimal)
      extends BaseCommand[v0.TapRequest, v0.TapResponse, Primitive.ContractId[coinCodegen.Coin]] {

    override def createRequest(): Either[String, v0.TapRequest] =
      Right(
        v0.TapRequest(amount = Numeric.toString(amount.bigDecimal))
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

  case class ListAppPaymentRequests()
      extends BaseCommand[v0.ListAppPaymentRequestsRequest, v0.ListAppPaymentRequestsResponse, Seq[
        Contract[walletCodegen.AppPaymentRequest]
      ]] {

    override def createRequest(): Either[String, v0.ListAppPaymentRequestsRequest] =
      Right(
        v0.ListAppPaymentRequestsRequest()
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAppPaymentRequestsRequest,
    ): Future[v0.ListAppPaymentRequestsResponse] = service.listAppPaymentRequests(request)

    override def handleResponse(
        response: v0.ListAppPaymentRequestsResponse
    ): Either[String, Seq[Contract[walletCodegen.AppPaymentRequest]]] =
      response.paymentRequests
        .traverse(req => Contract.fromProto(walletCodegen.AppPaymentRequest)(req))
        .leftMap(_.toString)
  }

  case class ApproveAppPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppPaymentRequest],
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ) extends BaseCommand[
        v0.ApproveAppPaymentRequestRequest,
        v0.ApproveAppPaymentRequestResponse,
        Primitive.ContractId[walletCodegen.ApprovedAppPayment],
      ] {

    override def createRequest(): Either[String, v0.ApproveAppPaymentRequestRequest] =
      Right(
        v0.ApproveAppPaymentRequestRequest(
          ApiTypes.ContractId.unwrap(requestId),
          ApiTypes.ContractId.unwrap(coinId),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ApproveAppPaymentRequestRequest,
    ): Future[v0.ApproveAppPaymentRequestResponse] = service.approveAppPaymentRequest(request)

    override def handleResponse(
        response: v0.ApproveAppPaymentRequestResponse
    ): Either[String, Primitive.ContractId[walletCodegen.ApprovedAppPayment]] =
      Right(
        Primitive.ContractId[walletCodegen.ApprovedAppPayment](
          response.approvedPaymentContractId
        )
      )
  }

  case class RejectAppPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppPaymentRequest]
  ) extends BaseCommand[
        v0.RejectAppPaymentRequestRequest,
        v0.RejectAppPaymentRequestResponse,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectAppPaymentRequestRequest] =
      Right(
        v0.RejectAppPaymentRequestRequest(
          ApiTypes.ContractId.unwrap(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectAppPaymentRequestRequest,
    ): Future[v0.RejectAppPaymentRequestResponse] = service.rejectAppPaymentRequest(request)

    override def handleResponse(
        response: v0.RejectAppPaymentRequestResponse
    ): Either[String, Unit] =
      Right(())
  }

  case class ProposePaymentChannel(
      receiver: PartyId
  ) extends BaseCommand[
        v0.ProposePaymentChannelRequest,
        v0.ProposePaymentChannelResponse,
        Primitive.ContractId[walletCodegen.PaymentChannelProposal],
      ] {

    override def createRequest(): Either[String, v0.ProposePaymentChannelRequest] =
      Right(
        v0.ProposePaymentChannelRequest(
          receiver.toProtoPrimitive
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ProposePaymentChannelRequest,
    ): Future[v0.ProposePaymentChannelResponse] =
      service.proposePaymentChannel(request)

    override def handleResponse(
        response: v0.ProposePaymentChannelResponse
    ): Either[String, Primitive.ContractId[walletCodegen.PaymentChannelProposal]] =
      Right(
        Primitive.ContractId[walletCodegen.PaymentChannelProposal](
          response.proposalContractId
        )
      )
  }

  case class ListPaymentChannelProposals()
      extends BaseCommand[
        v0.ListPaymentChannelProposalsRequest,
        v0.ListPaymentChannelProposalsResponse,
        Seq[Contract[walletCodegen.PaymentChannelProposal]],
      ] {

    override def createRequest(): Either[String, v0.ListPaymentChannelProposalsRequest] =
      Right(
        v0.ListPaymentChannelProposalsRequest()
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListPaymentChannelProposalsRequest,
    ): Future[v0.ListPaymentChannelProposalsResponse] = service.listPaymentChannelProposals(request)

    override def handleResponse(
        response: v0.ListPaymentChannelProposalsResponse
    ): Either[String, Seq[Contract[walletCodegen.PaymentChannelProposal]]] =
      response.proposals
        .traverse(req => Contract.fromProto(walletCodegen.PaymentChannelProposal)(req))
        .leftMap(_.toString)
  }

  case class AcceptPaymentChannelProposal(
      requestId: Primitive.ContractId[walletCodegen.PaymentChannelProposal]
  ) extends BaseCommand[
        v0.AcceptPaymentChannelProposalRequest,
        v0.AcceptPaymentChannelProposalResponse,
        Primitive.ContractId[walletCodegen.PaymentChannel],
      ] {

    override def createRequest(): Either[String, v0.AcceptPaymentChannelProposalRequest] =
      Right(
        v0.AcceptPaymentChannelProposalRequest(
          ApiTypes.ContractId.unwrap(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptPaymentChannelProposalRequest,
    ): Future[v0.AcceptPaymentChannelProposalResponse] =
      service.acceptPaymentChannelProposal(request)

    override def handleResponse(
        response: v0.AcceptPaymentChannelProposalResponse
    ): Either[String, Primitive.ContractId[walletCodegen.PaymentChannel]] =
      Right(
        Primitive.ContractId[walletCodegen.PaymentChannel](
          response.channelContractId
        )
      )
  }

  case class ExecuteDirectTransfer(
      receiver: PartyId,
      quantity: BigDecimal,
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ) extends BaseCommand[
        v0.ExecuteDirectTransferRequest,
        v0.ExecuteDirectTransferResponse,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.ExecuteDirectTransferRequest] =
      Right(
        v0.ExecuteDirectTransferRequest(
          receiver = receiver.toProtoPrimitive,
          quantity = Numeric.toString(quantity.bigDecimal),
          coinContractId = ApiTypes.ContractId.unwrap(coinId),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ExecuteDirectTransferRequest,
    ): Future[v0.ExecuteDirectTransferResponse] =
      service.executeDirectTransfer(request)

    override def handleResponse(
        response: v0.ExecuteDirectTransferResponse
    ): Either[String, Unit] =
      Right(())

  }

}
