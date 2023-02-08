package com.daml.network.wallet.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOfferCodegen,
}
import com.daml.network.http.v0.wallet as http
import com.daml.network.http.v0.definitions
import com.daml.network.util.TemplateJsonDecoder
import com.daml.network.util.{Proto, Contract}
import com.digitalasset.canton.{DomainAlias, ProtoDeserializationError}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}

import scala.concurrent.{ExecutionContext, Future}

object HttpWalletAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.WalletClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.WalletClient(host)
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

  final case class UserStatusData(
      party: String,
      userOnboarded: Boolean,
      hasFeaturedAppRight: Boolean,
  )
  sealed trait SubscriptionStateContract
  final case class SubscriptionStateIdleContract(contract: definitions.Contract)
      extends SubscriptionStateContract
  final case class SubscriptionStatePaymentContract(contract: definitions.Contract)
      extends SubscriptionStateContract

  object SubscriptionStateContract {
    def unapply(state: Option[definitions.SubscriptionState]): Option[SubscriptionStateContract] = {
      state match {
        case Some(definitions.SubscriptionState(Some(state), None)) =>
          Some(SubscriptionStateIdleContract(state))
        case Some(definitions.SubscriptionState(None, Some(state))) =>
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

    override def handleResponse(
        response: http.ListResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, ListResponse] = {
      response match {
        case http.ListResponse.OK(response) =>
          def decodePositions(position: definitions.CoinPosition) =
            for {
              contract <- Contract
                .fromJson(coinCodegen.Coin.COMPANION)(position.contract)
                .leftMap(_.toString)

              accruedHoldingFee <- Proto.decode(Proto.BigDecimal)(position.accruedHoldingFee)
              effectiveAmount <- Proto.decode(Proto.BigDecimal)(position.effectiveAmount)
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

              accruedHoldingFee <- Proto.decode(Proto.BigDecimal)(lockedPosition.accruedHoldingFee)
              effectiveAmount <- Proto.decode(Proto.BigDecimal)(lockedPosition.effectiveAmount)
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
  }

  case class Tap(amount: BigDecimal)
      extends BaseCommand[http.TapResponse, coinCodegen.Coin.ContractId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.TapResponse] =
      client.tap(definitions.TapRequest(Proto.encode(amount)), headers = headers)

    override def handleResponse(
        response: http.TapResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, coinCodegen.Coin.ContractId] = {
      response match {
        case http.TapResponse.OK(response) =>
          Proto.decodeJavaContractId(coinCodegen.Coin.COMPANION)(response.contractId)
        case http.TapResponse.NotFound => Err.TapResponse.NotFound
        case http.TapResponse.Gone => Err.TapResponse.Aborted
      }
    }
  }

  object Err {
    object TapResponse {
      val NotFound = Left("Tap request not found")
      val Aborted = Left("Tap request aborted")
    }
    object AcceptAppPaymentRequestResponse {
      val NotFound = Left("AcceptAppPayment request not found")
    }
    object RejectAppPaymentRequestResponse {
      val NotFound = Left("RejectAppPayment request not found")
    }
    object AcceptSubscriptionRequestResponse {
      val NotFound = Left("AcceptSubscription request not found")
    }
    object RejectSubscriptionRequestResponse {
      val NotFound = Left("RejectSubscription request not found")
    }
    object CancelSubscriptionRequestResponse {
      val NotFound = Left("CancelSubscription request not found")
    }
    object AcceptTransferOfferResponse {
      val NotFound = Left("AcceptTransferOffer request not found")
    }
    object RejectTransferOfferResponse {
      val NotFound = Left("RejectTransferOffer request not found")
    }
    object WithdrawTransferOfferResponse {
      val NotFound = Left("WithdrawTransferOffer request not found")
    }
    object CreateTransferOfferResponse {
      val Conflict = Left("CreateTransferOffer duplicate command")
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

    override def handleResponse(
        response: http.SelfGrantFeatureAppRightResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, coinCodegen.FeaturedAppRight.ContractId] = {
      response match {
        case http.SelfGrantFeatureAppRightResponse.OK(response) =>
          Proto.decodeJavaContractId(coinCodegen.FeaturedAppRight.COMPANION)(response.contractId)
      }
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

    override def handleResponse(
        response: http.GetBalanceResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Balance] = {
      response match {
        case http.GetBalanceResponse.OK(response) =>
          for {
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
    }
  }

  case object ListAppPaymentRequests
      extends BaseCommand[
        http.ListAppPaymentRequestsResponse,
        Seq[
          Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAppPaymentRequestsResponse] =
      client.listAppPaymentRequests(headers = headers)

    override def handleResponse(
        response: http.ListAppPaymentRequestsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[
      Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
    ]] = {
      response match {
        case http.ListAppPaymentRequestsResponse.OK(response) =>
          response.paymentRequests
            .traverse(req => Contract.fromJson(walletCodegen.AppPaymentRequest.COMPANION)(req))
            .leftMap(_.toString)
      }
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
      client.acceptAppPaymentRequest(Proto.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.AcceptAppPaymentRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, walletCodegen.AcceptedAppPayment.ContractId] = {
      response match {
        case http.AcceptAppPaymentRequestResponse.OK(response) =>
          Proto.decodeJavaContractId(walletCodegen.AcceptedAppPayment.COMPANION)(
            response.acceptedPaymentContractId
          )
        case http.AcceptAppPaymentRequestResponse.NotFound =>
          Err.AcceptAppPaymentRequestResponse.NotFound
      }
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
      client.rejectAppPaymentRequest(Proto.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.RejectAppPaymentRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.RejectAppPaymentRequestResponse.OK => Right(())
        case http.RejectAppPaymentRequestResponse.NotFound =>
          Err.RejectAppPaymentRequestResponse.NotFound
      }
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

    override def handleResponse(
        response: http.ListAcceptedAppPaymentsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[
      Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment]
    ]] = {
      response match {
        case http.ListAcceptedAppPaymentsResponse.OK(response) =>
          response.acceptedAppPayments
            .traverse(req => Contract.fromJson(walletCodegen.AcceptedAppPayment.COMPANION)(req))
            .leftMap(_.toString)
      }
    }
  }

  case object ListSubscriptionRequests
      extends BaseCommand[
        http.ListSubscriptionRequestsResponse,
        Seq[
          Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListSubscriptionRequestsResponse] =
      client.listSubscriptionRequests(headers = headers)

    override def handleResponse(
        response: http.ListSubscriptionRequestsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[
      Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]
    ]] = {
      response match {
        case http.ListSubscriptionRequestsResponse.OK(response) =>
          response.subscriptionRequests
            .traverse(req => Contract.fromJson(subsCodegen.SubscriptionRequest.COMPANION)(req))
            .leftMap(_.toString)
      }
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

    override def handleResponse(
        response: http.ListSubscriptionInitialPaymentsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[Contract[
      subsCodegen.SubscriptionInitialPayment.ContractId,
      subsCodegen.SubscriptionInitialPayment,
    ]]] =
      response match {
        case http.ListSubscriptionInitialPaymentsResponse.OK(response) =>
          response.initialPayments
            .traverse(req =>
              Contract.fromJson(subsCodegen.SubscriptionInitialPayment.COMPANION)(req)
            )
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

    override def handleResponse(
        response: http.ListSubscriptionsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[Subscription]] = {
      response match {
        case http.ListSubscriptionsResponse.OK(response) =>
          response.subscriptions
            .traverse(sub =>
              for {
                main <- sub.main
                  .toRight("Could not find main subscription contract")
                  .flatMap(
                    Contract.fromJson(subsCodegen.Subscription.COMPANION)(_).leftMap(_.toString)
                  )
                state <- (sub.state match {
                  case SubscriptionStateContract(SubscriptionStateIdleContract(contract)) =>
                    Contract
                      .fromJson(subsCodegen.SubscriptionIdleState.COMPANION)(contract)
                      .map(SubscriptionIdleState)
                  case SubscriptionStateContract(SubscriptionStatePaymentContract(contract)) =>
                    Contract
                      .fromJson(subsCodegen.SubscriptionPayment.COMPANION)(contract)
                      .map(SubscriptionPayment)
                  case None =>
                    Left(ProtoDeserializationError.FieldNotSet("Subscription.state"))
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
      client.acceptSubscriptionRequest(Proto.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.AcceptSubscriptionRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, subsCodegen.SubscriptionInitialPayment.ContractId] = {
      response match {
        case http.AcceptSubscriptionRequestResponse.OK(response) =>
          Proto.decodeJavaContractId(subsCodegen.SubscriptionInitialPayment.COMPANION)(
            response.initialPaymentContractId
          )
        case http.AcceptSubscriptionRequestResponse.NotFound =>
          Err.AcceptSubscriptionRequestResponse.NotFound
      }
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
      client.rejectSubscriptionRequest(Proto.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.RejectSubscriptionRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.RejectSubscriptionRequestResponse.OK =>
          Right(())
        case http.RejectSubscriptionRequestResponse.NotFound =>
          Err.RejectSubscriptionRequestResponse.NotFound
      }
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
      client.cancelSubscriptionRequest(Proto.encodeContractId(stateId), headers = headers)

    override def handleResponse(
        response: http.CancelSubscriptionRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.CancelSubscriptionRequestResponse.OK =>
          Right(())
        case http.CancelSubscriptionRequestResponse.NotFound =>
          Err.CancelSubscriptionRequestResponse.NotFound
      }
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
        Proto.encode(receiver),
        Proto.encode(amount),
        description,
        Proto.encode(expiresAt),
        idempotencyKey,
      )

      client.createTransferOffer(request, headers = headers)
    }

    override def handleResponse(
        response: http.CreateTransferOfferResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, transferOfferCodegen.TransferOffer.ContractId] = {
      response match {
        case http.CreateTransferOfferResponse.OK(response) =>
          Proto.decodeJavaContractId(transferOfferCodegen.TransferOffer.COMPANION)(
            response.offerContractId
          )
        case http.CreateTransferOfferResponse.Conflict =>
          Err.CreateTransferOfferResponse.Conflict
      }
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

    override def handleResponse(
        response: http.ListTransferOffersResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[Contract[
      transferOfferCodegen.TransferOffer.ContractId,
      transferOfferCodegen.TransferOffer,
    ]]] = {
      response match {
        case http.ListTransferOffersResponse.OK(response) =>
          response.offers
            .traverse(req => Contract.fromJson(transferOfferCodegen.TransferOffer.COMPANION)(req))
            .leftMap(_.toString)
      }
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
      client.acceptTransferOffer(Proto.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.AcceptTransferOfferResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, transferOfferCodegen.AcceptedTransferOffer.ContractId] = {
      response match {
        case http.AcceptTransferOfferResponse.OK(response) =>
          Proto.decodeJavaContractId(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(
            response.acceptedOfferContractId
          )
        case http.AcceptTransferOfferResponse.NotFound =>
          Err.AcceptTransferOfferResponse.NotFound
      }
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

    override def handleResponse(
        response: http.ListAcceptedTransferOffersResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[Contract[
      transferOfferCodegen.AcceptedTransferOffer.ContractId,
      transferOfferCodegen.AcceptedTransferOffer,
    ]]] = {
      response match {
        case http.ListAcceptedTransferOffersResponse.OK(response) =>
          response.acceptedOffers
            .traverse(req =>
              Contract.fromJson(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(req)
            )
            .leftMap(_.toString)
      }
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
      client.rejectTransferOffer(Proto.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.RejectTransferOfferResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.RejectTransferOfferResponse.OK => Right(())
        case http.RejectTransferOfferResponse.NotFound =>
          Err.RejectTransferOfferResponse.NotFound
      }
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
      client.withdrawTransferOffer(Proto.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.WithdrawTransferOfferResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.WithdrawTransferOfferResponse.OK => Right(())
        case http.WithdrawTransferOfferResponse.NotFound =>
          Err.WithdrawTransferOfferResponse.NotFound
      }
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

    override def handleResponse(
        response: http.ListAppRewardCouponsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[
      Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]
    ]] = {
      response match {
        case http.ListAppRewardCouponsResponse.OK(response) =>
          response.appRewardCoupons
            .traverse(req => Contract.fromJson(coinCodegen.AppRewardCoupon.COMPANION)(req))
            .leftMap(_.toString)
      }
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

    override def handleResponse(
        response: http.ListValidatorRewardCouponsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[
      Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
    ]] = {
      response match {
        case http.ListValidatorRewardCouponsResponse.OK(response) =>
          response.validatorRewardCoupons
            .traverse(req => Contract.fromJson(coinCodegen.ValidatorRewardCoupon.COMPANION)(req))
            .leftMap(_.toString)
      }
    }
  }
  case object UserStatus extends BaseCommand[http.UserStatusResponse, UserStatusData] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.userStatus(headers = headers)

    override def handleResponse(
        response: http.UserStatusResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, UserStatusData] =
      response match {
        case http.UserStatusResponse.OK(response) =>
          Right(
            UserStatusData(response.partyId, response.userOnboarded, response.hasFeaturedAppRight)
          )
      }
  }

  case object ListConnectedDomains
      extends BaseCommand[http.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.listConnectedDomains(headers)

    override def handleResponse(
        response: http.ListConnectedDomainsResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, Map[DomainAlias, DomainId]] =
      response match {
        case http.ListConnectedDomainsResponse.OK(response) =>
          response.connectedDomains.toList
            .traverse { case (k, v) =>
              for {
                k <- DomainAlias.create(k)
                v <- DomainId.fromString(v)
              } yield (k, v)
            }
            .map(_.toMap)
      }
  }

  case object CancelFeaturedAppRight
      extends BaseCommand[http.CancelFeaturedAppRightsResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.cancelFeaturedAppRights(headers = headers)

    override def handleResponse(
        response: http.CancelFeaturedAppRightsResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, Unit] = Right(())
  }
}
