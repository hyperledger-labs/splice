package com.daml.network.wallet.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOfferCodegen,
}
import com.daml.network.http.v0.{definitions, wallet as http}
import com.daml.network.http.v0.wallet.{
  GetAppPaymentRequestResponse,
  GetSubscriptionRequestResponse,
}
import com.daml.network.util.{Codec, Contract, TemplateJsonDecoder}
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpWalletAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.WalletClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.WalletClient.httpClient(HttpClientBuilder().buildClient, host)
  }

  final case class CoinPosition(
      contract: Contract[coinCodegen.Coin.ContractId, coinCodegen.Coin],
      round: Long,
      accruedHoldingFee: BigDecimal,
      effectiveAmount: BigDecimal,
  )

  final case class LockedCoinPosition(
      contract: Contract[coinCodegen.LockedCoin.ContractId, coinCodegen.LockedCoin],
      round: Long,
      accruedHoldingFee: BigDecimal,
      effectiveAmount: BigDecimal,
  )

  final case class ListResponse(
      coins: Seq[CoinPosition],
      lockedCoins: Seq[LockedCoinPosition],
  )

  final case class Balance(
      round: Long,
      unlockedQty: BigDecimal,
      lockedQty: BigDecimal,
      holdingFees: BigDecimal,
  )

  final case class Subscription(
      subscription: Contract[subsCodegen.Subscription.ContractId, subsCodegen.Subscription],
      state: SubscriptionState,
      context: Contract[
        subsCodegen.SubscriptionContext.ContractId,
        subsCodegen.SubscriptionContextView,
      ],
  )

  final case class SubscriptionRequest(
      subscriptionRequest: Contract[
        subsCodegen.SubscriptionRequest.ContractId,
        subsCodegen.SubscriptionRequest,
      ],
      context: Contract[
        subsCodegen.SubscriptionContext.ContractId,
        subsCodegen.SubscriptionContextView,
      ],
  )

  object SubscriptionRequest {
    def parseHttp(
        subscriptionRequest: definitions.SubscriptionRequest
    )(implicit decoder: TemplateJsonDecoder): Either[String, SubscriptionRequest] = {
      (for {
        main <- Contract.fromJson(subsCodegen.SubscriptionRequest.COMPANION)(
          subscriptionRequest.subscriptionRequest
        )
        context <- Contract.fromJson(subsCodegen.SubscriptionContext.INTERFACE)(
          subscriptionRequest.context
        )
      } yield SubscriptionRequest(main, context)).leftMap(_.toString)
    }
  }

  final case class AppPaymentRequest(
      appPaymentRequest: Contract[
        walletCodegen.AppPaymentRequest.ContractId,
        walletCodegen.AppPaymentRequest,
      ],
      deliveryOffer: Contract[
        walletCodegen.DeliveryOffer.ContractId,
        walletCodegen.DeliveryOfferView,
      ],
  )

  object AppPaymentRequest {
    def parseHttp(
        response: definitions.AppPaymentRequest
    )(implicit decoder: TemplateJsonDecoder): Either[String, AppPaymentRequest] = {
      (for {
        appPaymentRequest <- Contract
          .fromJson(walletCodegen.AppPaymentRequest.COMPANION)(
            response.appPaymentRequest
          )
        deliveryOffer <- Contract.fromJson(walletCodegen.DeliveryOffer.INTERFACE)(
          response.deliveryOffer
        )
      } yield AppPaymentRequest(appPaymentRequest, deliveryOffer)).leftMap(_.toString)
    }
  }

  final case class SubscriptionContext(
      description: String
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

  final case class UserStatusData(
      party: String,
      userOnboarded: Boolean,
      userWalletInstalled: Boolean,
      hasFeaturedAppRight: Boolean,
  )
  sealed trait SubscriptionStateContract
  final case class SubscriptionStateIdleContract(contract: definitions.Contract)
      extends SubscriptionStateContract
  final case class SubscriptionStatePaymentContract(contract: definitions.Contract)
      extends SubscriptionStateContract

  object SubscriptionStateContract {
    def unapply(state: definitions.SubscriptionState): Option[SubscriptionStateContract] = {
      state match {
        case definitions.SubscriptionState(Some(state), None) =>
          Some(SubscriptionStateIdleContract(state))
        case definitions.SubscriptionState(None, Some(state)) =>
          Some(SubscriptionStatePaymentContract(state))
        case _ => None
      }
    }
  }

  case object ListPositions
      extends BaseCommand[
        http.ListResponse,
        ListResponse,
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListResponse] =
      client.list(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListResponse.OK(response) =>
      def decodePositions(position: definitions.CoinPosition) =
        for {
          contract <- Contract
            .fromJson(coinCodegen.Coin.COMPANION)(position.contract)
            .leftMap(_.toString)

          accruedHoldingFee <- Codec.decode(Codec.BigDecimal)(position.accruedHoldingFee)
          effectiveAmount <- Codec.decode(Codec.BigDecimal)(position.effectiveAmount)
        } yield {
          new CoinPosition(
            contract,
            position.round,
            accruedHoldingFee,
            effectiveAmount,
          )
        }

      def decodeLockedPositions(lockedPosition: definitions.CoinPosition) =
        for {
          contract <- Contract
            .fromJson(coinCodegen.LockedCoin.COMPANION)(lockedPosition.contract)
            .leftMap(_.toString)

          accruedHoldingFee <- Codec.decode(Codec.BigDecimal)(lockedPosition.accruedHoldingFee)
          effectiveAmount <- Codec.decode(Codec.BigDecimal)(lockedPosition.effectiveAmount)
        } yield {
          LockedCoinPosition(
            contract,
            lockedPosition.round,
            accruedHoldingFee,
            effectiveAmount,
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

  case class Tap(amount: BigDecimal)
      extends BaseCommand[http.TapResponse, coinCodegen.Coin.ContractId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.TapResponse] =
      client.tap(definitions.TapRequest(Codec.encode(amount)), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.TapResponse.OK(response) =>
      Codec.decodeJavaContractId(coinCodegen.Coin.COMPANION)(response.contractId)
    }
  }

  case object SelfGrantFeaturedAppRight
      extends BaseCommand[
        http.SelfGrantFeatureAppRightResponse,
        coinCodegen.FeaturedAppRight.ContractId,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.SelfGrantFeatureAppRightResponse] =
      client.selfGrantFeatureAppRight(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.SelfGrantFeatureAppRightResponse.OK(response) =>
      Codec.decodeJavaContractId(coinCodegen.FeaturedAppRight.COMPANION)(response.contractId)
    }
  }

  case object GetBalance
      extends BaseCommand[
        http.GetBalanceResponse,
        Balance,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetBalanceResponse] =
      client.getBalance(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetBalanceResponse.OK(response) =>
      for {
        effectiveUnlockedQty <- Codec.decode(Codec.BigDecimal)(response.effectiveUnlockedQty)
        effectiveLockedQty <- Codec.decode(Codec.BigDecimal)(response.effectiveLockedQty)
        totalHoldingFees <- Codec.decode(Codec.BigDecimal)(response.totalHoldingFees)
      } yield {
        Balance(
          response.round,
          effectiveUnlockedQty,
          effectiveLockedQty,
          totalHoldingFees,
        )
      }
    }
  }

  case object ListAppPaymentRequests
      extends BaseCommand[
        http.ListAppPaymentRequestsResponse,
        Seq[AppPaymentRequest],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAppPaymentRequestsResponse] =
      client.listAppPaymentRequests(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListAppPaymentRequestsResponse.OK(response) =>
      response.paymentRequests
        .traverse(req => AppPaymentRequest.parseHttp(req))
    }
  }

  case class GetAppPaymentRequest(
      contractId: walletCodegen.AppPaymentRequest.ContractId
  ) extends BaseCommand[http.GetAppPaymentRequestResponse, AppPaymentRequest] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAppPaymentRequestResponse] =
      client.getAppPaymentRequest(Codec.encodeContractId(contractId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case GetAppPaymentRequestResponse.OK(response) =>
      AppPaymentRequest.parseHttp(response)
    }
  }

  case class AcceptAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ) extends BaseCommand[
        http.AcceptAppPaymentRequestResponse,
        walletCodegen.AcceptedAppPayment.ContractId,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.AcceptAppPaymentRequestResponse] =
      client.acceptAppPaymentRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.AcceptAppPaymentRequestResponse.OK(response) =>
      Codec.decodeJavaContractId(walletCodegen.AcceptedAppPayment.COMPANION)(
        response.acceptedPaymentContractId
      )
    }
  }

  case class RejectAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ) extends BaseCommand[
        http.RejectAppPaymentRequestResponse,
        Unit,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.RejectAppPaymentRequestResponse] =
      client.rejectAppPaymentRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.RejectAppPaymentRequestResponse.OK =>
      Right(())
    }
  }

  case object ListAcceptedAppPayments
      extends BaseCommand[
        http.ListAcceptedAppPaymentsResponse,
        Seq[
          Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAcceptedAppPaymentsResponse] =
      client.listAcceptedAppPayments(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListAcceptedAppPaymentsResponse.OK(response) =>
      response.acceptedAppPayments
        .traverse(req => Contract.fromJson(walletCodegen.AcceptedAppPayment.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object ListSubscriptionRequests
      extends BaseCommand[
        http.ListSubscriptionRequestsResponse,
        Seq[SubscriptionRequest],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListSubscriptionRequestsResponse] =
      client.listSubscriptionRequests(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListSubscriptionRequestsResponse.OK(response) =>
      response.subscriptionRequests.traverse(req => SubscriptionRequest.parseHttp(req))
    }
  }

  case object ListSubscriptionInitialPayments
      extends BaseCommand[
        http.ListSubscriptionInitialPaymentsResponse,
        Seq[
          Contract[
            subsCodegen.SubscriptionInitialPayment.ContractId,
            subsCodegen.SubscriptionInitialPayment,
          ]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListSubscriptionInitialPaymentsResponse] =
      client.listSubscriptionInitialPayments(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListSubscriptionInitialPaymentsResponse.OK(response) =>
      response.initialPayments
        .traverse(req => Contract.fromJson(subsCodegen.SubscriptionInitialPayment.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object ListSubscriptions
      extends BaseCommand[
        http.ListSubscriptionsResponse,
        Seq[Subscription],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListSubscriptionsResponse] =
      client.listSubscriptions(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListSubscriptionsResponse.OK(response) =>
      response.subscriptions
        .traverse(sub =>
          for {
            main <- Contract
              .fromJson(subsCodegen.Subscription.COMPANION)(sub.subscription)
              .leftMap(_.toString)
            context <- Contract
              .fromJson(subsCodegen.SubscriptionContext.INTERFACE)(sub.context)
              .leftMap(_.toString)
            state <- (sub.state match {
              case SubscriptionStateContract(SubscriptionStateIdleContract(contract)) =>
                Contract
                  .fromJson(subsCodegen.SubscriptionIdleState.COMPANION)(contract)
                  .map(SubscriptionIdleState)
              case SubscriptionStateContract(SubscriptionStatePaymentContract(contract)) =>
                Contract
                  .fromJson(subsCodegen.SubscriptionPayment.COMPANION)(contract)
                  .map(SubscriptionPayment)
              case other =>
                Left(
                  ProtoDeserializationError.UnrecognizedField(
                    s"Subscription.state with value $other"
                  )
                )
            }).leftMap(_.toString)
          } yield Subscription(main, state, context)
        )
    }
  }

  case class GetSubscriptionRequest(
      contractId: subsCodegen.SubscriptionRequest.ContractId
  ) extends BaseCommand[
        http.GetSubscriptionRequestResponse,
        SubscriptionRequest,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSubscriptionRequestResponse] =
      client.getSubscriptionRequest(Codec.encodeContractId(contractId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case GetSubscriptionRequestResponse.OK(subscriptionRequest) =>
      SubscriptionRequest.parseHttp(subscriptionRequest)
    }
  }

  case class AcceptSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ) extends BaseCommand[
        http.AcceptSubscriptionRequestResponse,
        subsCodegen.SubscriptionInitialPayment.ContractId,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.AcceptSubscriptionRequestResponse] =
      client.acceptSubscriptionRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.AcceptSubscriptionRequestResponse.OK(response) =>
      Codec.decodeJavaContractId(subsCodegen.SubscriptionInitialPayment.COMPANION)(
        response.initialPaymentContractId
      )
    }
  }

  case class RejectSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ) extends BaseCommand[
        http.RejectSubscriptionRequestResponse,
        Unit,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.RejectSubscriptionRequestResponse] =
      client.rejectSubscriptionRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.RejectSubscriptionRequestResponse.OK =>
      Right(())
    }
  }

  case class CancelSubscription(
      stateId: subsCodegen.SubscriptionIdleState.ContractId
  ) extends BaseCommand[
        http.CancelSubscriptionRequestResponse,
        Unit,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CancelSubscriptionRequestResponse] =
      client.cancelSubscriptionRequest(Codec.encodeContractId(stateId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CancelSubscriptionRequestResponse.OK =>
      Right(())
    }
  }

  case class CreateTransferOffer(
      receiver: PartyId,
      amount: BigDecimal,
      description: String,
      expiresAt: CantonTimestamp,
      idempotencyKey: String,
  ) extends BaseCommand[
        http.CreateTransferOfferResponse,
        transferOfferCodegen.TransferOffer.ContractId,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CreateTransferOfferResponse] = {
      val request = definitions.CreateTransferOfferRequest(
        Codec.encode(receiver),
        Codec.encode(amount),
        description,
        Codec.encode(expiresAt),
        idempotencyKey,
      )

      client.createTransferOffer(request, headers = headers)
    }

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CreateTransferOfferResponse.OK(response) =>
      Codec.decodeJavaContractId(transferOfferCodegen.TransferOffer.COMPANION)(
        response.offerContractId
      )
    }
  }

  case object ListTransferOffers
      extends BaseCommand[
        http.ListTransferOffersResponse,
        Seq[Contract[
          transferOfferCodegen.TransferOffer.ContractId,
          transferOfferCodegen.TransferOffer,
        ]],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListTransferOffersResponse] =
      client.listTransferOffers(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListTransferOffersResponse.OK(response) =>
      response.offers
        .traverse(req => Contract.fromJson(transferOfferCodegen.TransferOffer.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class AcceptTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends BaseCommand[
        http.AcceptTransferOfferResponse,
        transferOfferCodegen.AcceptedTransferOffer.ContractId,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.AcceptTransferOfferResponse] =
      client.acceptTransferOffer(Codec.encodeContractId(requestId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.AcceptTransferOfferResponse.OK(response) =>
      Codec.decodeJavaContractId(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(
        response.acceptedOfferContractId
      )
    }
  }

  case object ListAcceptedTransferOffers
      extends BaseCommand[
        http.ListAcceptedTransferOffersResponse,
        Seq[Contract[
          transferOfferCodegen.AcceptedTransferOffer.ContractId,
          transferOfferCodegen.AcceptedTransferOffer,
        ]],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAcceptedTransferOffersResponse] =
      client.listAcceptedTransferOffers(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListAcceptedTransferOffersResponse.OK(response) =>
      response.acceptedOffers
        .traverse(req =>
          Contract.fromJson(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(req)
        )
        .leftMap(_.toString)
    }
  }

  case class RejectTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends BaseCommand[
        http.RejectTransferOfferResponse,
        Unit,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.RejectTransferOfferResponse] =
      client.rejectTransferOffer(Codec.encodeContractId(requestId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.RejectTransferOfferResponse.OK =>
      Right(())
    }
  }

  case class WithdrawTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends BaseCommand[
        http.WithdrawTransferOfferResponse,
        Unit,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.WithdrawTransferOfferResponse] =
      client.withdrawTransferOffer(Codec.encodeContractId(requestId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.WithdrawTransferOfferResponse.OK =>
      Right(())
    }
  }

  case object ListAppRewardCoupons
      extends BaseCommand[
        http.ListAppRewardCouponsResponse,
        Seq[
          Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAppRewardCouponsResponse] =
      client.listAppRewardCoupons(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListAppRewardCouponsResponse.OK(response) =>
      response.appRewardCoupons
        .traverse(req => Contract.fromJson(coinCodegen.AppRewardCoupon.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object ListValidatorRewardCoupons
      extends BaseCommand[
        http.ListValidatorRewardCouponsResponse,
        Seq[
          Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListValidatorRewardCouponsResponse] =
      client.listValidatorRewardCoupons(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListValidatorRewardCouponsResponse.OK(response) =>
      response.validatorRewardCoupons
        .traverse(req => Contract.fromJson(coinCodegen.ValidatorRewardCoupon.COMPANION)(req))
        .leftMap(_.toString)
    }
  }
  case object UserStatus extends BaseCommand[http.UserStatusResponse, UserStatusData] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.userStatus(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.UserStatusResponse.OK(response) =>
        Right(
          UserStatusData(
            response.partyId,
            response.userOnboarded,
            response.userWalletInstalled,
            response.hasFeaturedAppRight,
          )
        )
    }
  }

  case object CancelFeaturedAppRight
      extends BaseCommand[http.CancelFeaturedAppRightsResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.cancelFeaturedAppRights(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.CancelFeaturedAppRightsResponse.OK => Right(())
    }
  }

  case class ListTransactions(
      beginAfterId: Option[String],
      pageSize: Int,
  ) extends BaseCommand[http.ListTransactionsResponse, Seq[UserWalletTxLogParser.TxLogEntry]] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.listTransactions(
        body = definitions.ListTransactionsRequest(
          beginAfterId = beginAfterId,
          pageSize = pageSize.toLong,
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListTransactionsResponse.OK(response) =>
      response.items.traverse(UserWalletTxLogParser.TxLogEntry.fromResponseItem)
    }
  }
}
