package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.lf.data.Numeric
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.util.{Contract, Proto, Value}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import io.grpc.ManagedChannel
import com.daml.ledger.client.binding.Primitive
import com.digitalasset.network.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.digitalasset.network.CN.{Wallet => walletCodegen}
import com.google.protobuf.empty.Empty

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
          validator = Some(Proto.encode(validator))
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

    override def createRequest(): Either[String, v0.TapRequest] = {
      Right(
        v0.TapRequest(amount = Proto.encode(amount))
      )
    }

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.TapRequest,
    ): Future[v0.TapResponse] = service.tap(request)

    override def handleResponse(
        response: v0.TapResponse
    ): Either[String, Primitive.ContractId[coinCodegen.Coin]] =
      Proto.decodeContractId[coinCodegen.Coin](response.contractId)
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

  case class AcceptAppPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppPaymentRequest],
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ) extends BaseCommand[
        v0.AcceptAppPaymentRequestRequest,
        v0.AcceptAppPaymentRequestResponse,
        Primitive.ContractId[walletCodegen.AcceptedAppPayment],
      ] {

    override def createRequest(): Either[String, v0.AcceptAppPaymentRequestRequest] =
      Right(
        v0.AcceptAppPaymentRequestRequest(
          Proto.encode(requestId),
          Proto.encode(coinId),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptAppPaymentRequestRequest,
    ): Future[v0.AcceptAppPaymentRequestResponse] = service.acceptAppPaymentRequest(request)

    override def handleResponse(
        response: v0.AcceptAppPaymentRequestResponse
    ): Either[String, Primitive.ContractId[walletCodegen.AcceptedAppPayment]] =
      Proto.decodeContractId[walletCodegen.AcceptedAppPayment](
        response.acceptedPaymentContractId
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
          Proto.encode(requestId)
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
        v0.ProposePaymentChannelRequest(Proto.encode(receiver))
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ProposePaymentChannelRequest,
    ): Future[v0.ProposePaymentChannelResponse] =
      service.proposePaymentChannel(request)

    override def handleResponse(
        response: v0.ProposePaymentChannelResponse
    ): Either[String, Primitive.ContractId[walletCodegen.PaymentChannelProposal]] =
      Proto.decodeContractId[walletCodegen.PaymentChannelProposal](response.proposalContractId)
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
          Proto.encode(requestId)
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
      Proto.decodeContractId[walletCodegen.PaymentChannel](response.channelContractId)
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
          receiver = Proto.encode(receiver),
          quantity = Proto.encode(quantity),
          coinContractId = Proto.encode(coinId),
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

  case class CreateOnChannelPaymentRequest(
      sender: PartyId,
      quantity: BigDecimal,
      description: String,
  ) extends BaseCommand[
        v0.CreateOnChannelPaymentRequestRequest,
        v0.CreateOnChannelPaymentRequestResponse,
        Primitive.ContractId[walletCodegen.OnChannelPaymentRequest],
      ] {
    override def createRequest(): Either[String, v0.CreateOnChannelPaymentRequestRequest] =
      Right(
        v0.CreateOnChannelPaymentRequestRequest(
          sender = Proto.encode(sender),
          quantity = Proto.encode(quantity),
          description = description,
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.CreateOnChannelPaymentRequestRequest,
    ): Future[v0.CreateOnChannelPaymentRequestResponse] =
      service.createOnChannelPaymentRequest(request)

    override def handleResponse(
        response: v0.CreateOnChannelPaymentRequestResponse
    ): Either[String, Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]] =
      Proto.decodeContractId[walletCodegen.OnChannelPaymentRequest](response.requestContractId)
  }

  case class AcceptOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest],
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ) extends BaseCommand[
        v0.AcceptOnChannelPaymentRequestRequest,
        v0.AcceptOnChannelPaymentRequestResponse,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.AcceptOnChannelPaymentRequestRequest] =
      Right(
        v0.AcceptOnChannelPaymentRequestRequest(
          requestContractId = Proto.encode(requestId),
          coinContractId = Proto.encode(coinId),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptOnChannelPaymentRequestRequest,
    ): Future[v0.AcceptOnChannelPaymentRequestResponse] =
      service.acceptOnChannelPaymentRequest(request)

    override def handleResponse(
        response: v0.AcceptOnChannelPaymentRequestResponse
    ): Either[String, Unit] =
      Right(())
  }

  case class RejectOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ) extends BaseCommand[
        v0.RejectOnChannelPaymentRequestRequest,
        v0.RejectOnChannelPaymentRequestResponse,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.RejectOnChannelPaymentRequestRequest] =
      Right(
        v0.RejectOnChannelPaymentRequestRequest(
          requestContractId = Proto.encode(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectOnChannelPaymentRequestRequest,
    ): Future[v0.RejectOnChannelPaymentRequestResponse] =
      service.rejectOnChannelPaymentRequest(request)

    override def handleResponse(
        response: v0.RejectOnChannelPaymentRequestResponse
    ): Either[String, Unit] =
      Right(())
  }

  case class WithdrawOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ) extends BaseCommand[
        v0.WithdrawOnChannelPaymentRequestRequest,
        v0.WithdrawOnChannelPaymentRequestResponse,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.WithdrawOnChannelPaymentRequestRequest] =
      Right(
        v0.WithdrawOnChannelPaymentRequestRequest(
          requestContractId = Proto.encode(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.WithdrawOnChannelPaymentRequestRequest,
    ): Future[v0.WithdrawOnChannelPaymentRequestResponse] =
      service.withdrawOnChannelPaymentRequest(request)

    override def handleResponse(
        response: v0.WithdrawOnChannelPaymentRequestResponse
    ): Either[String, Unit] =
      Right(())
  }

  case class ListAppRewards()
      extends BaseCommand[Empty, v0.ListAppRewardsResponse, Seq[
        Contract[coinCodegen.AppReward]
      ]] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: WalletServiceStub,
        request: Empty,
    ): Future[v0.ListAppRewardsResponse] = service.listAppRewards(request)

    override def handleResponse(
        response: v0.ListAppRewardsResponse
    ): Either[String, Seq[Contract[coinCodegen.AppReward]]] =
      response.appRewards
        .traverse(req => Contract.fromProto(coinCodegen.AppReward)(req))
        .leftMap(_.toString)
  }

  case class ListValidatorRewards()
      extends BaseCommand[Empty, v0.ListValidatorRewardsResponse, Seq[
        Contract[coinCodegen.ValidatorReward]
      ]] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: WalletServiceStub,
        request: Empty,
    ): Future[v0.ListValidatorRewardsResponse] = service.listValidatorRewards(request)

    override def handleResponse(
        response: v0.ListValidatorRewardsResponse
    ): Either[String, Seq[Contract[coinCodegen.ValidatorReward]]] =
      response.validatorRewards
        .traverse(req => Contract.fromProto(coinCodegen.ValidatorReward)(req))
        .leftMap(_.toString)
  }

  case class RedistributeOutput(
      exactQuantity: Option[BigDecimal]
  ) {
    def toProtoV0: v0.RedistributeOutput =
      v0.RedistributeOutput(exactQuantity.fold("")(Proto.encode(_)))
  }

  /** Redistribute the transfer inputs via a self-transfer. The outputs
    * declare the number of outputs and for each output the desired quantity or None
    * if it should be a floating output.
    */
  case class Redistribute(
      inputs: Seq[Value[coinRulesCodegen.TransferInput]],
      outputs: Seq[RedistributeOutput],
  ) extends BaseCommand[v0.RedistributeRequest, v0.RedistributeResponse, Seq[
        Primitive.ContractId[coinCodegen.Coin]
      ]] {

    override def createRequest(): Either[String, v0.RedistributeRequest] =
      Right(
        v0.RedistributeRequest(
          inputs = inputs.map(_.toProtoV0),
          outputs = outputs.map(_.toProtoV0),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RedistributeRequest,
    ): Future[v0.RedistributeResponse] = service.redistribute(request)

    override def handleResponse(
        response: v0.RedistributeResponse
    ): Either[String, Seq[Primitive.ContractId[coinCodegen.Coin]]] =
      response.coinContractIds.traverse(Proto.decodeContractId[coinCodegen.Coin](_))
  }
}
