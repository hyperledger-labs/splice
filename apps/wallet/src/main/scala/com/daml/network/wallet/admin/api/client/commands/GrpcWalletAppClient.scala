package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.util.{Contract, Proto, Value}
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.wallet.v0.{GetBalanceRequest, GetBalanceResponse}
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

  case class List()
      extends BaseCommand[
        v0.ListRequest,
        v0.ListResponse,
        ListResponse,
      ] {

    override def createRequest(): Either[String, v0.ListRequest] = Right(
      v0.ListRequest()
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

  case class Tap(quantity: BigDecimal)
      extends BaseCommand[v0.TapRequest, v0.TapResponse, Primitive.ContractId[coinCodegen.Coin]] {

    override def createRequest(): Either[String, v0.TapRequest] = {
      Right(
        v0.TapRequest(quantity = Proto.encode(quantity))
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

  case class GetBalance()
      extends BaseCommand[v0.GetBalanceRequest, v0.GetBalanceResponse, Balance] {

    override def createRequest(): Either[String, v0.GetBalanceRequest] = {
      Right(
        v0.GetBalanceRequest()
      )
    }

    override def submitRequest(
        service: WalletServiceStub,
        request: GetBalanceRequest,
    ): Future[GetBalanceResponse] = service.getBalance(request)

    override def handleResponse(
        response: v0.GetBalanceResponse
    ): Either[String, Balance] = for {
      effectiveUnlockedQty <- Proto.decode(Proto.BigDecimal)(response.effectiveUnlockedQty)
      effectiveLockedQty <- Proto.decode(Proto.BigDecimal)(response.effectiveLockedQty)
      totalHoldingFees <- Proto.decode(Proto.BigDecimal)(response.totalHoldingFees)
    } yield {
      Balance(
        response.round,
        effectiveUnlockedQty,
        effectiveLockedQty,
        totalHoldingFees,
      )
    }
  }

  case class ListAppMultiPaymentRequests()
      extends BaseCommand[
        v0.ListAppMultiPaymentRequestsRequest,
        v0.ListAppMultiPaymentRequestsResponse,
        Seq[
          Contract[walletCodegen.AppMultiPaymentRequest]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListAppMultiPaymentRequestsRequest] = Right(
      v0.ListAppMultiPaymentRequestsRequest()
    )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAppMultiPaymentRequestsRequest,
    ): Future[v0.ListAppMultiPaymentRequestsResponse] = service.listAppMultiPaymentRequests(request)

    override def handleResponse(
        response: v0.ListAppMultiPaymentRequestsResponse
    ): Either[String, Seq[Contract[walletCodegen.AppMultiPaymentRequest]]] =
      response.paymentRequests
        .traverse(req => Contract.fromProto(walletCodegen.AppMultiPaymentRequest)(req))
        .leftMap(_.toString)
  }

  case class AcceptAppMultiPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppMultiPaymentRequest]
  ) extends BaseCommand[
        v0.AcceptAppMultiPaymentRequestRequest,
        v0.AcceptAppMultiPaymentRequestResponse,
        Primitive.ContractId[walletCodegen.AcceptedAppMultiPayment],
      ] {

    override def createRequest(): Either[String, v0.AcceptAppMultiPaymentRequestRequest] =
      Right(
        v0.AcceptAppMultiPaymentRequestRequest(
          requestContractId = Proto.encode(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptAppMultiPaymentRequestRequest,
    ): Future[v0.AcceptAppMultiPaymentRequestResponse] =
      service.acceptAppMultiPaymentRequest(request)

    override def handleResponse(
        response: v0.AcceptAppMultiPaymentRequestResponse
    ): Either[String, Primitive.ContractId[walletCodegen.AcceptedAppMultiPayment]] =
      Proto.decodeContractId[walletCodegen.AcceptedAppMultiPayment](
        response.acceptedMultiPaymentContractId
      )
  }

  case class CancelPaymentChannelByReceiver(receiverPartyId: PartyId)
      extends BaseCommand[
        v0.CancelPaymentChannelByReceiverRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.CancelPaymentChannelByReceiverRequest] =
      Right(
        v0.CancelPaymentChannelByReceiverRequest(receiverPartyId = Proto.encode(receiverPartyId))
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.CancelPaymentChannelByReceiverRequest,
    ): Future[Empty] = service.cancelPaymentChannelByReceiver(request)

    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }

  case class CancelPaymentChannelBySender(senderPartyId: PartyId)
      extends BaseCommand[
        v0.CancelPaymentChannelBySenderRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.CancelPaymentChannelBySenderRequest] =
      Right(v0.CancelPaymentChannelBySenderRequest(senderPartyId = Proto.encode(senderPartyId)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.CancelPaymentChannelBySenderRequest,
    ): Future[Empty] = service.cancelPaymentChannelBySender(request)

    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }

  case class RejectAppMultiPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppMultiPaymentRequest]
  ) extends BaseCommand[
        v0.RejectAppMultiPaymentRequestRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectAppMultiPaymentRequestRequest] =
      Right(
        v0.RejectAppMultiPaymentRequestRequest(
          requestContractId = Proto.encode(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectAppMultiPaymentRequestRequest,
    ): Future[Empty] = service.rejectAppMultiPaymentRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class ListAcceptedAppMultiPayments()
      extends BaseCommand[
        v0.ListAcceptedAppMultiPaymentsRequest,
        v0.ListAcceptedAppMultiPaymentsResponse,
        Seq[
          Contract[walletCodegen.AcceptedAppMultiPayment]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListAcceptedAppMultiPaymentsRequest] =
      Right(v0.ListAcceptedAppMultiPaymentsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAcceptedAppMultiPaymentsRequest,
    ): Future[v0.ListAcceptedAppMultiPaymentsResponse] =
      service.listAcceptedAppMultiPayments(request)

    override def handleResponse(
        response: v0.ListAcceptedAppMultiPaymentsResponse
    ): Either[String, Seq[Contract[walletCodegen.AcceptedAppMultiPayment]]] =
      response.acceptedAppMultiPayments
        .traverse(req => Contract.fromProto(walletCodegen.AcceptedAppMultiPayment)(req))
        .leftMap(_.toString)
  }

  case class ListSubscriptionRequests()
      extends BaseCommand[
        v0.ListSubscriptionRequestsRequest,
        v0.ListSubscriptionRequestsResponse,
        Seq[
          Contract[walletCodegen.Subscriptions.SubscriptionRequest]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListSubscriptionRequestsRequest] =
      Right(v0.ListSubscriptionRequestsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListSubscriptionRequestsRequest,
    ): Future[v0.ListSubscriptionRequestsResponse] = service.listSubscriptionRequests(request)

    override def handleResponse(
        response: v0.ListSubscriptionRequestsResponse
    ): Either[String, Seq[Contract[walletCodegen.Subscriptions.SubscriptionRequest]]] =
      response.subscriptionRequests
        .traverse(req => Contract.fromProto(walletCodegen.Subscriptions.SubscriptionRequest)(req))
        .leftMap(_.toString)
  }

  case class ListSubscriptionIdleStates()
      extends BaseCommand[
        v0.ListSubscriptionIdleStatesRequest,
        v0.ListSubscriptionIdleStatesResponse,
        Seq[
          Contract[walletCodegen.Subscriptions.SubscriptionIdleState]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListSubscriptionIdleStatesRequest] =
      Right(v0.ListSubscriptionIdleStatesRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListSubscriptionIdleStatesRequest,
    ): Future[v0.ListSubscriptionIdleStatesResponse] = service.listSubscriptionIdleStates(request)

    override def handleResponse(
        response: v0.ListSubscriptionIdleStatesResponse
    ): Either[String, Seq[Contract[walletCodegen.Subscriptions.SubscriptionIdleState]]] =
      response.idleStates
        .traverse(req => Contract.fromProto(walletCodegen.Subscriptions.SubscriptionIdleState)(req))
        .leftMap(_.toString)
  }

  case class ListSubscriptionInitialPayments()
      extends BaseCommand[
        v0.ListSubscriptionInitialPaymentsRequest,
        v0.ListSubscriptionInitialPaymentsResponse,
        Seq[
          Contract[walletCodegen.Subscriptions.SubscriptionInitialPayment]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListSubscriptionInitialPaymentsRequest] =
      Right(v0.ListSubscriptionInitialPaymentsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListSubscriptionInitialPaymentsRequest,
    ): Future[v0.ListSubscriptionInitialPaymentsResponse] =
      service.listSubscriptionInitialPayments(request)

    override def handleResponse(
        response: v0.ListSubscriptionInitialPaymentsResponse
    ): Either[String, Seq[Contract[walletCodegen.Subscriptions.SubscriptionInitialPayment]]] =
      response.initialPayments
        .traverse(req =>
          Contract.fromProto(walletCodegen.Subscriptions.SubscriptionInitialPayment)(req)
        )
        .leftMap(_.toString)
  }

  case class ListSubscriptionPayments()
      extends BaseCommand[
        v0.ListSubscriptionPaymentsRequest,
        v0.ListSubscriptionPaymentsResponse,
        Seq[
          Contract[walletCodegen.Subscriptions.SubscriptionPayment]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListSubscriptionPaymentsRequest] =
      Right(v0.ListSubscriptionPaymentsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListSubscriptionPaymentsRequest,
    ): Future[v0.ListSubscriptionPaymentsResponse] = service.listSubscriptionPayments(request)

    override def handleResponse(
        response: v0.ListSubscriptionPaymentsResponse
    ): Either[String, Seq[Contract[walletCodegen.Subscriptions.SubscriptionPayment]]] =
      response.payments
        .traverse(req => Contract.fromProto(walletCodegen.Subscriptions.SubscriptionPayment)(req))
        .leftMap(_.toString)
  }

  case class AcceptSubscriptionRequest(
      requestId: Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionRequest]
  ) extends BaseCommand[
        v0.AcceptSubscriptionRequestRequest,
        v0.AcceptSubscriptionRequestResponse,
        Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionInitialPayment],
      ] {

    override def createRequest(): Either[String, v0.AcceptSubscriptionRequestRequest] =
      Right(v0.AcceptSubscriptionRequestRequest(Proto.encode(requestId)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptSubscriptionRequestRequest,
    ): Future[v0.AcceptSubscriptionRequestResponse] = service.acceptSubscriptionRequest(request)

    override def handleResponse(
        response: v0.AcceptSubscriptionRequestResponse
    ): Either[String, Primitive.ContractId[
      walletCodegen.Subscriptions.SubscriptionInitialPayment
    ]] =
      Proto.decodeContractId[walletCodegen.Subscriptions.SubscriptionInitialPayment](
        response.initialPaymentContractId
      )
  }

  case class RejectSubscriptionRequest(
      requestId: Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionRequest]
  ) extends BaseCommand[
        v0.RejectSubscriptionRequestRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectSubscriptionRequestRequest] =
      Right(v0.RejectSubscriptionRequestRequest(Proto.encode(requestId)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectSubscriptionRequestRequest,
    ): Future[Empty] = service.rejectSubscriptionRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class MakeSubscriptionPayment(
      stateId: Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionIdleState]
  ) extends BaseCommand[
        v0.MakeSubscriptionPaymentRequest,
        v0.MakeSubscriptionPaymentResponse,
        Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionPayment],
      ] {

    override def createRequest(): Either[String, v0.MakeSubscriptionPaymentRequest] =
      Right(v0.MakeSubscriptionPaymentRequest(Proto.encode(stateId)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.MakeSubscriptionPaymentRequest,
    ): Future[v0.MakeSubscriptionPaymentResponse] = service.makeSubscriptionPayment(request)

    override def handleResponse(
        response: v0.MakeSubscriptionPaymentResponse
    ): Either[String, Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionPayment]] =
      Proto.decodeContractId[walletCodegen.Subscriptions.SubscriptionPayment](
        response.paymentContractId
      )
  }

  case class CancelSubscription(
      stateId: Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionIdleState]
  ) extends BaseCommand[
        v0.CancelSubscriptionRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.CancelSubscriptionRequest] =
      Right(v0.CancelSubscriptionRequest(Proto.encode(stateId)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.CancelSubscriptionRequest,
    ): Future[Empty] = service.cancelSubscription(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class ProposePaymentChannel(
      receiver: PartyId,
      replacesChannelId: Option[Primitive.ContractId[walletCodegen.PaymentChannel]],
      allowRequests: Boolean,
      allowOffers: Boolean,
      allowDirectTransfers: Boolean,
      senderTransferFeeRatio: BigDecimal,
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

  case class ListPaymentChannels()
      extends BaseCommand[
        v0.ListPaymentChannelsRequest,
        v0.ListPaymentChannelsResponse,
        Seq[Contract[walletCodegen.PaymentChannel]],
      ] {

    override def createRequest(): Either[String, v0.ListPaymentChannelsRequest] =
      Right(
        v0.ListPaymentChannelsRequest()
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
      requestId: Primitive.ContractId[walletCodegen.PaymentChannelProposal]
  ) extends BaseCommand[
        v0.AcceptPaymentChannelProposalRequest,
        v0.AcceptPaymentChannelProposalResponse,
        Primitive.ContractId[walletCodegen.PaymentChannel],
      ] {

    override def createRequest(): Either[String, v0.AcceptPaymentChannelProposalRequest] =
      Right(
        v0.AcceptPaymentChannelProposalRequest(
          proposalContractId = Proto.encode(requestId)
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

  case class ListOnChannelPaymentRequests()
      extends BaseCommand[
        v0.ListOnChannelPaymentRequestsRequest,
        v0.ListOnChannelPaymentRequestsResponse,
        Seq[
          Contract[walletCodegen.OnChannelPaymentRequest]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListOnChannelPaymentRequestsRequest] =
      Right(v0.ListOnChannelPaymentRequestsRequest())

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
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ) extends BaseCommand[
        v0.AcceptOnChannelPaymentRequestRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.AcceptOnChannelPaymentRequestRequest] =
      Right(
        v0.AcceptOnChannelPaymentRequestRequest(
          requestContractId = Proto.encode(requestId)
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
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ) extends BaseCommand[
        v0.RejectOnChannelPaymentRequestRequest,
        Empty,
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
    ): Future[Empty] =
      service.rejectOnChannelPaymentRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class WithdrawOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ) extends BaseCommand[
        v0.WithdrawOnChannelPaymentRequestRequest,
        Empty,
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
    ): Future[Empty] =
      service.withdrawOnChannelPaymentRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class ListAppRewards()
      extends BaseCommand[v0.ListAppRewardsRequest, v0.ListAppRewardsResponse, Seq[
        Contract[coinCodegen.AppReward]
      ]] {

    override def createRequest(): Either[String, v0.ListAppRewardsRequest] =
      Right(v0.ListAppRewardsRequest())

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

  case class ListValidatorRewards()
      extends BaseCommand[v0.ListValidatorRewardsRequest, v0.ListValidatorRewardsResponse, Seq[
        Contract[coinCodegen.ValidatorReward]
      ]] {

    override def createRequest(): Either[String, v0.ListValidatorRewardsRequest] =
      Right(v0.ListValidatorRewardsRequest())

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

  case class CollectRewards(round: Long)
      extends BaseCommand[v0.CollectRewardsRequest, Empty, Unit] {

    override def createRequest(): Either[String, v0.CollectRewardsRequest] =
      Right(v0.CollectRewardsRequest())

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
