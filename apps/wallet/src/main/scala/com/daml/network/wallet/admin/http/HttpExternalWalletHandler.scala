package com.daml.network.wallet.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.codegen.java.cn.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.http.v0.external.wallet.WalletResource as r0
import com.daml.network.http.v0.{external, definitions as d0}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.Codec
import com.daml.network.wallet.UserWalletManager
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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class HttpExternalWalletHandler(
    override protected val walletManager: UserWalletManager,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
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
      val userWalletStore = getUserWallet(user).store
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
              exerciseWalletAction((installCid, _) => {
                val receiver = Codec.tryDecode(Codec.Party)(request.receiverPartyId)
                val amount = Codec.tryDecode(Codec.JavaBigDecimal)(request.amount)
                val expiresAt = Codec.tryDecode(Codec.Timestamp)(request.expiresAt)
                Future.successful(
                  installCid
                    .exerciseWalletAppInstall_CreateTransferOffer(
                      receiver.toProtoPrimitive,
                      new PaymentAmount(amount, Currency.CC),
                      request.description,
                      expiresAt.toInstant,
                      request.trackingId,
                    )
                    .map { cid =>
                      r0.CreateTransferOfferResponse.OK(
                        d0.CreateTransferOfferResponse(Codec.encodeContractId(cid.exerciseResult))
                      )
                    }
                )
              })(
                user,
                dedup = Some(
                  (
                    CNLedgerConnection.CommandId(
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
          )(status => r0.GetTransferOfferStatusResponseOK(status.toStatusResponse))
      }
    }
  }

  override def createBuyTrafficRequest(respond: r0.CreateBuyTrafficRequestResponse.type)(
      body: d0.RequestBuyTrafficRequest
  )(tuser: TracedUser): Future[r0.CreateBuyTrafficRequestResponse] = ???

  override def getBuyTrafficRequestStatus(
      respond: r0.GetBuyTrafficRequestStatusResponse.type
  )(trackingId: String)(tuser: TracedUser): Future[r0.GetBuyTrafficRequestStatusResponse] = ???
}

object HttpExternalWalletHandler {
  case class CreateTransferOfferRetryable(operationName: String) extends ExceptionRetryable {
    override def retryOK(outcome: Try[_], logger: TracedLogger)(implicit
        tc: TraceContext
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
