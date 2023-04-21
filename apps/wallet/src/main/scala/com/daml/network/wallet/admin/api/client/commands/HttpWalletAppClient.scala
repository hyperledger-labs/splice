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
import com.daml.network.http.v0.definitions.ErrorResponse
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

    override def handleResponse(
        response: http.ListResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, ListResponse] = {
      response match {
        case http.ListResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
        case http.ListResponse.NotFound(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
        case http.ListResponse.OK(response) =>
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
  }

  case class Tap(amount: BigDecimal)
      extends BaseCommand[http.TapResponse, coinCodegen.Coin.ContractId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.TapResponse] =
      client.tap(definitions.TapRequest(Codec.encode(amount)), headers = headers)

    override def handleResponse(
        response: http.TapResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, coinCodegen.Coin.ContractId] = {
      response match {
        case http.TapResponse.OK(response) =>
          Codec.decodeJavaContractId(coinCodegen.Coin.COMPANION)(response.contractId)
        case http.TapResponse.NotFound(ErrorResponse(err)) => Left(err)
        case http.TapResponse.BadRequest(ErrorResponse(err)) => Left(err)
        case http.TapResponse.TooManyRequests(ErrorResponse(err)) => Left(err)
        case http.TapResponse.InternalServerError(ErrorResponse(err)) => Left(err)
        case http.TapResponse.ServiceUnavailable(ErrorResponse(err)) => Left(err)
      }
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
          Codec.decodeJavaContractId(coinCodegen.FeaturedAppRight.COMPANION)(response.contractId)
        case http.SelfGrantFeatureAppRightResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.SelfGrantFeatureAppRightResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
        case http.GetBalanceResponse.NotFound(ErrorResponse(error)) => Left(error)
        case http.GetBalanceResponse.InternalServerError(ErrorResponse(errorMsg)) => Left(errorMsg)
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

    override def handleResponse(
        response: http.ListAppPaymentRequestsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[AppPaymentRequest]] = {
      response match {
        case http.ListAppPaymentRequestsResponse.OK(response) =>
          response.paymentRequests
            .traverse(req => AppPaymentRequest.parseHttp(req))
        case http.ListAppPaymentRequestsResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.ListAppPaymentRequestsResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
      }
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

    override def handleResponse(
        response: http.GetAppPaymentRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, AppPaymentRequest] = {
      response match {
        case GetAppPaymentRequestResponse.OK(response) =>
          AppPaymentRequest.parseHttp(response)
        case GetAppPaymentRequestResponse.NotFound(ErrorResponse(error)) => Left(error)
        case GetAppPaymentRequestResponse.InternalServerError(ErrorResponse(error)) => Left(error)
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
      client.acceptAppPaymentRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.AcceptAppPaymentRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, walletCodegen.AcceptedAppPayment.ContractId] = {
      response match {
        case http.AcceptAppPaymentRequestResponse.OK(response) =>
          Codec.decodeJavaContractId(walletCodegen.AcceptedAppPayment.COMPANION)(
            response.acceptedPaymentContractId
          )
        case http.AcceptAppPaymentRequestResponse.NotFound(ErrorResponse(error)) => Left(error)
        case http.AcceptAppPaymentRequestResponse.BadRequest(ErrorResponse(error)) => Left(error)
        case http.AcceptAppPaymentRequestResponse.TooManyRequests(ErrorResponse(error)) =>
          Left(error)
        case http.AcceptAppPaymentRequestResponse.ServiceUnavailable(ErrorResponse(error)) =>
          Left(error)
        case http.AcceptAppPaymentRequestResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
      client.rejectAppPaymentRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.RejectAppPaymentRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.RejectAppPaymentRequestResponse.OK => Right(())
        case http.RejectAppPaymentRequestResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.RejectAppPaymentRequestResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
        case http.ListAcceptedAppPaymentsResponse.NotFound(ErrorResponse(err)) =>
          Left(err)
        case http.ListAcceptedAppPaymentsResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
      }
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

    override def handleResponse(
        response: http.ListSubscriptionRequestsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[SubscriptionRequest]] = {
      response match {
        case http.ListSubscriptionRequestsResponse.OK(response) =>
          response.subscriptionRequests.traverse(req => SubscriptionRequest.parseHttp(req))
        case http.ListSubscriptionRequestsResponse.NotFound(ErrorResponse(err)) =>
          Left(err)
        case http.ListSubscriptionRequestsResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
        case http.ListSubscriptionInitialPaymentsResponse.NotFound(ErrorResponse(err)) =>
          Left(err)
        case http.ListSubscriptionInitialPaymentsResponse.InternalServerError(
              ErrorResponse(errorMsg)
            ) =>
          Left(errorMsg)
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
        case http.ListSubscriptionsResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.ListSubscriptionsResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
      }
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

    override def handleResponse(
        response: http.GetSubscriptionRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, SubscriptionRequest] = {
      response match {
        case GetSubscriptionRequestResponse.OK(subscriptionRequest) =>
          SubscriptionRequest.parseHttp(subscriptionRequest)
        case GetSubscriptionRequestResponse.NotFound(ErrorResponse(error)) => Left(error)
        case GetSubscriptionRequestResponse.InternalServerError(ErrorResponse(error)) => Left(error)
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
      client.acceptSubscriptionRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.AcceptSubscriptionRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, subsCodegen.SubscriptionInitialPayment.ContractId] = {
      response match {
        case http.AcceptSubscriptionRequestResponse.OK(response) =>
          Codec.decodeJavaContractId(subsCodegen.SubscriptionInitialPayment.COMPANION)(
            response.initialPaymentContractId
          )
        case http.AcceptSubscriptionRequestResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.AcceptSubscriptionRequestResponse.BadRequest(ErrorResponse(error)) =>
          Left(error)
        case http.AcceptSubscriptionRequestResponse.TooManyRequests(ErrorResponse(error)) =>
          Left(error)
        case http.AcceptSubscriptionRequestResponse.ServiceUnavailable(ErrorResponse(error)) =>
          Left(error)
        case http.AcceptSubscriptionRequestResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
      client.rejectSubscriptionRequest(Codec.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.RejectSubscriptionRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.RejectSubscriptionRequestResponse.OK =>
          Right(())
        case http.RejectSubscriptionRequestResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.RejectSubscriptionRequestResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
      client.cancelSubscriptionRequest(Codec.encodeContractId(stateId), headers = headers)

    override def handleResponse(
        response: http.CancelSubscriptionRequestResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.CancelSubscriptionRequestResponse.OK => Right(())
        case http.CancelSubscriptionRequestResponse.NotFound(ErrorResponse(error)) => Left(error)
        case http.CancelSubscriptionRequestResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
        Codec.encode(receiver),
        Codec.encode(amount),
        description,
        Codec.encode(expiresAt),
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
          Codec.decodeJavaContractId(transferOfferCodegen.TransferOffer.COMPANION)(
            response.offerContractId
          )
        case http.CreateTransferOfferResponse.Conflict(ErrorResponse(errorMsg)) => Left(errorMsg)
        case http.CreateTransferOfferResponse.NotFound(ErrorResponse(errorMsg)) => Left(errorMsg)
        case http.CreateTransferOfferResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
        case http.ListTransferOffersResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.ListTransferOffersResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
      client.acceptTransferOffer(Codec.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.AcceptTransferOfferResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, transferOfferCodegen.AcceptedTransferOffer.ContractId] = {
      response match {
        case http.AcceptTransferOfferResponse.OK(response) =>
          Codec.decodeJavaContractId(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(
            response.acceptedOfferContractId
          )
        case http.AcceptTransferOfferResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
        case http.AcceptTransferOfferResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
        case http.ListAcceptedTransferOffersResponse.NotFound(ErrorResponse(error)) => Left(error)
        case http.ListAcceptedTransferOffersResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
      client.rejectTransferOffer(Codec.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.RejectTransferOfferResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.RejectTransferOfferResponse.OK => Right(())
        case http.RejectTransferOfferResponse.NotFound(ErrorResponse(error)) => Left(error)
        case http.RejectTransferOfferResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
      client.withdrawTransferOffer(Codec.encodeContractId(requestId), headers = headers)

    override def handleResponse(
        response: http.WithdrawTransferOfferResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = {
      response match {
        case http.WithdrawTransferOfferResponse.OK => Right(())
        case http.WithdrawTransferOfferResponse.NotFound(ErrorResponse(error)) => Left(error)
        case http.WithdrawTransferOfferResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
        case http.ListAppRewardCouponsResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
        case http.ListAppRewardCouponsResponse.NotFound(ErrorResponse(error)) =>
          Left(error)
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
        case http.ListValidatorRewardCouponsResponse.NotFound(ErrorResponse(error)) => Left(error)
        case http.ListValidatorRewardCouponsResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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
            UserStatusData(
              response.partyId,
              response.userOnboarded,
              response.userWalletInstalled,
              response.hasFeaturedAppRight,
            )
          )
        case http.UserStatusResponse.NotFound(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
        case http.UserStatusResponse.InternalServerError(ErrorResponse(errorMsg)) =>
          Left(errorMsg)
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

    override def handleResponse(
        response: http.ListTransactionsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[UserWalletTxLogParser.TxLogEntry]] =
      response.fold(
        ok => ok.items.traverse(UserWalletTxLogParser.TxLogEntry.fromResponseItem),
        notFound => Left(notFound.error),
        internal => Left(internal.error),
      )
  }
}
