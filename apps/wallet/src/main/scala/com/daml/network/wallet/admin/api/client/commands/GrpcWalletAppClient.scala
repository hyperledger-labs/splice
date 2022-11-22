package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.network.codegen.java.cc.{coin => coinCodegen}
import com.daml.network.codegen.java.cn.wallet.{
  payment => walletCodegen,
  paymentchannel => channelCodegen,
  subscriptions => subsCodegen,
}
import com.daml.network.util.{JavaContract => Contract, Proto}
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.wallet.v0.{GetBalanceRequest, GetBalanceResponse}
import com.digitalasset.canton.ProtoDeserializationError
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
      contract: Contract[coinCodegen.Coin.ContractId, coinCodegen.Coin],
      round: Long,
      accruedHoldingFee: BigDecimal,
      effectiveQuantity: BigDecimal,
  )

  final case class LockedCoinPosition(
      contract: Contract[coinCodegen.LockedCoin.ContractId, coinCodegen.LockedCoin],
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
          contract <- Contract
            .fromProto(coinCodegen.Coin.COMPANION)(contractOpt)
            .leftMap(_.toString)

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
          contract <- Contract
            .fromProto(coinCodegen.LockedCoin.COMPANION)(contractOpt)
            .leftMap(_.toString)

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
      extends BaseCommand[v0.TapRequest, v0.TapResponse, coinCodegen.Coin.ContractId] {

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
    ): Either[String, coinCodegen.Coin.ContractId] =
      Proto.decodeJavaContractId(coinCodegen.Coin.COMPANION)(response.contractId)
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

  case class ListAppPaymentRequests()
      extends BaseCommand[
        v0.ListAppPaymentRequestsRequest,
        v0.ListAppPaymentRequestsResponse,
        Seq[
          Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListAppPaymentRequestsRequest] = Right(
      v0.ListAppPaymentRequestsRequest()
    )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAppPaymentRequestsRequest,
    ): Future[v0.ListAppPaymentRequestsResponse] = service.listAppPaymentRequests(request)

    override def handleResponse(
        response: v0.ListAppPaymentRequestsResponse
    ): Either[String, Seq[
      Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
    ]] =
      response.paymentRequests
        .traverse(req => Contract.fromProto(walletCodegen.AppPaymentRequest.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class AcceptAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ) extends BaseCommand[
        v0.AcceptAppPaymentRequestRequest,
        v0.AcceptAppPaymentRequestResponse,
        walletCodegen.AcceptedAppPayment.ContractId,
      ] {

    override def createRequest(): Either[String, v0.AcceptAppPaymentRequestRequest] =
      Right(
        v0.AcceptAppPaymentRequestRequest(
          requestContractId = Proto.encodeContractId(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptAppPaymentRequestRequest,
    ): Future[v0.AcceptAppPaymentRequestResponse] =
      service.acceptAppPaymentRequest(request)

    override def handleResponse(
        response: v0.AcceptAppPaymentRequestResponse
    ): Either[String, walletCodegen.AcceptedAppPayment.ContractId] =
      Proto.decodeJavaContractId(walletCodegen.AcceptedAppPayment.COMPANION)(
        response.acceptedPaymentContractId
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

  case class RejectAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ) extends BaseCommand[
        v0.RejectAppPaymentRequestRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectAppPaymentRequestRequest] =
      Right(
        v0.RejectAppPaymentRequestRequest(
          requestContractId = Proto.encodeContractId(requestId)
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

  case class ListAcceptedAppPayments()
      extends BaseCommand[
        v0.ListAcceptedAppPaymentsRequest,
        v0.ListAcceptedAppPaymentsResponse,
        Seq[
          Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListAcceptedAppPaymentsRequest] =
      Right(v0.ListAcceptedAppPaymentsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAcceptedAppPaymentsRequest,
    ): Future[v0.ListAcceptedAppPaymentsResponse] =
      service.listAcceptedAppPayments(request)

    override def handleResponse(
        response: v0.ListAcceptedAppPaymentsResponse
    ): Either[String, Seq[
      Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment]
    ]] =
      response.acceptedAppPayments
        .traverse(req => Contract.fromProto(walletCodegen.AcceptedAppPayment.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class ListSubscriptionRequests()
      extends BaseCommand[
        v0.ListSubscriptionRequestsRequest,
        v0.ListSubscriptionRequestsResponse,
        Seq[
          Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]
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
    ): Either[String, Seq[
      Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]
    ]] =
      response.subscriptionRequests
        .traverse(req => Contract.fromProto(subsCodegen.SubscriptionRequest.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class ListSubscriptionInitialPayments()
      extends BaseCommand[
        v0.ListSubscriptionInitialPaymentsRequest,
        v0.ListSubscriptionInitialPaymentsResponse,
        Seq[
          Contract[
            subsCodegen.SubscriptionInitialPayment.ContractId,
            subsCodegen.SubscriptionInitialPayment,
          ]
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
    ): Either[String, Seq[Contract[
      subsCodegen.SubscriptionInitialPayment.ContractId,
      subsCodegen.SubscriptionInitialPayment,
    ]]] =
      response.initialPayments
        .traverse(req => Contract.fromProto(subsCodegen.SubscriptionInitialPayment.COMPANION)(req))
        .leftMap(_.toString)
  }

  final case class Subscription(
      main: Contract[subsCodegen.Subscription.ContractId, subsCodegen.Subscription],
      state: SubscriptionState,
  )
  sealed trait SubscriptionState extends Product with Serializable;
  final case class SubscriptionIdleState(
      contract: Contract[
        subsCodegen.SubscriptionIdleState.ContractId,
        subsCodegen.SubscriptionIdleState,
      ]
  ) extends SubscriptionState;
  final case class SubscriptionPayment(
      contract: Contract[
        subsCodegen.SubscriptionPayment.ContractId,
        subsCodegen.SubscriptionPayment,
      ]
  ) extends SubscriptionState;

  case class ListSubscriptions()
      extends BaseCommand[
        v0.ListSubscriptionsRequest,
        v0.ListSubscriptionsResponse,
        Seq[Subscription],
      ] {

    override def createRequest(): Either[String, v0.ListSubscriptionsRequest] =
      Right(v0.ListSubscriptionsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListSubscriptionsRequest,
    ): Future[v0.ListSubscriptionsResponse] = service.listSubscriptions(request)

    override def handleResponse(
        response: v0.ListSubscriptionsResponse
    ): Either[String, Seq[Subscription]] =
      response.subscriptions
        .traverse(sub =>
          for {
            main <- sub.main
              .toRight("Could not find main subscription contract")
              .flatMap(
                Contract.fromProto(subsCodegen.Subscription.COMPANION)(_).leftMap(_.toString)
              )
            state <- (sub.state match {
              case v0.Subscription.State.Empty =>
                Left(ProtoDeserializationError.FieldNotSet("Subscription.state"))
              case v0.Subscription.State.Idle(state) =>
                Contract
                  .fromProto(subsCodegen.SubscriptionIdleState.COMPANION)(state)
                  .map(SubscriptionIdleState)
              case v0.Subscription.State.Payment(state) =>
                Contract
                  .fromProto(subsCodegen.SubscriptionPayment.COMPANION)(state)
                  .map(SubscriptionPayment)
              case other =>
                Left(
                  ProtoDeserializationError.UnrecognizedField(
                    s"Subscription.state with value $other"
                  )
                )
            }).leftMap(_.toString)
          } yield Subscription(main, state)
        )
  }

  case class AcceptSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ) extends BaseCommand[
        v0.AcceptSubscriptionRequestRequest,
        v0.AcceptSubscriptionRequestResponse,
        subsCodegen.SubscriptionInitialPayment.ContractId,
      ] {

    override def createRequest(): Either[String, v0.AcceptSubscriptionRequestRequest] =
      Right(v0.AcceptSubscriptionRequestRequest(Proto.encodeContractId(requestId)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptSubscriptionRequestRequest,
    ): Future[v0.AcceptSubscriptionRequestResponse] = service.acceptSubscriptionRequest(request)

    override def handleResponse(
        response: v0.AcceptSubscriptionRequestResponse
    ): Either[String, subsCodegen.SubscriptionInitialPayment.ContractId] =
      Proto.decodeJavaContractId(subsCodegen.SubscriptionInitialPayment.COMPANION)(
        response.initialPaymentContractId
      )
  }

  case class RejectSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ) extends BaseCommand[
        v0.RejectSubscriptionRequestRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectSubscriptionRequestRequest] =
      Right(v0.RejectSubscriptionRequestRequest(Proto.encodeContractId(requestId)))

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectSubscriptionRequestRequest,
    ): Future[Empty] = service.rejectSubscriptionRequest(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] =
      Right(())
  }

  case class CancelSubscription(
      stateId: subsCodegen.SubscriptionIdleState.ContractId
  ) extends BaseCommand[
        v0.CancelSubscriptionRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.CancelSubscriptionRequest] =
      Right(v0.CancelSubscriptionRequest(Proto.encodeContractId(stateId)))

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
      replacesChannelId: Option[channelCodegen.PaymentChannel.ContractId],
      allowRequests: Boolean,
      allowOffers: Boolean,
      allowDirectTransfers: Boolean,
      senderTransferFeeRatio: BigDecimal,
  ) extends BaseCommand[
        v0.ProposePaymentChannelRequest,
        v0.ProposePaymentChannelResponse,
        channelCodegen.PaymentChannelProposal.ContractId,
      ] {

    override def createRequest(): Either[String, v0.ProposePaymentChannelRequest] =
      Right(
        v0.ProposePaymentChannelRequest(
          Proto.encode(receiver),
          replacesChannelId = replacesChannelId.map(Proto.encodeContractId(_)),
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
    ): Either[String, channelCodegen.PaymentChannelProposal.ContractId] =
      Proto.decodeJavaContractId(channelCodegen.PaymentChannelProposal.COMPANION)(
        response.proposalContractId
      )
  }

  case class ListPaymentChannelProposals()
      extends BaseCommand[
        v0.ListPaymentChannelProposalsRequest,
        v0.ListPaymentChannelProposalsResponse,
        Seq[Contract[
          channelCodegen.PaymentChannelProposal.ContractId,
          channelCodegen.PaymentChannelProposal,
        ]],
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
    ): Either[String, Seq[Contract[
      channelCodegen.PaymentChannelProposal.ContractId,
      channelCodegen.PaymentChannelProposal,
    ]]] =
      response.proposals
        .traverse(req => Contract.fromProto(channelCodegen.PaymentChannelProposal.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class ListPaymentChannels()
      extends BaseCommand[
        v0.ListPaymentChannelsRequest,
        v0.ListPaymentChannelsResponse,
        Seq[Contract[channelCodegen.PaymentChannel.ContractId, channelCodegen.PaymentChannel]],
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
    ): Either[String, Seq[
      Contract[channelCodegen.PaymentChannel.ContractId, channelCodegen.PaymentChannel]
    ]] =
      response.channels
        .traverse(req => Contract.fromProto(channelCodegen.PaymentChannel.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class AcceptPaymentChannelProposal(
      requestId: channelCodegen.PaymentChannelProposal.ContractId
  ) extends BaseCommand[
        v0.AcceptPaymentChannelProposalRequest,
        v0.AcceptPaymentChannelProposalResponse,
        channelCodegen.PaymentChannel.ContractId,
      ] {

    override def createRequest(): Either[String, v0.AcceptPaymentChannelProposalRequest] =
      Right(
        v0.AcceptPaymentChannelProposalRequest(
          proposalContractId = Proto.encodeContractId(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptPaymentChannelProposalRequest,
    ): Future[v0.AcceptPaymentChannelProposalResponse] =
      service.acceptPaymentChannelProposal(request)

    override def handleResponse(
        response: v0.AcceptPaymentChannelProposalResponse
    ): Either[String, channelCodegen.PaymentChannel.ContractId] =
      Proto.decodeJavaContractId(channelCodegen.PaymentChannel.COMPANION)(
        response.channelContractId
      )
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
        channelCodegen.OnChannelPaymentRequest.ContractId,
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
    ): Either[String, channelCodegen.OnChannelPaymentRequest.ContractId] =
      Proto.decodeJavaContractId(channelCodegen.OnChannelPaymentRequest.COMPANION)(
        response.requestContractId
      )
  }

  case class ListOnChannelPaymentRequests()
      extends BaseCommand[
        v0.ListOnChannelPaymentRequestsRequest,
        v0.ListOnChannelPaymentRequestsResponse,
        Seq[
          Contract[
            channelCodegen.OnChannelPaymentRequest.ContractId,
            channelCodegen.OnChannelPaymentRequest,
          ]
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
    ): Either[String, Seq[Contract[
      channelCodegen.OnChannelPaymentRequest.ContractId,
      channelCodegen.OnChannelPaymentRequest,
    ]]] =
      response.paymentRequests
        .traverse(req => Contract.fromProto(channelCodegen.OnChannelPaymentRequest.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class AcceptOnChannelPaymentRequest(
      requestId: channelCodegen.OnChannelPaymentRequest.ContractId
  ) extends BaseCommand[
        v0.AcceptOnChannelPaymentRequestRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.AcceptOnChannelPaymentRequestRequest] =
      Right(
        v0.AcceptOnChannelPaymentRequestRequest(
          requestContractId = Proto.encodeContractId(requestId)
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
      requestId: channelCodegen.OnChannelPaymentRequest.ContractId
  ) extends BaseCommand[
        v0.RejectOnChannelPaymentRequestRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.RejectOnChannelPaymentRequestRequest] =
      Right(
        v0.RejectOnChannelPaymentRequestRequest(
          requestContractId = Proto.encodeContractId(requestId)
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
      requestId: channelCodegen.OnChannelPaymentRequest.ContractId
  ) extends BaseCommand[
        v0.WithdrawOnChannelPaymentRequestRequest,
        Empty,
        Unit,
      ] {
    override def createRequest(): Either[String, v0.WithdrawOnChannelPaymentRequestRequest] =
      Right(
        v0.WithdrawOnChannelPaymentRequestRequest(
          requestContractId = Proto.encodeContractId(requestId)
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
        Contract[coinCodegen.AppReward.ContractId, coinCodegen.AppReward]
      ]] {

    override def createRequest(): Either[String, v0.ListAppRewardsRequest] =
      Right(v0.ListAppRewardsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAppRewardsRequest,
    ): Future[v0.ListAppRewardsResponse] = service.listAppRewards(request)

    override def handleResponse(
        response: v0.ListAppRewardsResponse
    ): Either[String, Seq[Contract[coinCodegen.AppReward.ContractId, coinCodegen.AppReward]]] =
      response.appRewards
        .traverse(req => Contract.fromProto(coinCodegen.AppReward.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class ListValidatorRewards()
      extends BaseCommand[v0.ListValidatorRewardsRequest, v0.ListValidatorRewardsResponse, Seq[
        Contract[coinCodegen.ValidatorReward.ContractId, coinCodegen.ValidatorReward]
      ]] {

    override def createRequest(): Either[String, v0.ListValidatorRewardsRequest] =
      Right(v0.ListValidatorRewardsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListValidatorRewardsRequest,
    ): Future[v0.ListValidatorRewardsResponse] = service.listValidatorRewards(request)

    override def handleResponse(
        response: v0.ListValidatorRewardsResponse
    ): Either[String, Seq[
      Contract[coinCodegen.ValidatorReward.ContractId, coinCodegen.ValidatorReward]
    ]] =
      response.validatorRewards
        .traverse(req => Contract.fromProto(coinCodegen.ValidatorReward.COMPANION)(req))
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

  case class UserStatusData(
      party: String,
      userOnboarded: Boolean,
  )

  case class UserStatus()
      extends BaseCommand[v0.UserStatusRequest, v0.UserStatusResponse, UserStatusData] {

    override def createRequest(): Either[String, v0.UserStatusRequest] =
      Right(v0.UserStatusRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.UserStatusRequest,
    ): Future[v0.UserStatusResponse] = service.userStatus(request)

    override def handleResponse(
        response: v0.UserStatusResponse
    ): Either[String, UserStatusData] = Right(
      UserStatusData(response.partyId, response.userOnboarded)
    )
  }
}
