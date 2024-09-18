// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.splice.wallet.payment.{Unit, PaymentAmount}
import com.daml.network.codegen.java.splice.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.{
  SpliceLedgerConnection,
  ParticipantAdminConnection,
  RetryProvider,
}
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.http.v0.external.wallet.WalletResource as r0
import com.daml.network.http.v0.{external, definitions as d0}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.Codec
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.config.RequireTypes.PositiveLong
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.RetryUtil.{
  ErrorKind,
  ExceptionRetryable,
  FatalErrorKind,
  NoErrorKind,
  TransientErrorKind,
}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import cats.syntax.either.*
import com.daml.network.wallet.store.TxLogEntry

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class HttpExternalWalletHandler(
    override protected val walletManager: UserWalletManager,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    participantAdminConnection: ParticipantAdminConnection,
    domainMigrationId: Long,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends external.wallet.WalletHandler[TracedUser]
    with HttpWalletHandlerUtil {

  protected val workflowId = this.getClass.getSimpleName

  override def createTransferOffer(respond: r0.CreateTransferOfferResponse.type)(
      request: d0.CreateTransferOfferRequest
  )(tuser: TracedUser): Future[r0.CreateTransferOfferResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.createTransferOffer") { _ => _ =>
      getUserStore(user).flatMap(userWalletStore =>
        userWalletStore
          .getLatestTransferOfferEventByTrackingId(request.trackingId)
          .flatMap {
            case QueryResult(_, Some(_)) =>
              Future.failed(
                io.grpc.Status.ALREADY_EXISTS
                  .withDescription(
                    s"Transfer offer with trackingId ${request.trackingId} already exists."
                  )
                  .asRuntimeException()
              )
            case QueryResult(dedupOffset, None) =>
              val sender = userWalletStore.key.endUserParty
              // TODO(#8300) revisit if we want to retry here.
              retryProvider.retryForClientCalls(
                "createTransferOffer",
                "createTransferOffer",
                exerciseWalletAction((installCid, _) => {
                  val receiver = Codec.tryDecode(Codec.Party)(request.receiverPartyId)
                  val amount = Codec.tryDecode(Codec.JavaBigDecimal)(request.amount)
                  val expiresAt = Codec.tryDecode(Codec.Timestamp)(request.expiresAt)
                  Future.successful(
                    installCid
                      .exerciseWalletAppInstall_CreateTransferOffer(
                        receiver.toProtoPrimitive,
                        new PaymentAmount(amount, Unit.AMULETUNIT),
                        request.description,
                        expiresAt.toInstant,
                        request.trackingId,
                      )
                      .map { cid =>
                        r0.CreateTransferOfferResponse.OK(
                          d0.CreateTransferOfferResponse(
                            Codec.encodeContractId(cid.exerciseResult.transferOffer)
                          )
                        )
                      }
                  )
                })(
                  user,
                  dedup = Some(
                    (
                      SpliceLedgerConnection.CommandId(
                        "com.daml.network.wallet.createTransferOffer",
                        Seq(
                          sender,
                          Codec.tryDecode(Codec.Party)(request.receiverPartyId),
                        ),
                        request.trackingId,
                      ),
                      DedupOffset(dedupOffset),
                    )
                  ),
                ),
                logger,
                HttpExternalWalletHandler.CreateTransferOfferRetryable(_),
              )
          }
          .transform(HttpErrorHandler.onGrpcAlreadyExists("CreateTransferOffer duplicate command"))
      )
    }
  }

  override def listTransferOffers(
      respond: r0.ListTransferOffersResponse.type
  )()(
      tuser: TracedUser
  ): Future[r0.ListTransferOffersResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      transferOffersCodegen.TransferOffer.COMPANION,
      user,
      d0.ListTransferOffersResponse(_),
    )
  }

  def getTransferOfferStatus(
      respond: r0.GetTransferOfferStatusResponse.type
  )(
      trackingId: String
  )(tuser: TracedUser): Future[r0.GetTransferOfferStatusResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.getTransferOfferStatus") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        txLogEntry <- userStore.getLatestTransferOfferEventByTrackingId(trackingId)
      } yield {
        txLogEntry.value
          .map(_.status)
          .fold[r0.GetTransferOfferStatusResponse](
            r0.GetTransferOfferStatusResponseNotFound(
              d0.ErrorResponse(s"Couldn't find transfer offer with tracking id $trackingId")
            )
          )(status => r0.GetTransferOfferStatusResponseOK(TxLogEntry.Http.toStatusResponse(status)))
      }
    }
  }

  override def createBuyTrafficRequest(respond: r0.CreateBuyTrafficRequestResponse.type)(
      request: d0.CreateBuyTrafficRequest
  )(tuser: TracedUser): Future[r0.CreateBuyTrafficRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.createBuyTrafficRequest") { _ => _ =>
      val domainId = Codec.tryDecode(Codec.DomainId)(request.domainId)
      val receivingValidator = Codec.tryDecode(Codec.Party)(request.receivingValidatorPartyId)
      val trafficAmount = PositiveLong
        .create(request.trafficAmount)
        .valueOr(_ =>
          throw io.grpc.Status.INVALID_ARGUMENT
            .withDescription(s"trafficAmount must be positive")
            .asRuntimeException()
        )
      for {
        userWalletStore <- getUserStore(user)
        participantId <- participantAdminConnection
          .getPartyToParticipant(
            domainId,
            receivingValidator,
          )
          .transform(
            _.mapping.participantIds,
            // We translate NOT_FOUND raised by getPartyToParticipant to INVALID_ARGUMENT
            // if no PartyToParticipant state is found
            {
              case ex: io.grpc.StatusRuntimeException
                  if ex.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
                throw io.grpc.Status.INVALID_ARGUMENT
                  .withDescription(
                    s"Could not find participant hosting ${receivingValidator} on domain ${domainId}"
                  )
                  .asRuntimeException()
              case other => other
            },
          )
          .map {
            case Seq() =>
              throw io.grpc.Status.INVALID_ARGUMENT
                .withDescription(
                  s"Could not find participant hosting ${receivingValidator} on domain ${domainId}"
                )
                .asRuntimeException()
            case Seq(participantId) => participantId
            case _ =>
              throw io.grpc.Status.INTERNAL
                .withDescription(
                  s"Receiving validator party ${receivingValidator} is hosted on multiple participants, which is not currently supported"
                )
                .asRuntimeException()
          }
        result <- userWalletStore
          .getLatestBuyTrafficRequestEventByTrackingId(request.trackingId)
          .flatMap {
            case QueryResult(_, Some(_)) =>
              Future.failed(
                io.grpc.Status.ALREADY_EXISTS
                  .withDescription(
                    s"Buy traffic request with trackingId ${request.trackingId} already exists."
                  )
                  .asRuntimeException()
              )
            case QueryResult(dedupOffset, None) =>
              val buyer = userWalletStore.key.endUserParty
              // TODO(#8300) revisit if we want to retry here.
              retryProvider
                .retryForClientCalls(
                  "createBuyTrafficRequest",
                  "createBuyTrafficRequest",
                  exerciseWalletAction((installCid, _) => {
                    val expiresAt = Codec.tryDecode(Codec.Timestamp)(request.expiresAt)
                    Future.successful(
                      installCid
                        .exerciseWalletAppInstall_CreateBuyTrafficRequest(
                          participantId.toProtoPrimitive,
                          domainId.toProtoPrimitive,
                          domainMigrationId,
                          trafficAmount.value,
                          expiresAt.toInstant,
                          request.trackingId,
                        )
                        .map { cid =>
                          r0.CreateBuyTrafficRequestResponse.OK(
                            d0.CreateBuyTrafficRequestResponse(
                              Codec.encodeContractId(cid.exerciseResult.buyTrafficRequest)
                            )
                          )
                        }
                    )
                  })(
                    user,
                    dedup = Some(
                      (
                        SpliceLedgerConnection.CommandId(
                          "com.daml.network.wallet.createBuyTrafficRequest",
                          Seq(
                            receivingValidator,
                            buyer,
                          ),
                          request.trackingId,
                        ),
                        DedupOffset(dedupOffset),
                      )
                    ),
                  ),
                  logger,
                  HttpExternalWalletHandler.CreateTransferOfferRetryable(_),
                )
                .transform(
                  HttpErrorHandler.onGrpcAlreadyExists("CreateBuyTrafficRequest duplicate command")
                )
          }
      } yield result
    }
  }

  override def getBuyTrafficRequestStatus(
      respond: r0.GetBuyTrafficRequestStatusResponse.type
  )(trackingId: String)(tuser: TracedUser): Future[r0.GetBuyTrafficRequestStatusResponse] = {

    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.getBuyTrafficRequestStatus") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        txLogEntry <- userStore.getLatestBuyTrafficRequestEventByTrackingId(trackingId)
      } yield {
        txLogEntry.value
          .map(_.status)
          .fold[r0.GetBuyTrafficRequestStatusResponse](
            r0.GetBuyTrafficRequestStatusResponse(
              d0.ErrorResponse(s"Couldn't find buy traffic request with tracking id $trackingId")
            )
          )(status => {
            r0.GetBuyTrafficRequestStatusResponse(TxLogEntry.Http.toStatusResponse(status))
          })
      }
    }
  }
}

object HttpExternalWalletHandler {
  case class CreateTransferOfferRetryable(operationName: String) extends ExceptionRetryable {
    override def retryOK(outcome: Try[_], logger: TracedLogger, lastErrorKind: Option[ErrorKind])(
        implicit tc: TraceContext
    ): ErrorKind = outcome match {
      // TODO(#8300) global domain can be disconnected and reconnected after config of sequencer connections changed
      case Failure(ex: io.grpc.StatusRuntimeException)
          if (ex.getStatus.getCode == Status.Code.FAILED_PRECONDITION && ex.getStatus.getDescription
            .contains("The domain id was not found")) =>
        logger.info(
          s"The operation $operationName failed due to the domain id was not found $ex."
        )
        TransientErrorKind
      case Failure(ex) =>
        logThrowable(ex, logger)
        FatalErrorKind
      case Success(_) => NoErrorKind
    }
  }
}
