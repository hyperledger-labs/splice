package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.transferoffer.TransferOffer
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOfferCodegen,
}
import com.daml.network.util.{JavaContract => Contract, Proto}
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.wallet.v0.{
  CreateTransferOfferRequest,
  CreateTransferOfferResponse,
  GetBalanceRequest,
  GetBalanceResponse,
  SelfGrantFeaturedAppRightResponse,
}
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.{DomainAlias, ProtoDeserializationError}
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

  case class SelfGrantFeaturedAppRight()
      extends BaseCommand[
        Empty,
        v0.SelfGrantFeaturedAppRightResponse,
        coinCodegen.FeaturedAppRight.ContractId,
      ] {

    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(
        service: WalletServiceStub,
        request: Empty,
    ): Future[SelfGrantFeaturedAppRightResponse] = service.selfGrantFeaturedAppRight(request)

    override def handleResponse(
        response: SelfGrantFeaturedAppRightResponse
    ): Either[String, FeaturedAppRight.ContractId] =
      Proto.decodeJavaContractId(coinCodegen.FeaturedAppRight.COMPANION)(response.contractId)
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

  case class CreateTransferOffer(
      receiver: PartyId,
      quantity: BigDecimal,
      description: String,
      expiresAt: CantonTimestamp,
      senderFeeTransferRatio: BigDecimal,
      idempotencyKey: String,
  ) extends BaseCommand[
        v0.CreateTransferOfferRequest,
        v0.CreateTransferOfferResponse,
        transferOfferCodegen.TransferOffer.ContractId,
      ] {
    override def submitRequest(
        service: WalletServiceStub,
        request: CreateTransferOfferRequest,
    ): Future[CreateTransferOfferResponse] = service.createTransferOffer(request)

    override def createRequest(): Either[String, CreateTransferOfferRequest] = {
      Right(
        v0.CreateTransferOfferRequest(
          Proto.encode(receiver),
          Proto.encode(quantity),
          description,
          Proto.encode(expiresAt),
          Proto.encode(senderFeeTransferRatio),
          idempotencyKey,
        )
      )
    }

    override def handleResponse(
        response: CreateTransferOfferResponse
    ): Either[String, TransferOffer.ContractId] =
      Proto.decodeJavaContractId(transferOfferCodegen.TransferOffer.COMPANION)(
        response.offerContractId
      )
  }

  case class ListTransferOffers()
      extends BaseCommand[
        Empty,
        v0.ListTransferOffersResponse,
        Seq[Contract[
          transferOfferCodegen.TransferOffer.ContractId,
          transferOfferCodegen.TransferOffer,
        ]],
      ] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: WalletServiceStub,
        request: Empty,
    ): Future[v0.ListTransferOffersResponse] = service.listTransferOffers(request)

    override def handleResponse(
        response: v0.ListTransferOffersResponse
    ): Either[String, Seq[Contract[
      transferOfferCodegen.TransferOffer.ContractId,
      transferOfferCodegen.TransferOffer,
    ]]] =
      response.offers
        .traverse(req => Contract.fromProto(transferOfferCodegen.TransferOffer.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class AcceptTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends BaseCommand[
        v0.AcceptTransferOfferRequest,
        v0.AcceptTransferOfferResponse,
        transferOfferCodegen.AcceptedTransferOffer.ContractId,
      ] {

    override def createRequest(): Either[String, v0.AcceptTransferOfferRequest] =
      Right(
        v0.AcceptTransferOfferRequest(
          offerContractId = Proto.encodeContractId(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.AcceptTransferOfferRequest,
    ): Future[v0.AcceptTransferOfferResponse] =
      service.acceptTransferOffer(request)

    override def handleResponse(
        response: v0.AcceptTransferOfferResponse
    ): Either[String, transferOfferCodegen.AcceptedTransferOffer.ContractId] =
      Proto.decodeJavaContractId(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(
        response.acceptedOfferContractId
      )
  }

  case class ListAcceptedTransferOffers()
      extends BaseCommand[
        Empty,
        v0.ListAcceptedTransferOffersResponse,
        Seq[Contract[
          transferOfferCodegen.AcceptedTransferOffer.ContractId,
          transferOfferCodegen.AcceptedTransferOffer,
        ]],
      ] {

    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: WalletServiceStub,
        request: Empty,
    ): Future[v0.ListAcceptedTransferOffersResponse] = service.listAcceptedTransferOffers(request)

    override def handleResponse(
        response: v0.ListAcceptedTransferOffersResponse
    ): Either[String, Seq[Contract[
      transferOfferCodegen.AcceptedTransferOffer.ContractId,
      transferOfferCodegen.AcceptedTransferOffer,
    ]]] =
      response.acceptedOffers
        .traverse(req =>
          Contract.fromProto(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(req)
        )
        .leftMap(_.toString)
  }

  case class RejectTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends BaseCommand[
        v0.RejectTransferOfferRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.RejectTransferOfferRequest] =
      Right(
        v0.RejectTransferOfferRequest(
          offerContractId = Proto.encodeContractId(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.RejectTransferOfferRequest,
    ): Future[Empty] =
      service.rejectTransferOffer(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
  }

  case class WithdrawTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends BaseCommand[
        v0.WithdrawTransferOfferRequest,
        Empty,
        Unit,
      ] {

    override def createRequest(): Either[String, v0.WithdrawTransferOfferRequest] =
      Right(
        v0.WithdrawTransferOfferRequest(
          offerContractId = Proto.encodeContractId(requestId)
        )
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.WithdrawTransferOfferRequest,
    ): Future[Empty] =
      service.withdrawTransferOffer(request)

    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
  }

  case class ListAppRewardCoupons()
      extends BaseCommand[v0.ListAppRewardCouponsRequest, v0.ListAppRewardCouponsResponse, Seq[
        Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]
      ]] {

    override def createRequest(): Either[String, v0.ListAppRewardCouponsRequest] =
      Right(v0.ListAppRewardCouponsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListAppRewardCouponsRequest,
    ): Future[v0.ListAppRewardCouponsResponse] = service.listAppRewardCoupons(request)

    override def handleResponse(
        response: v0.ListAppRewardCouponsResponse
    ): Either[String, Seq[
      Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]
    ]] =
      response.appRewardCoupons
        .traverse(req => Contract.fromProto(coinCodegen.AppRewardCoupon.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class ListValidatorRewardCoupons()
      extends BaseCommand[
        v0.ListValidatorRewardCouponsRequest,
        v0.ListValidatorRewardCouponsResponse,
        Seq[
          Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
        ],
      ] {

    override def createRequest(): Either[String, v0.ListValidatorRewardCouponsRequest] =
      Right(v0.ListValidatorRewardCouponsRequest())

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListValidatorRewardCouponsRequest,
    ): Future[v0.ListValidatorRewardCouponsResponse] = service.listValidatorRewardCoupons(request)

    override def handleResponse(
        response: v0.ListValidatorRewardCouponsResponse
    ): Either[String, Seq[
      Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
    ]] =
      response.validatorRewardCoupons
        .traverse(req => Contract.fromProto(coinCodegen.ValidatorRewardCoupon.COMPANION)(req))
        .leftMap(_.toString)
  }

  case class UserStatusData(
      party: String,
      userOnboarded: Boolean,
      hasFeaturedAppRight: Boolean,
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
      UserStatusData(response.partyId, response.userOnboarded, response.hasFeaturedAppRight)
    )
  }

  case class ListConnectedDomains(
  ) extends BaseCommand[Empty, v0.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: WalletServiceStub,
        request: Empty,
    ): Future[v0.ListConnectedDomainsResponse] = service.listConnectedDomains(request)

    override def handleResponse(
        response: v0.ListConnectedDomainsResponse
    ): Either[String, Map[DomainAlias, DomainId]] =
      Proto.decode(Proto.ConnectedDomains)(response.getDomains)
  }

  case class CancelFeaturedAppRight() extends BaseCommand[Empty, Empty, Unit] {

    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(service: WalletServiceStub, request: Empty): Future[Empty] =
      service.cancelFeaturedAppRights(request)

    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }
}
