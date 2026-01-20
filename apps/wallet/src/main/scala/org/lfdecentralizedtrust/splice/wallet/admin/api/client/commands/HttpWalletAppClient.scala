// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes}
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation as amuletAllocationCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferPreapproval
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as validatorLicenseCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOfferCodegen,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, wallet as http}
import org.lfdecentralizedtrust.splice.http.v0.status.wallet as statusHttp
import org.lfdecentralizedtrust.splice.http.v0.external.wallet as externalHttp
import org.lfdecentralizedtrust.splice.http.v0.wallet.{
  CreateTokenStandardTransferResponse,
  GetAppPaymentRequestResponse,
  GetSubscriptionRequestResponse,
  ListAllocationRequestsResponse,
  ListTokenStandardTransfersResponse,
  WalletClient,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  Contract,
  ContractWithState,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulettransferinstruction.AmuletTransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv1.AllocationRequest
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  allocationv1,
  transferinstructionv1,
}

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object HttpWalletAppClient {
  import http.WalletClient as Client
  import externalHttp.WalletClient as EClient
  import statusHttp.WalletClient as SClient

  abstract class InternalBaseCommand[Res, Result] extends HttpCommand[Res, Result, Client] {
    val createGenClientFn = (fn, host, ec, mat) => Client.httpClient(fn, host)(ec, mat)
    override val nonErrorStatusCodes = Set(StatusCodes.Conflict)
  }

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result, EClient] {
    val createGenClientFn = (fn, host, ec, mat) => EClient.httpClient(fn, host)(ec, mat)
  }

  abstract class StatusBaseCommand[Res, Result] extends HttpCommand[Res, Result, SClient] {
    val createGenClientFn = (fn, host, ec, mat) => SClient.httpClient(fn, host)(ec, mat)
    override val nonErrorStatusCodes = Set(StatusCodes.Conflict)
  }

  final case class AmuletPosition(
      contract: ContractWithState[amuletCodegen.Amulet.ContractId, amuletCodegen.Amulet],
      round: Long,
      accruedHoldingFee: BigDecimal,
      effectiveAmount: BigDecimal,
  )

  final case class LockedAmuletPosition(
      contract: ContractWithState[
        amuletCodegen.LockedAmulet.ContractId,
        amuletCodegen.LockedAmulet,
      ],
      round: Long,
      accruedHoldingFee: BigDecimal,
      effectiveAmount: BigDecimal,
  )

  final case class ListResponse(
      amulets: Seq[AmuletPosition],
      lockedAmulets: Seq[LockedAmuletPosition],
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
        case definitions.SubscriptionState.members.SubscriptionIdleState(state) =>
          Some(SubscriptionStateIdleContract(state.idle))
        case definitions.SubscriptionState.members.SubscriptionPaymentState(state) =>
          Some(SubscriptionStatePaymentContract(state.payment))
        case _ => None
      }
    }
  }

  case object ListPositions
      extends InternalBaseCommand[
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
      def decodePositions(position: definitions.AmuletPosition) =
        for {
          contract <- ContractWithState
            .fromHttp(amuletCodegen.Amulet.COMPANION)(position.contract)
            .leftMap(_.toString)

          accruedHoldingFee <- Codec.decode(Codec.BigDecimal)(position.accruedHoldingFee)
          effectiveAmount <- Codec.decode(Codec.BigDecimal)(position.effectiveAmount)
        } yield {
          new AmuletPosition(
            contract,
            position.round,
            accruedHoldingFee,
            effectiveAmount,
          )
        }

      def decodeLockedPositions(lockedPosition: definitions.AmuletPosition) =
        for {
          contract <- ContractWithState
            .fromHttp(amuletCodegen.LockedAmulet.COMPANION)(lockedPosition.contract)
            .leftMap(_.toString)

          accruedHoldingFee <- Codec.decode(Codec.BigDecimal)(lockedPosition.accruedHoldingFee)
          effectiveAmount <- Codec.decode(Codec.BigDecimal)(lockedPosition.effectiveAmount)
        } yield {
          LockedAmuletPosition(
            contract,
            lockedPosition.round,
            accruedHoldingFee,
            effectiveAmount,
          )
        }

      for {
        positions <- response.amulets.traverse(decodePositions)
        lockedPositions <- response.lockedAmulets.traverse(decodeLockedPositions)
      } yield {
        ListResponse(positions, lockedPositions)
      }
    }
  }

  case class Tap(amount: BigDecimal, commandId: Option[String] = None)
      extends InternalBaseCommand[http.TapResponse, amuletCodegen.Amulet.ContractId] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.TapResponse] =
      client.tap(definitions.TapRequest(Codec.encode(amount), commandId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.TapResponse.OK(response) =>
      Codec.decodeJavaContractId(amuletCodegen.Amulet.COMPANION)(response.contractId)
    }
  }

  case object SelfGrantFeaturedAppRight
      extends InternalBaseCommand[
        http.SelfGrantFeatureAppRightResponse,
        amuletCodegen.FeaturedAppRight.ContractId,
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.SelfGrantFeatureAppRightResponse] =
      client.selfGrantFeatureAppRight(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.SelfGrantFeatureAppRightResponse.OK(response) =>
      Codec.decodeJavaContractId(amuletCodegen.FeaturedAppRight.COMPANION)(response.contractId)
    }
  }

  case object GetBalance
      extends InternalBaseCommand[
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
      extends InternalBaseCommand[
        http.ListAppPaymentRequestsResponse,
        Seq[ContractWithState[
          walletCodegen.AppPaymentRequest.ContractId,
          walletCodegen.AppPaymentRequest,
        ]],
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
        .traverse(contractWithState =>
          for {
            contract <- Contract
              .fromHttp(walletCodegen.AppPaymentRequest.COMPANION)(contractWithState.contract)
              .leftMap(_.toString)
            synchronizerId <- contractWithState.domainId.traverse(SynchronizerId.fromString)
          } yield ContractWithState(
            contract,
            synchronizerId.fold(ContractState.InFlight: ContractState)(ContractState.Assigned.apply),
          )
        )
    }
  }

  case class GetAppPaymentRequest(
      contractId: walletCodegen.AppPaymentRequest.ContractId
  ) extends InternalBaseCommand[
        http.GetAppPaymentRequestResponse,
        Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAppPaymentRequestResponse] =
      client.getAppPaymentRequest(Codec.encodeContractId(contractId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case GetAppPaymentRequestResponse.OK(response) =>
      Contract
        .fromHttp(walletCodegen.AppPaymentRequest.COMPANION)(response.contract)
        .leftMap(_.toString)
    }
  }

  case class AcceptAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ) extends InternalBaseCommand[
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
  ) extends InternalBaseCommand[
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
      extends InternalBaseCommand[
        http.ListAcceptedAppPaymentsResponse,
        Seq[
          ContractWithState[
            walletCodegen.AcceptedAppPayment.ContractId,
            walletCodegen.AcceptedAppPayment,
          ]
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
        .traverse(req =>
          ContractWithState.fromHttp(walletCodegen.AcceptedAppPayment.COMPANION)(req)
        )
        .leftMap(_.toString)
    }
  }

  case object ListSubscriptionRequests
      extends InternalBaseCommand[
        http.ListSubscriptionRequestsResponse,
        Seq[Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListSubscriptionRequestsResponse] =
      client.listSubscriptionRequests(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListSubscriptionRequestsResponse.OK(response) =>
      response.subscriptionRequests
        .traverse(req => Contract.fromHttp(subsCodegen.SubscriptionRequest.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object ListSubscriptionInitialPayments
      extends InternalBaseCommand[
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
        .traverse(req => Contract.fromHttp(subsCodegen.SubscriptionInitialPayment.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object ListSubscriptions
      extends InternalBaseCommand[
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
              .fromHttp(subsCodegen.Subscription.COMPANION)(sub.subscription)
              .leftMap(_.toString)
            state <- (sub.state match {
              case SubscriptionStateContract(SubscriptionStateIdleContract(contract)) =>
                Contract
                  .fromHttp(subsCodegen.SubscriptionIdleState.COMPANION)(contract)
                  .map(SubscriptionIdleState.apply)
              case SubscriptionStateContract(SubscriptionStatePaymentContract(contract)) =>
                Contract
                  .fromHttp(subsCodegen.SubscriptionPayment.COMPANION)(contract)
                  .map(SubscriptionPayment.apply)
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

  case class GetSubscriptionRequest(
      contractId: subsCodegen.SubscriptionRequest.ContractId
  ) extends InternalBaseCommand[
        http.GetSubscriptionRequestResponse,
        Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSubscriptionRequestResponse] =
      client.getSubscriptionRequest(Codec.encodeContractId(contractId), headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case GetSubscriptionRequestResponse.OK(subscriptionRequest) =>
      Contract
        .fromHttp(subsCodegen.SubscriptionRequest.COMPANION)(subscriptionRequest)
        .leftMap(_.toString)
    }
  }

  case class AcceptSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ) extends InternalBaseCommand[
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
  ) extends InternalBaseCommand[
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
  ) extends InternalBaseCommand[
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
      trackingId: String,
  ) extends ExternalBaseCommand[
        externalHttp.CreateTransferOfferResponse,
        transferOfferCodegen.TransferOffer.ContractId,
      ] {
    def submitRequest(
        client: EClient,
        headers: List[HttpHeader],
    ): EitherT[
      Future,
      Either[Throwable, HttpResponse],
      externalHttp.CreateTransferOfferResponse,
    ] = {
      val request = definitions.CreateTransferOfferRequest(
        Codec.encode(receiver),
        Codec.encode(amount),
        description,
        Codec.encode(expiresAt),
        trackingId,
      )

      client.createTransferOffer(request, headers = headers)
    }

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.CreateTransferOfferResponse.OK(response) =>
      Codec.decodeJavaContractId(transferOfferCodegen.TransferOffer.COMPANION)(
        response.offerContractId
      )
    }
  }

  case class GetTransferOfferStatus(trackingId: String)
      extends ExternalBaseCommand[
        externalHttp.GetTransferOfferStatusResponse,
        definitions.GetTransferOfferStatusResponse,
      ] {
    def submitRequest(
        client: EClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], externalHttp.GetTransferOfferStatusResponse] =
      client.getTransferOfferStatus(trackingId = trackingId, headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.GetTransferOfferStatusResponse.OK(ok) =>
      Right(ok)
    }
  }

  case object ListTransferOffers
      extends ExternalBaseCommand[
        externalHttp.ListTransferOffersResponse,
        Seq[Contract[
          transferOfferCodegen.TransferOffer.ContractId,
          transferOfferCodegen.TransferOffer,
        ]],
      ] {
    def submitRequest(
        client: EClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.ListTransferOffersResponse] =
      client.listTransferOffers(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.ListTransferOffersResponse.OK(response) =>
      response.offers
        .traverse(req => Contract.fromHttp(transferOfferCodegen.TransferOffer.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class AcceptTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends InternalBaseCommand[
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
      extends InternalBaseCommand[
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
          Contract.fromHttp(transferOfferCodegen.AcceptedTransferOffer.COMPANION)(req)
        )
        .leftMap(_.toString)
    }
  }

  case class RejectTransferOffer(
      requestId: transferOfferCodegen.TransferOffer.ContractId
  ) extends InternalBaseCommand[
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
  ) extends InternalBaseCommand[
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

  case class CreateBuyTrafficRequest(
      receivingValidator: PartyId,
      synchronizerId: SynchronizerId,
      trafficAmount: Long,
      expiresAt: CantonTimestamp,
      trackingId: String,
  ) extends ExternalBaseCommand[
        externalHttp.CreateBuyTrafficRequestResponse,
        trafficRequestCodegen.BuyTrafficRequest.ContractId,
      ] {
    def submitRequest(
        client: EClient,
        headers: List[HttpHeader],
    ): EitherT[
      Future,
      Either[Throwable, HttpResponse],
      externalHttp.CreateBuyTrafficRequestResponse,
    ] = {
      val request = definitions.CreateBuyTrafficRequest(
        Codec.encode(receivingValidator),
        Codec.encode(synchronizerId),
        trafficAmount,
        trackingId,
        Codec.encode(expiresAt),
      )
      client.createBuyTrafficRequest(request, headers = headers)
    }

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.CreateBuyTrafficRequestResponse.OK(response) =>
      Codec.decodeJavaContractId(trafficRequestCodegen.BuyTrafficRequest.COMPANION)(
        response.requestContractId
      )
    }
  }

  case class GetTrafficRequestStatus(trackingId: String)
      extends ExternalBaseCommand[
        externalHttp.GetBuyTrafficRequestStatusResponse,
        definitions.GetBuyTrafficRequestStatusResponse,
      ] {
    def submitRequest(
        client: EClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], externalHttp.GetBuyTrafficRequestStatusResponse] = {
      client.getBuyTrafficRequestStatus(trackingId = trackingId, headers = headers)
    }

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[externalHttp.GetBuyTrafficRequestStatusResponse, Either[
      String,
      definitions.GetBuyTrafficRequestStatusResponse,
    ]] = { case externalHttp.GetBuyTrafficRequestStatusResponse.OK(ok) =>
      Right(ok)
    }
  }

  case object ListAppRewardCoupons
      extends InternalBaseCommand[
        http.ListAppRewardCouponsResponse,
        Seq[
          Contract[amuletCodegen.AppRewardCoupon.ContractId, amuletCodegen.AppRewardCoupon]
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
        .traverse(req => Contract.fromHttp(amuletCodegen.AppRewardCoupon.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object ListValidatorRewardCoupons
      extends InternalBaseCommand[
        http.ListValidatorRewardCouponsResponse,
        Seq[
          Contract[
            amuletCodegen.ValidatorRewardCoupon.ContractId,
            amuletCodegen.ValidatorRewardCoupon,
          ]
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
        .traverse(req => Contract.fromHttp(amuletCodegen.ValidatorRewardCoupon.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object ListValidatorFaucetCoupons
      extends InternalBaseCommand[
        http.ListValidatorFaucetCouponsResponse,
        Seq[
          Contract[
            validatorLicenseCodegen.ValidatorFaucetCoupon.ContractId,
            validatorLicenseCodegen.ValidatorFaucetCoupon,
          ]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListValidatorFaucetCouponsResponse] =
      client.listValidatorFaucetCoupons(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListValidatorFaucetCouponsResponse.OK(response) =>
      response.validatorFaucetCoupons
        .traverse(req =>
          Contract.fromHttp(validatorLicenseCodegen.ValidatorFaucetCoupon.COMPANION)(req)
        )
        .leftMap(_.toString)
    }
  }

  case object ListValidatorLivenessActivityRecords
      extends InternalBaseCommand[
        http.ListValidatorLivenessActivityRecordsResponse,
        Seq[
          Contract[
            validatorLicenseCodegen.ValidatorLivenessActivityRecord.ContractId,
            validatorLicenseCodegen.ValidatorLivenessActivityRecord,
          ]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListValidatorLivenessActivityRecordsResponse] =
      client.listValidatorLivenessActivityRecords(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListValidatorLivenessActivityRecordsResponse.OK(response) =>
      response.validatorLivenessActivityRecords
        .traverse(req =>
          Contract.fromHttp(validatorLicenseCodegen.ValidatorLivenessActivityRecord.COMPANION)(req)
        )
        .leftMap(_.toString)
    }
  }

  case object ListSvRewardCoupons
      extends InternalBaseCommand[
        http.ListSvRewardCouponsResponse,
        Seq[
          Contract[
            amuletCodegen.SvRewardCoupon.ContractId,
            amuletCodegen.SvRewardCoupon,
          ]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListSvRewardCouponsResponse] =
      client.listSvRewardCoupons(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListSvRewardCouponsResponse.OK(response) =>
      response.svRewardCoupons
        .traverse(req => Contract.fromHttp(amuletCodegen.SvRewardCoupon.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case object UserStatus extends StatusBaseCommand[statusHttp.UserStatusResponse, UserStatusData] {
    override def submitRequest(
        client: SClient,
        headers: List[HttpHeader],
    ) =
      client.userStatus(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case statusHttp.UserStatusResponse.OK(response) =>
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
      extends InternalBaseCommand[http.CancelFeaturedAppRightsResponse, Unit] {

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
  ) extends InternalBaseCommand[http.ListTransactionsResponse, Seq[
        TxLogEntry.TransactionHistoryTxLogEntry
      ]] {
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
      response.items.traverse(TxLogEntry.Http.fromResponseItem)
    }
  }

  case object CreateTransferPreapproval
      extends InternalBaseCommand[
        http.CreateTransferPreapprovalResponse,
        CreateTransferPreapprovalResponse,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) =
      client.createTransferPreapproval(headers = headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.CreateTransferPreapprovalResponse.OK(response) =>
        Codec
          .decodeJavaContractId(TransferPreapproval.COMPANION)(
            response.transferPreapprovalContractId
          )
          .map(CreateTransferPreapprovalResponse.Created.apply)
      case http.CreateTransferPreapprovalResponse.Conflict(response) =>
        Codec
          .decodeJavaContractId(TransferPreapproval.COMPANION)(
            response.transferPreapprovalContractId
          )
          .map(CreateTransferPreapprovalResponse.AlreadyExists.apply)
    }
  }

  sealed abstract class CreateTransferPreapprovalResponse {
    def contractId: TransferPreapproval.ContractId
  }

  object CreateTransferPreapprovalResponse {
    final case class Created(contractId: TransferPreapproval.ContractId)
        extends CreateTransferPreapprovalResponse
    final case class AlreadyExists(contractId: TransferPreapproval.ContractId)
        extends CreateTransferPreapprovalResponse
  }

  final case class TransferPreapprovalSend(
      receiver: PartyId,
      amount: BigDecimal,
      deduplicationId: String,
      description: Option[String],
  ) extends InternalBaseCommand[http.TransferPreapprovalSendResponse, Unit] {
    override def submitRequest(client: Client, headers: List[HttpHeader]) =
      client.transferPreapprovalSend(
        definitions.TransferPreapprovalSendRequest(
          receiverPartyId = Codec.encode(receiver),
          amount = Codec.encode(amount),
          deduplicationId = deduplicationId,
          description = description,
        ),
        headers = headers,
      )

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.TransferPreapprovalSendResponse.OK => Right(())

    }
  }

  object TokenStandard {
    final case object ListTransfers
        extends InternalBaseCommand[
          http.ListTokenStandardTransfersResponse,
          Seq[Contract[AmuletTransferInstruction.ContractId, AmuletTransferInstruction]],
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[Throwable, HttpResponse], ListTokenStandardTransfersResponse] =
        client.listTokenStandardTransfers(headers = headers)

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[ListTokenStandardTransfersResponse, Either[
        String,
        Seq[Contract[AmuletTransferInstruction.ContractId, AmuletTransferInstruction]],
      ]] = { case http.ListTokenStandardTransfersResponse.OK(value) =>
        value.transfers
          .traverse(Contract.fromHttp(AmuletTransferInstruction.COMPANION)(_))
          .leftMap(_.toString)
      }
    }

    final case class CreateTransfer(
        receiver: PartyId,
        amount: BigDecimal,
        description: String,
        expiresAt: CantonTimestamp,
        trackingId: String,
    ) extends InternalBaseCommand[
          http.CreateTokenStandardTransferResponse,
          definitions.TransferInstructionResultResponse,
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[Throwable, HttpResponse], CreateTokenStandardTransferResponse] =
        client.createTokenStandardTransfer(
          definitions.CreateTokenStandardTransferRequest(
            Codec.encode(receiver),
            Codec.encode(amount),
            description,
            Codec.encode(expiresAt),
            trackingId,
          ),
          headers = headers,
        )

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.CreateTokenStandardTransferResponse, Either[
        String,
        definitions.TransferInstructionResultResponse,
      ]] = {
        case http.CreateTokenStandardTransferResponse.OK(value) =>
          Right(value)
        case http.CreateTokenStandardTransferResponse.Conflict(value) =>
          Left(value.error)
        case http.CreateTokenStandardTransferResponse.TooManyRequests(value) =>
          Left(value.error)
      }
    }

    final case class AcceptTransfer(
        contractId: transferinstructionv1.TransferInstruction.ContractId
    ) extends InternalBaseCommand[
          http.AcceptTokenStandardTransferResponse,
          definitions.TransferInstructionResultResponse,
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[
        Throwable,
        HttpResponse,
      ], http.AcceptTokenStandardTransferResponse] =
        client.acceptTokenStandardTransfer(
          contractId.contractId,
          headers = headers,
        )

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.AcceptTokenStandardTransferResponse, Either[
        String,
        definitions.TransferInstructionResultResponse,
      ]] = { case http.AcceptTokenStandardTransferResponse.OK(value) =>
        Right(value)
      }
    }

    final case class RejectTransfer(
        contractId: transferinstructionv1.TransferInstruction.ContractId
    ) extends InternalBaseCommand[
          http.RejectTokenStandardTransferResponse,
          definitions.TransferInstructionResultResponse,
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[
        Throwable,
        HttpResponse,
      ], http.RejectTokenStandardTransferResponse] =
        client.rejectTokenStandardTransfer(
          contractId.contractId,
          headers = headers,
        )

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.RejectTokenStandardTransferResponse, Either[
        String,
        definitions.TransferInstructionResultResponse,
      ]] = { case http.RejectTokenStandardTransferResponse.OK(value) =>
        Right(value)
      }
    }

    final case class WithdrawTransfer(
        contractId: transferinstructionv1.TransferInstruction.ContractId
    ) extends InternalBaseCommand[
          http.WithdrawTokenStandardTransferResponse,
          definitions.TransferInstructionResultResponse,
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[
        Throwable,
        HttpResponse,
      ], http.WithdrawTokenStandardTransferResponse] =
        client.withdrawTokenStandardTransfer(
          contractId.contractId,
          headers = headers,
        )

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.WithdrawTokenStandardTransferResponse, Either[
        String,
        definitions.TransferInstructionResultResponse,
      ]] = { case http.WithdrawTokenStandardTransferResponse.OK(value) =>
        Right(value)
      }
    }

    final case class AllocateAmulet(spec: allocationv1.AllocationSpecification)
        extends InternalBaseCommand[
          http.AllocateAmuletResponse,
          definitions.AllocateAmuletResponse,
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[
        Throwable,
        HttpResponse,
      ], http.AllocateAmuletResponse] =
        client.allocateAmulet(
          definitions.AllocateAmuletRequest(
            definitions.AllocateAmuletRequest.Settlement(
              executor = spec.settlement.executor,
              settlementRef = definitions.AllocateAmuletRequest.Settlement.SettlementRef(
                spec.settlement.settlementRef.id,
                spec.settlement.settlementRef.cid.map(_.contractId).toScala,
              ),
              requestedAt =
                Codec.encode(CantonTimestamp.assertFromInstant(spec.settlement.requestedAt)),
              allocateBefore =
                Codec.encode(CantonTimestamp.assertFromInstant(spec.settlement.allocateBefore)),
              settleBefore =
                Codec.encode(CantonTimestamp.assertFromInstant(spec.settlement.settleBefore)),
              meta = Some(spec.settlement.meta.values.asScala.toMap),
            ),
            spec.transferLegId,
            definitions.AllocateAmuletRequest.TransferLeg(
              spec.transferLeg.receiver,
              spec.transferLeg.amount.toString,
              Some(spec.transferLeg.meta.values.asScala.toMap),
            ),
          ),
          headers = headers,
        )

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.AllocateAmuletResponse, Either[
        String,
        definitions.AllocateAmuletResponse,
      ]] = { case http.AllocateAmuletResponse.OK(value) =>
        Right(value)
      }
    }

    final case class WithdrawAmuletAllocation(
        contractId: amuletAllocationCodegen.AmuletAllocation.ContractId
    ) extends InternalBaseCommand[
          http.WithdrawAmuletAllocationResponse,
          definitions.AmuletAllocationWithdrawResult,
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[
        Throwable,
        HttpResponse,
      ], http.WithdrawAmuletAllocationResponse] =
        client.withdrawAmuletAllocation(contractId.contractId, headers)

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.WithdrawAmuletAllocationResponse, Either[
        String,
        definitions.AmuletAllocationWithdrawResult,
      ]] = { case http.WithdrawAmuletAllocationResponse.OK(value) =>
        Right(value)
      }
    }

    final case object ListAmuletAllocations
        extends InternalBaseCommand[
          http.ListAmuletAllocationsResponse,
          Seq[
            Contract[
              amuletAllocationCodegen.AmuletAllocation.ContractId,
              amuletAllocationCodegen.AmuletAllocation,
            ]
          ],
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAmuletAllocationsResponse] =
        client.listAmuletAllocations(headers)

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.ListAmuletAllocationsResponse, Either[
        String,
        Seq[
          Contract[
            amuletAllocationCodegen.AmuletAllocation.ContractId,
            amuletAllocationCodegen.AmuletAllocation,
          ]
        ],
      ]] = { case http.ListAmuletAllocationsResponse.OK(allocationRequestsResponse) =>
        allocationRequestsResponse.allocations
          .traverse(ar =>
            Contract
              .fromHttp(amuletAllocationCodegen.AmuletAllocation.COMPANION)(
                ar.contract
              )
          )
          .leftMap(_.toString)
      }
    }

    final case object ListAllocationRequests
        extends InternalBaseCommand[
          http.ListAllocationRequestsResponse,
          Seq[
            Contract[
              allocationrequestv1.AllocationRequest.ContractId,
              allocationrequestv1.AllocationRequestView,
            ]
          ],
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAllocationRequestsResponse] =
        client.listAllocationRequests(headers)

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.ListAllocationRequestsResponse, Either[
        String,
        Seq[
          Contract[
            allocationrequestv1.AllocationRequest.ContractId,
            allocationrequestv1.AllocationRequestView,
          ]
        ],
      ]] = { case ListAllocationRequestsResponse.OK(allocationRequestsResponse) =>
        allocationRequestsResponse.allocationRequests
          .traverse(ar =>
            Contract
              .fromHttp(allocationrequestv1.AllocationRequest.INTERFACE)(
                ar.contract
              )
          )
          .leftMap(_.toString)
      }
    }

    case class RejectAllocationRequest(id: AllocationRequest.ContractId)
        extends InternalBaseCommand[
          http.RejectAllocationRequestResponse,
          Unit,
        ] {
      override def submitRequest(
          client: WalletClient,
          headers: List[HttpHeader],
      ): EitherT[Future, Either[Throwable, HttpResponse], http.RejectAllocationRequestResponse] =
        client.rejectAllocationRequest(id.contractId, headers)

      override protected def handleOk()(implicit
          decoder: TemplateJsonDecoder
      ): PartialFunction[http.RejectAllocationRequestResponse, Either[
        String,
        Unit,
      ]] = { case http.RejectAllocationRequestResponse.OK(_) =>
        Right(())
      }
    }
  }

  final case class AllocateDevelopmentFundCouponRequest(
      unclaimedDevelopmentFundCouponContractIds: Seq[
        amuletCodegen.UnclaimedDevelopmentFundCoupon.ContractId
      ],
      beneficiary: PartyId,
      amount: BigDecimal,
      expiresAt: CantonTimestamp,
      reason: String,
      fundManager: PartyId,
  ) extends InternalBaseCommand[
        http.AllocateDevelopmentFundCouponResponse,
        definitions.AllocateDevelopmentFundCouponResponse,
      ] {
    override def submitRequest(
        client: WalletClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.AllocateDevelopmentFundCouponResponse] = client.allocateDevelopmentFundCoupon(
      body = definitions.AllocateDevelopmentFundCouponRequest(
        unclaimedDevelopmentFundCouponContractIds.map(_.contractId).toVector,
        Codec.encode(beneficiary),
        Codec.encode(amount),
        Codec.encode(expiresAt),
        reason,
        Codec.encode(fundManager),
      )
    )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[http.AllocateDevelopmentFundCouponResponse, Either[
      String,
      definitions.AllocateDevelopmentFundCouponResponse,
    ]] = { case http.AllocateDevelopmentFundCouponResponse.OK(value) =>
      Right(value)
    }
  }

  case object ListActiveDevelopmentFundCoupons
      extends InternalBaseCommand[
        http.ListActiveDevelopmentFundCouponsResponse,
        Seq[
          Contract[
            amuletCodegen.DevelopmentFundCoupon.ContractId,
            amuletCodegen.DevelopmentFundCoupon,
          ]
        ],
      ] {
    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListActiveDevelopmentFundCouponsResponse] =
      client.listActiveDevelopmentFundCoupons(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListActiveDevelopmentFundCouponsResponse.OK(response) =>
      response.activeDevelopmentFundCoupons
        .traverse(req => Contract.fromHttp(amuletCodegen.DevelopmentFundCoupon.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

}
