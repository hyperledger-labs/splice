package com.daml.network.wallet.admin.http

import akka.stream.Materializer
import cats.implicits.showInterpolator
import com.daml.ledger.api.v1.CommandsOuterClass
import com.daml.ledger.javaapi.data.{Template, Unit as DUnit}
import com.daml.ledger.javaapi.data.codegen.{ContractId, Exercised, Update}
import com.daml.network.codegen.java.cc.coin.{Coin, LockedCoin}
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.COO_AcceptedAppPayment
import com.daml.network.codegen.java.cn.wallet.install.{
  CoinOperationOutcome,
  coinoperation,
  coinoperationoutcome,
}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.environment.{
  CoinLedgerClient,
  CoinLedgerConnection,
  CoinRetries,
  DedupConfig,
  DedupDuration,
}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.environment.CoinLedgerConnection.CommandId
import com.daml.network.http.v0.{definitions as d0, wallet as v0}
import com.daml.network.http.v0.wallet.WalletResource as r0
import com.daml.network.wallet.{UserWalletManager, UserWalletService}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import com.daml.network.util.{CoinUtil, Contract, Proto}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.ShowUtil.ShowStringSyntax
import io.circe.Json
import io.grpc.{Status, StatusRuntimeException}
import com.google.protobuf.Duration

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class HttpWalletHandler(
    walletManager: UserWalletManager,
    ledgerClient: CoinLedgerClient,
    clock: Clock,
    scanConnection: ScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: CoinRetries,
)(implicit
    mat: Materializer,
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.WalletHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val store = walletManager.store
  private val walletServiceParty: PartyId = store.key.walletServiceParty
  private val validatorParty: PartyId = store.key.validatorParty

  private val connection = ledgerClient.connection()

  override def list(respond: r0.ListResponse.type)()(
      user: String
  ): Future[r0.ListResponse] = withNewTrace(workflowId) { implicit traceContext => span =>
    def _list: Future[r0.ListResponse] = for {
      userStore <- getUserStore(user)
      now = clock.now
      currentRound <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
      acs <- userStore.defaultAcs
      coins <- acs.listContracts(coinCodegen.Coin.COMPANION)
      lockedCoins <- acs.listContracts(
        coinCodegen.LockedCoin.COMPANION
      )
    } yield r0.ListResponseOK(
      d0.ListResponse(
        coins.map(coinToCoinPosition(_, currentRound)).toVector,
        lockedCoins.map(lockedCoinToCoinPosition(_, currentRound)).toVector,
      )
    )
    handleErrors(_list, r0.ListResponseInternalServerError, r0.ListResponseNotFound)
  }

  override def listAcceptedAppPayments(
      respond: v0.WalletResource.ListAcceptedAppPaymentsResponse.type
  )()(user: String): Future[v0.WalletResource.ListAcceptedAppPaymentsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        listContracts(
          walletCodegen.AcceptedAppPayment.COMPANION,
          user,
          d0.ListAcceptedAppPaymentsResponse(_),
        ),
        r0.ListAcceptedAppPaymentsResponseInternalServerError,
        r0.ListAcceptedAppPaymentsResponseNotFound,
      )
    }

  override def listAcceptedTransferOffers(
      respond: v0.WalletResource.ListAcceptedTransferOffersResponse.type
  )()(user: String): Future[v0.WalletResource.ListAcceptedTransferOffersResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        listContracts(
          transferOffersCodegen.AcceptedTransferOffer.COMPANION,
          user,
          d0.ListAcceptedTransferOffersResponse(_),
        ),
        r0.ListAcceptedTransferOffersResponseInternalServerError,
        r0.ListAcceptedTransferOffersResponseNotFound,
      )
    }

  override def listAppPaymentRequests(
      respond: v0.WalletResource.ListAppPaymentRequestsResponse.type
  )()(user: String): Future[v0.WalletResource.ListAppPaymentRequestsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        listContracts(
          walletCodegen.AppPaymentRequest.COMPANION,
          user,
          d0.ListAppPaymentRequestsResponse(_),
        ),
        r0.ListAppPaymentRequestsResponseInternalServerError,
        r0.ListAppPaymentRequestsResponseNotFound,
      )
    }

  override def listAppRewardCoupons(respond: v0.WalletResource.ListAppRewardCouponsResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListAppRewardCouponsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        listContracts(
          coinCodegen.AppRewardCoupon.COMPANION,
          user,
          d0.ListAppRewardCouponsResponse(_),
        ),
        r0.ListAppRewardCouponsResponseInternalServerError,
        r0.ListAppRewardCouponsResponseNotFound,
      )
    }

  override def listSubscriptionInitialPayments(
      respond: v0.WalletResource.ListSubscriptionInitialPaymentsResponse.type
  )()(user: String): Future[v0.WalletResource.ListSubscriptionInitialPaymentsResponse] = {
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        listContracts(
          subsCodegen.SubscriptionInitialPayment.COMPANION,
          user,
          d0.ListSubscriptionInitialPaymentsResponse(_),
        ),
        r0.ListSubscriptionInitialPaymentsResponseInternalServerError,
        r0.ListSubscriptionInitialPaymentsResponseNotFound,
      )
    }
  }

  override def listSubscriptionRequests(
      respond: v0.WalletResource.ListSubscriptionRequestsResponse.type
  )()(user: String): Future[v0.WalletResource.ListSubscriptionRequestsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        listContracts(
          subsCodegen.SubscriptionRequest.COMPANION,
          user,
          d0.ListSubscriptionRequestsResponse(_),
        ),
        r0.ListSubscriptionRequestsResponseInternalServerError,
        r0.ListSubscriptionRequestsResponseNotFound,
      )
    }

  override def listSubscriptions(respond: v0.WalletResource.ListSubscriptionsResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListSubscriptionsResponse] = withNewTrace(workflowId) {
    implicit traceContext => span =>
      def _listSubscriptions: Future[v0.WalletResource.ListSubscriptionsResponse] = for {
        userStore <- getUserStore(user)
        acs <- userStore.defaultAcs
        subscriptions <- acs.listContracts(subsCodegen.Subscription.COMPANION)
        subscriptionIdleStates <- acs.listContracts(
          subsCodegen.SubscriptionIdleState.COMPANION
        )
        subscriptionPayments <- acs.listContracts(
          subsCodegen.SubscriptionPayment.COMPANION
        )
      } yield {
        val mainMap = subscriptions.map(sub => (sub.contractId -> sub.toJson)).toMap
        val idleStates = subscriptionIdleStates.map(state =>
          (state.payload.subscription, d0.SubscriptionState(idle = Some(state.toJson)))
        )
        val payments = subscriptionPayments.map(state =>
          (state.payload.subscription, d0.SubscriptionState(payment = Some(state.toJson)))
        )
        v0.WalletResource.ListSubscriptionsResponseOK(
          d0.ListSubscriptionsResponse(
            (for {
              (mainId, state) <- idleStates ++ payments
              main <- mainMap.get(mainId)
            } yield {
              d0.Subscription(main, state)
            }).toVector
          )
        )
      }
      handleErrors(
        _listSubscriptions,
        r0.ListSubscriptionsResponseInternalServerError,
        r0.ListSubscriptionsResponseNotFound,
      )
  }

  override def listTransferOffers(respond: v0.WalletResource.ListTransferOffersResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListTransferOffersResponse] = {
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        listContracts(
          transferOffersCodegen.TransferOffer.COMPANION,
          user,
          d0.ListTransferOffersResponse(_),
        ),
        r0.ListTransferOffersResponseInternalServerError,
        r0.ListTransferOffersResponseNotFound,
      )
    }
  }

  override def listValidatorRewardCoupons(
      respond: v0.WalletResource.ListValidatorRewardCouponsResponse.type
  )()(user: String): Future[v0.WalletResource.ListValidatorRewardCouponsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        for {
          userStore <- getUserStore(user)
          validatorRewardCoupons <- walletManager.listValidatorRewardCouponsCollectableBy(
            userStore,
            None,
            None,
          )
        } yield d0.ListValidatorRewardCouponsResponse(
          validatorRewardCoupons.map(_.toJson).toVector
        ),
        r0.ListValidatorRewardCouponsResponseInternalServerError,
        r0.ListValidatorRewardCouponsResponseNotFound,
      )
    }

  override def listTransactions(
      respond: v0.WalletResource.ListTransactionsResponse.type
  )(
      request: d0.ListTransactionsRequest
  )(user: String): Future[r0.ListTransactionsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        for {
          userStore <- getUserStore(user)
          beginAfterId = if (request.beginAfterId.exists(_.isEmpty)) None else request.beginAfterId
          transactions <- userStore.listTransactions(beginAfterId, request.pageSize.toInt)
        } yield v0.WalletResource.ListTransactionsResponse.OK(
          d0.ListTransactionsResponse(
            items = transactions.map(_.toJson).toVector
          )
        ),
        r0.ListTransactionsResponseInternalServerError,
        r0.ListTransactionsResponseNotFound,
      )
    }

  private def listContracts[TCid <: ContractId[T], T <: Template, ResponseT](
      templateCompanion: Contract.Companion.Template[TCid, T],
      user: String,
      mkResponse: Vector[d0.Contract] => ResponseT,
  ): Future[ResponseT] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        acs <- userStore.defaultAcs
        contracts <- acs.listContracts(templateCompanion)
      } yield mkResponse(contracts.map(_.toJson).toVector)
    }

  override def selfGrantFeatureAppRight(
      respond: v0.WalletResource.SelfGrantFeatureAppRightResponse.type
  )(request: Option[Json])(
      user: String
  ): Future[r0.SelfGrantFeatureAppRightResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        for {
          coinRules <- scanConnection.getCoinRules()
          result <- exerciseWalletAction((installCid, _) =>
            Future.successful(
              installCid.exerciseWalletAppInstall_FeaturedAppRights_SelfGrant(coinRules.contractId)
            )
          )(user, _.exerciseResult, dislosedContracts = Seq(coinRules.toDisclosedContract))
        } yield d0.SelfGrantFeaturedAppRightResponse(Proto.encodeContractId(result)),
        r0.SelfGrantFeatureAppRightResponseInternalServerError,
        r0.SelfGrantFeatureAppRightResponseNotFound,
      )
    }

  override def acceptTransferOffer(respond: r0.AcceptTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[r0.AcceptTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
              contractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_TransferOffer_Accept(
              requestCid
            )
          )
        })(
          user,
          cid => d0.AcceptTransferOfferResponse(Proto.encodeContractId(cid.exerciseResult)),
        ),
        r0.AcceptTransferOfferResponseInternalServerError,
        r0.AcceptTransferOfferResponseNotFound,
      )
    }

  override def rejectTransferOffer(respond: r0.RejectTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[r0.RejectTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        exerciseWalletAction[r0.RejectTransferOfferResponse, Exercised[DUnit]]((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
              contractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_TransferOffer_Reject(
              requestCid
            )
          )
        })(
          user,
          _ => r0.RejectTransferOfferResponseOK,
        ),
        r0.RejectTransferOfferResponseInternalServerError,
        r0.RejectTransferOfferResponseNotFound,
      )
    }

  override def withdrawTransferOffer(respond: r0.WithdrawTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[r0.WithdrawTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        exerciseWalletAction[r0.WithdrawTransferOfferResponse, Exercised[DUnit]](
          (installCid, _) => {
            val requestCid =
              Proto.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
                contractId
              )
            Future.successful(
              installCid.exerciseWalletAppInstall_TransferOffer_Withdraw(
                requestCid
              )
            )
          }
        )(
          user,
          _ => r0.WithdrawTransferOfferResponseOK,
        ),
        r0.WithdrawTransferOfferResponseInternalServerError,
        r0.WithdrawTransferOfferResponseNotFound,
      )
    }

  override def acceptAppPaymentRequest(
      respond: r0.AcceptAppPaymentRequestResponse.type
  )(contractId: String)(user: String): Future[r0.AcceptAppPaymentRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val requestCid = Proto.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
        contractId
      )
      handleErrors(
        exerciseWalletCoinAction(
          new coinoperation.CO_AppPayment(requestCid),
          user,
          (outcome: COO_AcceptedAppPayment) =>
            r0.AcceptAppPaymentRequestResponse.OK(
              d0.AcceptAppPaymentRequestResponse(
                Proto.encodeContractId(outcome.contractIdValue)
              )
            ),
        ),
        r0.AcceptAppPaymentRequestResponseInternalServerError,
        notFound = r0.AcceptAppPaymentRequestResponseNotFound,
        aborted = Some(r0.AcceptAppPaymentRequestResponseTooManyRequests),
        unavailable = Some(r0.AcceptAppPaymentRequestResponseServiceUnavailable),
        internal = Some(r0.AcceptAppPaymentRequestResponseInternalServerError),
        failedPrecondition = Some(r0.AcceptAppPaymentRequestResponseBadRequest),
      )
    }

  override def rejectAppPaymentRequest(
      respond: r0.RejectAppPaymentRequestResponse.type
  )(contractId: String)(user: String): Future[r0.RejectAppPaymentRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val exercise: Future[r0.RejectAppPaymentRequestResponse] =
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
            contractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_AppPaymentRequest_Reject(
              requestCid
            )
          )
        })(user, _ => r0.RejectAppPaymentRequestResponseOK)

      handleErrors(
        exercise,
        r0.RejectAppPaymentRequestResponseInternalServerError,
        r0.RejectAppPaymentRequestResponseNotFound,
      )
    }

  override def acceptSubscriptionRequest(
      respond: r0.AcceptSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[r0.AcceptSubscriptionRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val requestCid =
        Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
          contractId
        )
      handleErrors(
        exerciseWalletCoinAction(
          new coinoperation.CO_SubscriptionAcceptAndMakeInitialPayment(requestCid),
          user,
          (outcome: coinoperationoutcome.COO_SubscriptionInitialPayment) =>
            d0.AcceptSubscriptionRequestResponse(
              Proto.encodeContractId(outcome.contractIdValue)
            ),
        ),
        r0.AcceptSubscriptionRequestResponseInternalServerError,
        notFound = r0.AcceptSubscriptionRequestResponseNotFound,
        aborted = Some(r0.AcceptSubscriptionRequestResponseTooManyRequests),
        unavailable = Some(r0.AcceptSubscriptionRequestResponseServiceUnavailable),
        internal = Some(r0.AcceptSubscriptionRequestResponseInternalServerError),
        failedPrecondition = Some(r0.AcceptSubscriptionRequestResponseBadRequest),
      )
    }

  override def cancelSubscriptionRequest(
      respond: r0.CancelSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[r0.CancelSubscriptionRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val exercise: Future[r0.CancelSubscriptionRequestResponse] =
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionIdleState.COMPANION)(
              contractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_SubscriptionIdleState_CancelSubscription(
              requestCid
            )
          )
        })(user, _ => r0.CancelSubscriptionRequestResponseOK)

      handleErrors(
        exercise,
        r0.CancelSubscriptionRequestResponseInternalServerError,
        r0.CancelSubscriptionRequestResponseNotFound,
      )
    }

  override def rejectSubscriptionRequest(
      respond: r0.RejectSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[r0.RejectSubscriptionRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val exercise: Future[r0.RejectSubscriptionRequestResponse] =
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
            contractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_SubscriptionRequest_Reject(
              requestCid
            )
          )
        })(user, _ => r0.RejectSubscriptionRequestResponseOK)

      handleErrors(
        exercise,
        r0.RejectSubscriptionRequestResponseInternalServerError,
        r0.RejectSubscriptionRequestResponseNotFound,
      )
    }

  override def getBalance(respond: r0.GetBalanceResponse.type)()(
      user: String
  ): Future[r0.GetBalanceResponse] = withNewTrace(workflowId) { _ => _ =>
    withNewTrace(workflowId) { implicit traceContext => span =>
      handleErrors(
        for {
          userStore <- getUserStore(user)
          acs <- userStore.defaultAcs
          coins <- acs.listContracts(coinCodegen.Coin.COMPANION)
          lockedCoins <- acs.listContracts(coinCodegen.LockedCoin.COMPANION)
          now = clock.now
          currentRound <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
        } yield {
          val unlockedHoldingFees =
            coins.view.map(c => BigDecimal(CoinUtil.holdingFee(c.payload, currentRound))).sum
          val unlockedQty =
            coins.view.map(c => BigDecimal(CoinUtil.currentAmount(c.payload, currentRound))).sum
          val lockedQty =
            lockedCoins.view
              .map(c => BigDecimal(CoinUtil.currentAmount(c.payload.coin, currentRound)))
              .sum

          d0.GetBalanceResponse(
            currentRound,
            Proto.encode(unlockedQty),
            Proto.encode(lockedQty),
            Proto.encode(unlockedHoldingFees),
          )
        },
        r0.GetBalanceResponseInternalServerError,
        r0.GetBalanceResponseNotFound,
      )
    }
  }

  override def createTransferOffer(respond: r0.CreateTransferOfferResponse.type)(
      request: d0.CreateTransferOfferRequest
  )(user: String): Future[r0.CreateTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      def _createTransferOffer = {
        val sender = getUserWallet(user).store.key.endUserParty
        exerciseWalletAction((installCid, _) => {
          val receiver = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          val amount = Proto.tryDecode(Proto.JavaBigDecimal)(request.amount)
          val expiresAt = Proto.tryDecode(Proto.Timestamp)(request.expiresAt)
          Future.successful(
            installCid.exerciseWalletAppInstall_CreateTransferOffer(
              receiver.toProtoPrimitive,
              new PaymentAmount(amount, Currency.CC),
              request.description,
              expiresAt.toInstant,
            )
          )
        })(
          user,
          cid =>
            r0.CreateTransferOfferResponse.OK(
              d0.CreateTransferOfferResponse(Proto.encodeContractId(cid.exerciseResult))
            ),
          dedup = Some(
            (
              CoinLedgerConnection.CommandId(
                "com.daml.network.wallet.createTransferOffer",
                Seq(
                  sender,
                  Proto.tryDecode(Proto.Party)(request.receiverPartyId),
                ),
                request.idempotencyKey,
              ),
              DedupDuration(
                Duration.newBuilder().setSeconds(60 * 60 * 24).build()
              ), // 24 hours, similar to Stripe's API, documented at https://stripe.com/docs/api/idempotent_requests
            )
          ),
        ).recover {
          case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.ALREADY_EXISTS =>
            r0.CreateTransferOfferResponse.Conflict
        }
      }
      handleErrors(
        _createTransferOffer,
        r0.CreateTransferOfferResponseNotFound,
        r0.CreateTransferOfferResponseInternalServerError,
      )
    }

  override def tap(respond: r0.TapResponse.type)(request: d0.TapRequest)(
      user: String
  ): Future[r0.TapResponse] = withNewTrace(workflowId) { implicit traceContext => span =>
    def _tap: Future[r0.TapResponse] = {
      val amount = Proto.tryDecode(Proto.JavaBigDecimal)(request.amount)
      (for {
        userStore <- getUserStore(user)
        result <- exerciseWalletCoinAction(
          new coinoperation.CO_Tap(
            amount
          ),
          user,
          (outcome: coinoperationoutcome.COO_Tap) =>
            d0.TapResponse(Proto.encodeContractId(outcome.contractIdValue)),
        )
      } yield result)
    }
    handleErrors(
      _tap,
      r0.TapResponseInternalServerError,
      notFound = r0.TapResponseNotFound,
      aborted = Some(r0.TapResponseTooManyRequests),
      unavailable = Some(r0.TapResponseServiceUnavailable),
      internal = Some(r0.TapResponseInternalServerError),
      failedPrecondition = Some(r0.TapResponseBadRequest),
    )
  }

  override def userStatus(respond: r0.UserStatusResponse.type)()(
      user: String
  ): Future[r0.UserStatusResponse] = withNewTrace(workflowId) { implicit traceContext => span =>
    handleErrors(
      for {
        optInstall <- store.lookupInstallByName(user)
        hasFeaturedAppRight <- walletManager.lookupUserWallet(user) match {
          case None => Future(false)
          case Some(wallet) =>
            wallet.store.lookupFeaturedAppRight().map(_.isDefined)
        }
      } yield {
        d0.UserStatusResponse(
          partyId = optInstall.fold("")(co => co.payload.endUserParty),
          userOnboarded = optInstall.isDefined,
          hasFeaturedAppRight = hasFeaturedAppRight,
        )
      },
      r0.UserStatusResponseInternalServerError,
      r0.UserStatusResponseNotFound,
    )
  }

  override def cancelFeaturedAppRights(
      respond: r0.CancelFeaturedAppRightsResponse.type
  )()(user: String): Future[r0.CancelFeaturedAppRightsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        featuredAppRight <- userStore.lookupFeaturedAppRight()
        result <- featuredAppRight match {
          case None =>
            logger.info(s"No featured app right found for user ${user} - nothing to cancel")
            Future.successful(r0.CancelFeaturedAppRightsResponseOK)
          case Some(cid) =>
            exerciseWalletAction((installCid, _) => {
              Future.successful(
                installCid.exerciseWalletAppInstall_FeaturedAppRights_Cancel(
                  cid.contractId
                )
              )
            })(user, _ => r0.CancelFeaturedAppRightsResponseOK)
        }
      } yield result
    }

  private def coinToCoinPosition(
      coin: Contract[Coin.ContractId, Coin],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): d0.CoinPosition = {
    d0.CoinPosition(
      coin.toJson,
      round,
      Proto.encode(CoinUtil.holdingFee(coin.payload, round)),
      Proto.encode(CoinUtil.currentAmount(coin.payload, round)),
    )
  }

  private def lockedCoinToCoinPosition(
      lockedCoin: Contract[LockedCoin.ContractId, LockedCoin],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): d0.CoinPosition =
    d0.CoinPosition(
      lockedCoin.toJson,
      round,
      Proto.encode(CoinUtil.holdingFee(lockedCoin.payload.coin, round)),
      Proto.encode(CoinUtil.currentAmount(lockedCoin.payload.coin, round)),
    )

  private[this] def getUserWallet(user: String): UserWalletService =
    walletManager
      .lookupUserWallet(user)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(show"User ${user.singleQuoted}")
        )
      )

  private[this] def getUserStore(user: String): Future[UserWalletStore] =
    Future.successful(getUserWallet(user).store)

  private[this] def getUserTreasury(user: String): Future[TreasuryService] =
    Future.successful(getUserWallet(user).treasury)

  /** Executes a wallet action by calling the `WalletAppInstall_ExecuteBatch` choice on the WalletAppInstall
    * contract of the given end user.
    *
    * The choice is always executed with the wallet service party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Additionally, the validator service party is also a readAs party (workaround for lack
    * of explicit disclosure for CoinRules).
    */
  private def exerciseWalletCoinAction[
      ExpectedCOO <: CoinOperationOutcome: ClassTag,
      R,
  ](
      operation: installCodegen.CoinOperation,
      user: String,
      processResponse: ExpectedCOO => R,
  )(implicit tc: TraceContext): Future[R] =
    for {
      userTreasury <- getUserTreasury(user)
      res <- userTreasury
        .enqueueCoinOperation(operation)
        .map(processCOO[ExpectedCOO, R](processResponse))
    } yield res

  /** Helper function to process a CoinOperationOutcome.
    * Ensures that the outcome is of the expected type and throws an appropriate exception if it isn't.
    */
  private def processCOO[
      ExpectedCOO <: CoinOperationOutcome: ClassTag,
      R,
  ](
      process: ExpectedCOO => R
  )(
      actual: installCodegen.CoinOperationOutcome
  )(implicit tc: TraceContext): R = {
    // I (Arne) did not find a way to avoid ClassTag usage (or passing along a partial function) here
    // For example, passing along the `ExpectedCOO` type to the treasury service doesn't work
    // because inside the TreasuryService we have a Queue of
    // different coin operation outcomes and thus the type of that Queue needs to be CoinOperationOutcome
    // and it can't be the type of a particular coin operation outcome (like `ExpectedCOO`)
    val clazz = implicitly[ClassTag[ExpectedCOO]].runtimeClass
    actual match {
      case result: ExpectedCOO if clazz.isInstance(result) => process(result)
      case failedOperation: coinoperationoutcome.COO_Error =>
        throw new StatusRuntimeException(
          Status.FAILED_PRECONDITION.withDescription(
            s"the coin operation failed with a Daml exception: ${failedOperation}."
          )
        )
      case _ =>
        ErrorUtil.internalErrorGrpc(
          s"expected to receive a coin operation outcome of type $clazz or `COO_Error` but received type ${actual.getClass} with value: $actual"
        )
    }
  }

  /** Executes a wallet action by calling a choice on the WalletInstall contract for the given user.
    *
    * The choice is always executed with the wallet service party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Additionally, the validator service party is also a readAs party (workaround for lack
    * of explicit disclosure for CoinRules).
    *
    * Note: curried syntax helps with type inference
    */
  private def exerciseWalletAction[Response, ChoiceResult](
      getUpdate: (
          installCodegen.WalletAppInstall.ContractId,
          UserWalletStore,
      ) => Future[Update[ChoiceResult]]
  )(
      user: String,
      getResponse: ChoiceResult => Response,
      dedup: Option[(CommandId, DedupConfig)] = None,
      dislosedContracts: Seq[CommandsOuterClass.DisclosedContract] = Seq(),
  )(implicit
      traceContext: TraceContext
  ): Future[Response] = {
    for {
      userStore <- getUserStore(user)
      userParty = userStore.key.endUserParty
      install <- userStore.getInstall()
      update <- getUpdate(install.contractId, userStore)
      domainId <- store.domains.getDomainId(walletManager.globalDomain)
      result <- dedup match {
        case None =>
          connection.submitWithResultNoDedup(
            Seq(walletServiceParty),
            Seq(validatorParty, userParty),
            update,
            domainId,
            dislosedContracts,
          )
        case Some((commandId, dedupConfig)) =>
          connection
            .submitWithResult(
              Seq(walletServiceParty),
              Seq(validatorParty, userParty),
              update,
              commandId,
              dedupConfig,
              domainId,
              dislosedContracts,
            )
      }

    } yield {
      getResponse(result)
    }
  }

  private def handleErrors[R](
      f: => Future[R],
      unexpected: d0.ErrorResponse => R,
      notFound: d0.ErrorResponse => R,
      aborted: Option[d0.ErrorResponse => R] = None,
      unavailable: Option[d0.ErrorResponse => R] = None,
      internal: Option[d0.ErrorResponse => R] = None,
      failedPrecondition: Option[d0.ErrorResponse => R] = None,
  )(implicit traceContext: TraceContext) = {
    val fa = aborted.map(a => handleAborted(f, a)).getOrElse(f)
    val fu = unavailable.map(a => handleUnavailable(fa, a)).getOrElse(fa)
    val fi = internal.map(a => handleInternal(fu, a)).getOrElse(fu)
    val fp = failedPrecondition.map(a => handleFailedPrecondition(fi, a)).getOrElse(fi)
    handleUnexpected(handleNotFound(fp, notFound), unexpected)
  }

  private def handleNotFound[R](f: => Future[R], response: d0.ErrorResponse => R)(implicit
      traceContext: TraceContext
  ): Future[R] =
    handleStatusCode(Status.Code.NOT_FOUND, f, response)
  private def handleAborted[R](f: => Future[R], response: d0.ErrorResponse => R)(implicit
      traceContext: TraceContext
  ): Future[R] =
    handleStatusCode(Status.Code.ABORTED, f, response)
  private def handleUnavailable[R](f: => Future[R], response: d0.ErrorResponse => R)(implicit
      traceContext: TraceContext
  ): Future[R] =
    handleStatusCode(Status.Code.UNAVAILABLE, f, response)
  private def handleInternal[R](f: => Future[R], response: d0.ErrorResponse => R)(implicit
      traceContext: TraceContext
  ): Future[R] =
    handleStatusCode(Status.Code.INTERNAL, f, response)
  private def handleFailedPrecondition[R](
      f: => Future[R],
      response: d0.ErrorResponse => R,
  )(implicit traceContext: TraceContext): Future[R] =
    handleStatusCode(Status.Code.FAILED_PRECONDITION, f, response)

  private def handleStatusCode[R](
      statusCode: Status.Code,
      f: => Future[R],
      response: d0.ErrorResponse => R,
  )(implicit traceContext: TraceContext): Future[R] = {
    f.recover {
      case e: StatusRuntimeException if e.getStatus.getCode == statusCode =>
        logger.info(s"gRPC StatusRuntimeException: ${e.getMessage}", e)
        response(d0.ErrorResponse(e.getStatus.getDescription))
    }
  }

  private def handleUnexpected[R](
      f: => Future[R],
      response: d0.ErrorResponse => R,
  )(implicit traceContext: TraceContext): Future[R] = {
    f.recover { case e: Throwable =>
      logger.error(s"Unexpected exception: ${e.getMessage}", e)
      response(d0.ErrorResponse("An unexpected error occurred."))
    }
  }
}
