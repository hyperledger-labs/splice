package com.daml.network.wallet.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.codegen.java.cn.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.DedupDuration
import com.daml.network.http.v0.external.wallet.WalletResource as r0
import com.daml.network.http.v0.{external, definitions as d0}
import com.daml.network.util.Codec
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.google.protobuf.Duration
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpExternalWalletHandler(
    override protected val walletManager: UserWalletManager,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends external.wallet.WalletHandler[String]
    with HttpWalletHandlerUtil {

  protected val workflowId = this.getClass.getSimpleName

  override def createTransferOffer(respond: r0.CreateTransferOfferResponse.type)(
      request: d0.CreateTransferOfferRequest
  )(user: String): Future[r0.CreateTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val sender = getUserWallet(user).store.key.endUserParty
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
            DedupDuration(
              Duration.newBuilder().setSeconds(60 * 60 * 24).build()
            ), // 24 hours, similar to Stripe's API, documented at https://stripe.com/docs/api/idempotent_requests
            // If you change this, make sure to update the documentation in the OpenAPI specs.
          )
        ),
      ).transform(HttpErrorHandler.onGrpcAlreadyExists("CreateTransferOffer duplicate command"))
    }

  override def listTransferOffers(
      respond: r0.ListTransferOffersResponse.type
  )()(
      user: String
  ): Future[r0.ListTransferOffersResponse] = {
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
  )(extracted: String): Future[r0.GetTransferOfferStatusResponse] = ???

  override def createBuyTrafficRequest(respond: r0.CreateBuyTrafficRequestResponse.type)(
      body: d0.RequestBuyTrafficRequest
  )(cted: String): Future[r0.CreateBuyTrafficRequestResponse] = ???

  override def getBuyTrafficRequestStatus(
      respond: r0.GetBuyTrafficRequestStatusResponse.type
  )(trackingId: String)(extracted: String): Future[r0.GetBuyTrafficRequestStatusResponse] = ???
}
