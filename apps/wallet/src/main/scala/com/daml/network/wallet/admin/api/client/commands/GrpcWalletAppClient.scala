package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.util.{Contract, Proto, Value}
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.wallet.v0.{GetBalanceRequest, GetBalanceResponse, WalletContext}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcWalletAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = WalletServiceStub
    override def createService(channel: ManagedChannel): WalletServiceStub =
      v0.WalletServiceGrpc.stub(channel)
  }

  case class Initialize(validator: PartyId) extends BaseCommand[v0.InitializeRequest, Empty, Unit] {

    override def createRequest(): Either[String, v0.InitializeRequest] =
      Right(
        v0.InitializeRequest(
          validatorPartyId = Proto.encode(validator)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.InitializeRequest,
    ): Future[Empty] = service.initialize(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
  }

  final case class CoinPosition(
      contract: Contract[coinCodegen.Coin],
      round: Long,
      accruedHoldingFee: BigDecimal,
      effectiveQuantity: BigDecimal,
  )

  final case class LockedCoinPosition(
      contract: Contract[coinCodegen.LockedCoin],
      round: Long,
      accruedHoldingFee: BigDecimal,
      effectiveQuantity: BigDecimal,
  )

  final case class ListResponse(
      coins: Seq[CoinPosition],
      lockedCoins: Seq[LockedCoinPosition],
  )

  case class List(walletCtx: WalletContext)
      extends BaseCommand[
        v0.ListRequest,
        v0.ListResponse,
        ListResponse,
      ] {

    override def createRequest(): Either[String, v0.ListRequest] = Right(
      v0.ListRequest(
        walletCtx = Some(walletCtx)
      )
    )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListRequest,
    ): Future[v0.ListResponse] = service.list(request)

    override def handleResponse(
        response: v0.ListResponse
    ): Either[String, ListResponse] = {
      def decodePositions(position: v0.CoinPosition) =
        for {
          contractOpt <- position.contract.toRight("Could not find contract payload")
          contract <- Contract.fromProto(coinCodegen.Coin)(contractOpt).leftMap(_.toString)

          accruedHoldingFee <- Proto.decode(Proto.BigDecimal)(position.accruedHoldingFee)
          effectiveQuantity <- Proto.decode(Proto.BigDecimal)(position.effectiveQuantity)
        } yield {
          new CoinPosition(
            contract,
            position.round,
            accruedHoldingFee,
            effectiveQuantity,
          )
        }

      def decodeLockedPositions(lockedPosition: v0.CoinPosition) =
        for {
          contractOpt <- lockedPosition.contract.toRight("Could not find contract payload")
          contract <- Contract.fromProto(coinCodegen.LockedCoin)(contractOpt).leftMap(_.toString)

          accruedHoldingFee <- Proto.decode(Proto.BigDecimal)(lockedPosition.accruedHoldingFee)
          effectiveQuantity <- Proto.decode(Proto.BigDecimal)(lockedPosition.effectiveQuantity)
        } yield {
          new LockedCoinPosition(
            contract,
            lockedPosition.round,
            accruedHoldingFee,
            effectiveQuantity,
          )
        }

      for {
        positions <- response.coins.traverse(decodePositions)
        lockedPositions <- response.lockedCoins.traverse(decodeLockedPositions)
      } yield {
        ListResponse(positions, lockedPositions)
      }
    }
  }

  case class Tap(quantity: BigDecimal, walletCtx: WalletContext)
      extends BaseCommand[v0.TapRequest, v0.TapResponse, Primitive.ContractId[coinCodegen.Coin]] {

    override def createRequest(): Either[String, v0.TapRequest] = {
      Right(
        v0.TapRequest(quantity = Proto.encode(quantity), walletCtx = Some(walletCtx))
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

  case class Balance(
      round: Long,
      unlockedQty: BigDecimal,
      lockedQty: BigDecimal,
      holdingFees: BigDecimal,
  )

  case class GetBalance(walletCtx: WalletContext)
      extends BaseCommand[v0.GetBalanceRequest, v0.GetBalanceResponse, Balance] {

    override def createRequest(): Either[String, v0.GetBalanceRequest] = {
      Right(
        v0.GetBalanceRequest(walletCtx = Some(walletCtx))
      )
    }

    override def submitRequest(
        service: WalletServiceStub,
        request: GetBalanceRequest,
    ): Future[GetBalanceResponse] = service.getBalance(request)

    override def handleResponse(
        response: v0.GetBalanceResponse
    ): Either[String, Balance] = Right(
      Balance(
        response.round,
        Proto.tryDecode(Proto.BigDecimal)(response.effectiveUnlockedQty),
        Proto.tryDecode(Proto.BigDecimal)(response.effectiveLockedQty),
        Proto.tryDecode(Proto.BigDecimal)(response.totalHoldingFees),
      )
    )
  }

  case class ListAppPaymentRequests(walletCtx: WalletContext)
      extends BaseCommand[v0.ListAppPaymentRequestsRequest, v0.ListAppPaymentRequestsResponse, Seq[
        Contract[walletCodegen.AppPaymentRequest]
      ]] {

    override def createRequest(): Either[String, v0.ListAppPaymentRequestsRequest] = Right(
      v0.ListAppPaymentRequestsRequest(Some(walletCtx))
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
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.AcceptAppPaymentRequestRequest,
        v0.AcceptAppPaymentRequestResponse,
        Primitive.ContractId[walletCodegen.AcceptedAppPayment],
      ] {

    override def createRequest(): Either[String, v0.AcceptAppPaymentRequestRequest] =
      Right(
        v0.AcceptAppPaymentRequestRequest(
          Proto.encode(requestId),
          Some(walletCtx),
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

  case class CancelPaymentChannelByReceiver(receiverPartyId: PartyId, walletCtx: WalletContext)
      extends BaseCommand[
        v0.CancelPaymentChannelByReceiverRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.CancelPaymentChannelByReceiverRequest] =
      Right(
        v0.CancelPaymentChannelByReceiverRequest(Proto.encode(receiverPartyId), Some(walletCtx))
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.CancelPaymentChannelByReceiverRequest,
    ): Future[Empty] = service.cancelPaymentChannelByReceiver(request)

    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }

  case class CancelPaymentChannelBySender(senderPartyId: PartyId, walletCtx: WalletContext)
      extends BaseCommand[
        v0.CancelPaymentChannelBySenderRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.CancelPaymentChannelBySenderRequest] =
      Right(v0.CancelPaymentChannelBySenderRequest(Proto.encode(senderPartyId), Some(walletCtx)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.CancelPaymentChannelBySenderRequest,
    ): Future[Empty] = service.cancelPaymentChannelBySender(request)

    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }

  case class RejectAppPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppPaymentRequest],
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.RejectAppPaymentRequestRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectAppPaymentRequestRequest] =
      Right(
        v0.RejectAppPaymentRequestRequest(
          Proto.encode(requestId),
          Some(walletCtx),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectAppPaymentRequestRequest,
    ): Future[Empty] = service.rejectAppPaymentRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class ListAcceptedAppPayments(walletCtx: WalletContext)
      extends BaseCommand[
        v0.ListAcceptedAppPaymentsRequest,
        v0.ListAcceptedAppPaymentsResponse,
        Seq[
          Contract[walletCodegen.AcceptedAppPayment]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListAcceptedAppPaymentsRequest] =
      Right(v0.ListAcceptedAppPaymentsRequest(Some(walletCtx)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAcceptedAppPaymentsRequest,
    ): Future[v0.ListAcceptedAppPaymentsResponse] = service.listAcceptedAppPayments(request)

    override def handleResponse(
        response: v0.ListAcceptedAppPaymentsResponse
    ): Either[String, Seq[Contract[walletCodegen.AcceptedAppPayment]]] =
      response.acceptedAppPayments
        .traverse(req => Contract.fromProto(walletCodegen.AcceptedAppPayment)(req))
        .leftMap(_.toString)
  }

  case class ProposePaymentChannel(
      receiver: PartyId,
      replacesChannelId: Option[Primitive.ContractId[walletCodegen.PaymentChannel]],
      allowRequests: Boolean,
      allowOffers: Boolean,
      allowDirectTransfers: Boolean,
      senderTransferFeeRatio: BigDecimal,
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.ProposePaymentChannelRequest,
        v0.ProposePaymentChannelResponse,
        Primitive.ContractId[walletCodegen.PaymentChannelProposal],
      ] {

    override def createRequest(): Either[String, v0.ProposePaymentChannelRequest] =
      Right(
        v0.ProposePaymentChannelRequest(
          Proto.encode(receiver),
          replacesChannelId = replacesChannelId.map(Proto.encode(_)),
          allowRequests = allowRequests,
          allowOffers = allowOffers,
          allowDirectTransfers = allowDirectTransfers,
          senderTransferFeeRatio = Proto.encode(senderTransferFeeRatio),
          Some(walletCtx),
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
      Proto.decodeContractId[walletCodegen.PaymentChannelProposal](response.proposalContractId)
  }

  case class ListPaymentChannelProposals(walletCtx: WalletContext)
      extends BaseCommand[
        v0.ListPaymentChannelProposalsRequest,
        v0.ListPaymentChannelProposalsResponse,
        Seq[Contract[walletCodegen.PaymentChannelProposal]],
      ] {

    override def createRequest(): Either[String, v0.ListPaymentChannelProposalsRequest] =
      Right(
        v0.ListPaymentChannelProposalsRequest(Some(walletCtx))
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

  case class ListPaymentChannels(walletCtx: WalletContext)
      extends BaseCommand[
        v0.ListPaymentChannelsRequest,
        v0.ListPaymentChannelsResponse,
        Seq[Contract[walletCodegen.PaymentChannel]],
      ] {

    override def createRequest(): Either[String, v0.ListPaymentChannelsRequest] =
      Right(
        v0.ListPaymentChannelsRequest(Some(walletCtx))
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListPaymentChannelsRequest,
    ): Future[v0.ListPaymentChannelsResponse] = service.listPaymentChannels(request)

    override def handleResponse(
        response: v0.ListPaymentChannelsResponse
    ): Either[String, Seq[Contract[walletCodegen.PaymentChannel]]] =
      response.channels
        .traverse(req => Contract.fromProto(walletCodegen.PaymentChannel)(req))
        .leftMap(_.toString)
  }

  case class AcceptPaymentChannelProposal(
      requestId: Primitive.ContractId[walletCodegen.PaymentChannelProposal],
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.AcceptPaymentChannelProposalRequest,
        v0.AcceptPaymentChannelProposalResponse,
        Primitive.ContractId[walletCodegen.PaymentChannel],
      ] {

    override def createRequest(): Either[String, v0.AcceptPaymentChannelProposalRequest] =
      Right(
        v0.AcceptPaymentChannelProposalRequest(
          Proto.encode(requestId),
          Some(walletCtx),
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
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.ExecuteDirectTransferRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.ExecuteDirectTransferRequest] =
      Right(
        v0.ExecuteDirectTransferRequest(
          receiverPartyId = Proto.encode(receiver),
          quantity = Proto.encode(quantity),
          walletCtx = Some(walletCtx),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ExecuteDirectTransferRequest,
    ): Future[Empty] =
      service.executeDirectTransfer(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())

  }

  case class CreateOnChannelPaymentRequest(
      sender: PartyId,
      quantity: BigDecimal,
      description: String,
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.CreateOnChannelPaymentRequestRequest,
        v0.CreateOnChannelPaymentRequestResponse,
        Primitive.ContractId[walletCodegen.OnChannelPaymentRequest],
      ] {
    override def createRequest(): Either[String, v0.CreateOnChannelPaymentRequestRequest] =
      Right(
        v0.CreateOnChannelPaymentRequestRequest(
          senderPartyId = Proto.encode(sender),
          quantity = Proto.encode(quantity),
          description = description,
          walletCtx = Some(walletCtx),
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

  case class ListOnChannelPaymentRequests(walletCtx: WalletContext)
      extends BaseCommand[
        v0.ListOnChannelPaymentRequestsRequest,
        v0.ListOnChannelPaymentRequestsResponse,
        Seq[
          Contract[walletCodegen.OnChannelPaymentRequest]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListOnChannelPaymentRequestsRequest] =
      Right(v0.ListOnChannelPaymentRequestsRequest(Some(walletCtx)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListOnChannelPaymentRequestsRequest,
    ): Future[v0.ListOnChannelPaymentRequestsResponse] =
      service.listOnChannelPaymentRequests(request)

    override def handleResponse(
        response: v0.ListOnChannelPaymentRequestsResponse
    ): Either[String, Seq[Contract[walletCodegen.OnChannelPaymentRequest]]] =
      response.paymentRequests
        .traverse(req => Contract.fromProto(walletCodegen.OnChannelPaymentRequest)(req))
        .leftMap(_.toString)
  }

  case class AcceptOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest],
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.AcceptOnChannelPaymentRequestRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.AcceptOnChannelPaymentRequestRequest] =
      Right(
        v0.AcceptOnChannelPaymentRequestRequest(
          requestContractId = Proto.encode(requestId),
          walletCtx = Some(walletCtx),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptOnChannelPaymentRequestRequest,
    ): Future[Empty] =
      service.acceptOnChannelPaymentRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class RejectOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest],
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.RejectOnChannelPaymentRequestRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.RejectOnChannelPaymentRequestRequest] =
      Right(
        v0.RejectOnChannelPaymentRequestRequest(
          requestContractId = Proto.encode(requestId),
          walletCtx = Some(walletCtx),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectOnChannelPaymentRequestRequest,
    ): Future[Empty] =
      service.rejectOnChannelPaymentRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class WithdrawOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest],
      walletCtx: WalletContext,
  ) extends BaseCommand[
        v0.WithdrawOnChannelPaymentRequestRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.WithdrawOnChannelPaymentRequestRequest] =
      Right(
        v0.WithdrawOnChannelPaymentRequestRequest(
          requestContractId = Proto.encode(requestId),
          walletCtx = Some(walletCtx),
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.WithdrawOnChannelPaymentRequestRequest,
    ): Future[Empty] =
      service.withdrawOnChannelPaymentRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class ListAppRewards(walletCtx: WalletContext)
      extends BaseCommand[v0.ListAppRewardsRequest, v0.ListAppRewardsResponse, Seq[
        Contract[coinCodegen.AppReward]
      ]] {

    override def createRequest(): Either[String, v0.ListAppRewardsRequest] =
      Right(v0.ListAppRewardsRequest(Some(walletCtx)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAppRewardsRequest,
    ): Future[v0.ListAppRewardsResponse] = service.listAppRewards(request)

    override def handleResponse(
        response: v0.ListAppRewardsResponse
    ): Either[String, Seq[Contract[coinCodegen.AppReward]]] =
      response.appRewards
        .traverse(req => Contract.fromProto(coinCodegen.AppReward)(req))
        .leftMap(_.toString)
  }

  case class ListValidatorRewards(walletCtx: WalletContext)
      extends BaseCommand[v0.ListValidatorRewardsRequest, v0.ListValidatorRewardsResponse, Seq[
        Contract[coinCodegen.ValidatorReward]
      ]] {

    override def createRequest(): Either[String, v0.ListValidatorRewardsRequest] =
      Right(v0.ListValidatorRewardsRequest(Some(walletCtx)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListValidatorRewardsRequest,
    ): Future[v0.ListValidatorRewardsResponse] = service.listValidatorRewards(request)

    override def handleResponse(
        response: v0.ListValidatorRewardsResponse
    ): Either[String, Seq[Contract[coinCodegen.ValidatorReward]]] =
      response.validatorRewards
        .traverse(req => Contract.fromProto(coinCodegen.ValidatorReward)(req))
        .leftMap(_.toString)
  }

  case class CollectRewards(round: Long, walletCtx: WalletContext)
      extends BaseCommand[v0.CollectRewardsRequest, Empty, Unit] {

    override def createRequest(): Either[String, v0.CollectRewardsRequest] =
      Right(v0.CollectRewardsRequest(Some(walletCtx)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.CollectRewardsRequest,
    ): Future[Empty] = service.collectRewards(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
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
      walletCtx: WalletContext,
  ) extends BaseCommand[v0.RedistributeRequest, v0.RedistributeResponse, Seq[
        Primitive.ContractId[coinCodegen.Coin]
      ]] {

    override def createRequest(): Either[String, v0.RedistributeRequest] =
      Right(
        v0.RedistributeRequest(
          inputs = inputs.map(_.toProtoV0),
          outputs = outputs.map(_.toProtoV0),
          walletCtx = Some(walletCtx),
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
