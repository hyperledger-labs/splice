package com.daml.network.wallet.admin.http

import akka.stream.Materializer
import com.daml.error.utils.ErrorDetails
import com.daml.error.utils.ErrorDetails.ErrorInfoDetail
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.coin.{Coin, LockedCoin}
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.COO_AcceptedAppPayment
import com.daml.network.codegen.java.cn.wallet.install.{
  CoinOperationOutcome,
  coinoperation,
  coinoperationoutcome,
}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.environment.{CommandPriority, RetryProvider}
import com.daml.network.http.v0.wallet.WalletResource as r0
import com.daml.network.http.v0.{definitions as d0, wallet as v0}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.{Limit, PageLimit}
import com.daml.network.util.{CNNodeUtil, Codec, Contract, DisclosedContracts}
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.protocol.messages.LocalReject
import com.digitalasset.canton.protocol.messages.LocalReject.ConsistencyRejections.InactiveContracts
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.retry.RetryUtil.*
import io.circe.Json
import io.grpc.protobuf.StatusProto
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

class HttpWalletHandler(
    override protected val walletManager: UserWalletManager,
    scanConnection: ScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit
    mat: Materializer,
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.WalletHandler[TracedUser]
    with HttpWalletHandlerUtil {
  protected val workflowId = this.getClass.getSimpleName

  override def list(respond: r0.ListResponse.type)()(
      tuser: TracedUser
  ): Future[r0.ListResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.list") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        currentRound <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
        coins <- userStore.multiDomainAcsStore.listContracts(
          coinCodegen.Coin.COMPANION
        )
        lockedCoins <- userStore.multiDomainAcsStore.listContracts(
          coinCodegen.LockedCoin.COMPANION
        )
      } yield r0.ListResponseOK(
        d0.ListResponse(
          coins.map(c => coinToCoinPosition(c.contract, currentRound)).toVector,
          lockedCoins.map(c => lockedCoinToCoinPosition(c.contract, currentRound)).toVector,
        )
      )
    }
  }

  override def listAcceptedAppPayments(
      respond: v0.WalletResource.ListAcceptedAppPaymentsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListAcceptedAppPaymentsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      walletCodegen.AcceptedAppPayment.COMPANION,
      user,
      d0.ListAcceptedAppPaymentsResponse(_),
    )
  }

  override def listAcceptedTransferOffers(
      respond: v0.WalletResource.ListAcceptedTransferOffersResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListAcceptedTransferOffersResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
      user,
      d0.ListAcceptedTransferOffersResponse(_),
    )
  }

  override def getAppPaymentRequest(respond: r0.GetAppPaymentRequestResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.GetAppPaymentRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.getAppPaymentRequest") { _ => _ =>
      val requestCid =
        Codec.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(contractId)
      for {
        userStore <- getUserStore(user)
        appPaymentRequest <- userStore.getAppPaymentRequest(requestCid)
      } yield r0.GetAppPaymentRequestResponseOK(
        appPaymentRequest.toHttp
      )
    }
  }

  override def listAppPaymentRequests(
      respond: v0.WalletResource.ListAppPaymentRequestsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListAppPaymentRequestsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.listAppPaymentRequests") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        appPaymentRequests <- userStore.listAppPaymentRequests()
      } yield d0.ListAppPaymentRequestsResponse(appPaymentRequests.map(_.toHttp).toVector)
    }
  }

  override def listAppRewardCoupons(respond: v0.WalletResource.ListAppRewardCouponsResponse.type)()(
      tuser: TracedUser
  ): Future[v0.WalletResource.ListAppRewardCouponsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      coinCodegen.AppRewardCoupon.COMPANION,
      user,
      d0.ListAppRewardCouponsResponse(_),
    )
  }

  override def listSubscriptionInitialPayments(
      respond: v0.WalletResource.ListSubscriptionInitialPaymentsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListSubscriptionInitialPaymentsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      subsCodegen.SubscriptionInitialPayment.COMPANION,
      user,
      d0.ListSubscriptionInitialPaymentsResponse(_),
    )
  }

  override def listSubscriptionRequests(
      respond: v0.WalletResource.ListSubscriptionRequestsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListSubscriptionRequestsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.listSubscriptionRequests") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        subRequests <- userStore.listSubscriptionRequests()
      } yield {
        d0.ListSubscriptionRequestsResponse(subRequests.map(_.toHttp).toVector)
      }
    }
  }

  override def listSubscriptions(respond: v0.WalletResource.ListSubscriptionsResponse.type)()(
      tuser: TracedUser
  ): Future[v0.WalletResource.ListSubscriptionsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser

    withSpan(s"$workflowId.listSubscriptions") { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        subscriptions <- userStore.listSubscriptions()
      } yield {
        v0.WalletResource.ListSubscriptionsResponseOK(
          d0.ListSubscriptionsResponse(
            subscriptions.map { subscription =>
              d0.Subscription(
                subscription.subscription.toHttp,
                subscription.state match {
                  case UserWalletStore.SubscriptionIdleState(contract) =>
                    d0.SubscriptionState(idle = Some(contract.toHttp))
                  case UserWalletStore.SubscriptionPaymentState(contract) =>
                    d0.SubscriptionState(payment = Some(contract.toHttp))
                },
              )
            }.toVector
          )
        )
      }
    }
  }

  override def listValidatorRewardCoupons(
      respond: v0.WalletResource.ListValidatorRewardCouponsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListValidatorRewardCouponsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.listValidatorRewardCoupons") { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        validatorRewardCoupons <- walletManager.listValidatorRewardCouponsCollectableBy(
          userStore,
          Limit.DefaultLimit,
          None,
        )
      } yield d0.ListValidatorRewardCouponsResponse(
        validatorRewardCoupons.map(_.toHttp).toVector
      )
    }
  }

  override def listTransactions(
      respond: v0.WalletResource.ListTransactionsResponse.type
  )(
      request: d0.ListTransactionsRequest
  )(tuser: TracedUser): Future[r0.ListTransactionsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.listTransactions") { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        beginAfterId = if (request.beginAfterId.exists(_.isEmpty)) None else request.beginAfterId
        transactions <- userStore.listTransactions(
          beginAfterId,
          PageLimit.tryCreate(request.pageSize.toInt),
        )
      } yield v0.WalletResource.ListTransactionsResponse.OK(
        d0.ListTransactionsResponse(
          items = transactions.map(_.toResponseItem).toVector
        )
      )
    }
  }

  override def selfGrantFeatureAppRight(
      respond: v0.WalletResource.SelfGrantFeatureAppRightResponse.type
  )(request: Option[Json])(
      tuser: TracedUser
  ): Future[r0.SelfGrantFeatureAppRightResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.selfGrantFeatureAppRight") { implicit traceContext => _ =>
      for {
        coinRules <- scanConnection.getCoinRules()
        result <- exerciseWalletAction((installCid, _) =>
          Future.successful(
            installCid
              .exerciseWalletAppInstall_FeaturedAppRights_SelfGrant(
                coinRules.contractId
              )
              .map(_.exerciseResult)
          )
        )(user, dislosedContracts = DisclosedContracts(coinRules))
      } yield d0.SelfGrantFeaturedAppRightResponse(Codec.encodeContractId(result))
    }
  }
  override def acceptTransferOffer(respond: r0.AcceptTransferOfferResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.AcceptTransferOfferResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.acceptTransferOffer") { implicit traceContext => _ =>
      exerciseWalletAction((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
            contractId
          )
        Future.successful(
          installCid
            .exerciseWalletAppInstall_TransferOffer_Accept(requestCid)
            .map { cid =>
              d0.AcceptTransferOfferResponse(
                Codec.encodeContractId(cid.exerciseResult)
              ): r0.AcceptTransferOfferResponse
            }
        )
      })(
        user,
        priority = CommandPriority.High,
      )
    }
  }
  override def rejectTransferOffer(respond: r0.RejectTransferOfferResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.RejectTransferOfferResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.rejectTransferOffer") { implicit traceContext => _ =>
      exerciseWalletAction[r0.RejectTransferOfferResponse]((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
            contractId
          )
        Future.successful(
          installCid
            .exerciseWalletAppInstall_TransferOffer_Reject(
              requestCid
            )
            .map(_ => r0.RejectTransferOfferResponseOK)
        )
      })(
        user
      )
    }
  }

  override def withdrawTransferOffer(respond: r0.WithdrawTransferOfferResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.WithdrawTransferOfferResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.withdrawTransferOffer") { implicit traceContext => _ =>
      exerciseWalletAction[r0.WithdrawTransferOfferResponse]((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
            contractId
          )
        Future.successful(
          installCid
            .exerciseWalletAppInstall_TransferOffer_Withdraw(
              requestCid,
              // This is used for withdrawn_reason in the status response.
              // In the future, it could come from the request payload.
              "Withdrawn by sender",
            )
            .map(_ => r0.WithdrawTransferOfferResponseOK)
        )
      })(
        user
      )
    }
  }

  override def acceptAppPaymentRequest(
      respond: r0.AcceptAppPaymentRequestResponse.type
  )(contractId: String)(tuser: TracedUser): Future[r0.AcceptAppPaymentRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.acceptAppPaymentRequest") { _ => _ =>
      val requestCid = Codec.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
        contractId
      )
      retryProvider.retryForClientCalls(
        "Accept app payment request",
        exerciseWalletCoinAction(
          new coinoperation.CO_AppPayment(requestCid),
          user,
          (outcome: COO_AcceptedAppPayment) =>
            r0.AcceptAppPaymentRequestResponse.OK(
              d0.AcceptAppPaymentRequestResponse(
                Codec.encodeContractId(outcome.contractIdValue)
              )
            ),
        ),
        logger,
      )
    }
  }

  override def rejectAppPaymentRequest(
      respond: r0.RejectAppPaymentRequestResponse.type
  )(contractId: String)(tuser: TracedUser): Future[r0.RejectAppPaymentRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.rejectAppPaymentRequest") { implicit traceContext => _ =>
      val requestCid = Codec.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
        contractId
      )
      retryProvider.retryForClientCalls(
        "Reject app payment request",
        exerciseWalletAction((installCid, _) => {
          Future.successful(
            installCid
              .exerciseWalletAppInstall_AppPaymentRequest_Reject(requestCid)
              .map(_ => r0.RejectAppPaymentRequestResponseOK)
          )
        })(user),
        logger,
      )
    }
  }

  override def getSubscriptionRequest(respond: r0.GetSubscriptionRequestResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.GetSubscriptionRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.getSubscriptionRequest") { implicit traceContext => _ =>
      val requestCid =
        Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(contractId)
      for {
        userStore <- getUserStore(user)
        subscriptionRequest <- userStore.getSubscriptionRequest(requestCid)
      } yield r0.GetSubscriptionRequestResponseOK(
        subscriptionRequest.toHttp
      )
    }
  }

  override def acceptSubscriptionRequest(
      respond: r0.AcceptSubscriptionRequestResponse.type
  )(contractId: String)(tuser: TracedUser): Future[r0.AcceptSubscriptionRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.acceptSubscriptionRequest") { implicit traceContext => _ =>
      val requestCid =
        Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
          contractId
        )
      retryProvider.retryForClientCalls(
        "Accept subscription and make initial payment",
        exerciseWalletCoinAction(
          new coinoperation.CO_SubscriptionAcceptAndMakeInitialPayment(requestCid),
          user,
          (outcome: coinoperationoutcome.COO_SubscriptionInitialPayment) =>
            d0.AcceptSubscriptionRequestResponse(
              Codec.encodeContractId(outcome.contractIdValue)
            ),
        ),
        logger,
      )
    }
  }

  override def cancelSubscriptionRequest(
      respond: r0.CancelSubscriptionRequestResponse.type
  )(contractId: String)(tuser: TracedUser): Future[r0.CancelSubscriptionRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.cancelSubscriptionRequest") { implicit traceContext => _ =>
      exerciseWalletAction((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionIdleState.COMPANION)(
            contractId
          )
        Future.successful(
          installCid
            .exerciseWalletAppInstall_SubscriptionIdleState_CancelSubscription(
              requestCid
            )
            .map(_ => r0.CancelSubscriptionRequestResponseOK)
        )
      })(user)
    }
  }

  override def rejectSubscriptionRequest(
      respond: r0.RejectSubscriptionRequestResponse.type
  )(contractId: String)(tuser: TracedUser): Future[r0.RejectSubscriptionRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.rejectSubscriptionRequest") { implicit traceContext => _ =>
      exerciseWalletAction((installCid, _) => {
        val requestCid = Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
          contractId
        )
        Future.successful(
          installCid
            .exerciseWalletAppInstall_SubscriptionRequest_Reject(requestCid)
            .map(_ => r0.RejectSubscriptionRequestResponseOK)
        )
      })(user)
    }
  }

  override def getBalance(respond: r0.GetBalanceResponse.type)()(
      tuser: TracedUser
  ): Future[r0.GetBalanceResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.getBalance") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        coins <- userStore.multiDomainAcsStore.listContracts(
          coinCodegen.Coin.COMPANION
        )
        lockedCoins <- userStore.multiDomainAcsStore.listContracts(
          coinCodegen.LockedCoin.COMPANION
        )
        currentRound <- scanConnection
          .getLatestOpenMiningRound()
          .map(_.payload.round.number)
      } yield {
        val unlockedHoldingFees =
          coins.view
            .map(c => BigDecimal(CNNodeUtil.holdingFee(c.payload, currentRound)))
            .sum
        val unlockedQty =
          coins.view
            .map(c => BigDecimal(CNNodeUtil.currentAmount(c.payload, currentRound)))
            .sum
        val lockedQty =
          lockedCoins.view
            .map(c => BigDecimal(CNNodeUtil.currentAmount(c.payload.coin, currentRound)))
            .sum

        d0.GetBalanceResponse(
          currentRound,
          Codec.encode(unlockedQty),
          Codec.encode(lockedQty),
          Codec.encode(unlockedHoldingFees),
        )
      }
    }
  }
  override def tap(respond: r0.TapResponse.type)(request: d0.TapRequest)(
      tuser: TracedUser
  ): Future[r0.TapResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.tap") { _ => _ =>
      val amount = Codec.tryDecode(Codec.JavaBigDecimal)(request.amount)
      // Note that we're passing a custom retryable here because blindly retrying
      // on failed taps would be incorrect (tap is not idempotent).
      retryProvider.retryForClientCalls(
        "Tap",
        for {
          result <- exerciseWalletCoinAction(
            new coinoperation.CO_Tap(
              amount
            ),
            user,
            (outcome: coinoperationoutcome.COO_Tap) =>
              d0.TapResponse(Codec.encodeContractId(outcome.contractIdValue)),
          )
        } yield result,
        logger,
        HttpWalletHandler.TapRetryable(_),
      )
    }
  }

  override def userStatus(respond: r0.UserStatusResponse.type)()(
      tuser: TracedUser
  ): Future[r0.UserStatusResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.userStatus") { implicit traceContext => _ =>
      val optWallet = walletManager.lookupUserWallet(user)
      for {
        hasFeaturedAppRight <- optWallet match {
          case None => Future(false)
          case Some(wallet) =>
            wallet.store.lookupFeaturedAppRight().map(_.isDefined)
        }
        optInstall <- optWallet match {
          case None =>
            Future(None)
          case Some(w) => w.store.lookupInstall()
        }
      } yield {
        d0.UserStatusResponse(
          partyId = optWallet.fold("")(_.store.key.endUserParty.toProtoPrimitive),
          userOnboarded = optWallet.isDefined,
          userWalletInstalled = optInstall.isDefined,
          hasFeaturedAppRight = hasFeaturedAppRight,
        )
      }
    }
  }

  override def cancelFeaturedAppRights(
      respond: r0.CancelFeaturedAppRightsResponse.type
  )()(tuser: TracedUser): Future[r0.CancelFeaturedAppRightsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.cancelFeaturedAppRights") { implicit traceContext => _ =>
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
                installCid
                  .exerciseWalletAppInstall_FeaturedAppRights_Cancel(cid.contractId)
                  .map(_ => r0.CancelFeaturedAppRightsResponseOK)
              )
            })(user)
        }
      } yield result
    }
  }

  private def coinToCoinPosition(
      coin: Contract[Coin.ContractId, Coin],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): d0.CoinPosition = {
    d0.CoinPosition(
      coin.toHttp,
      round,
      Codec.encode(CNNodeUtil.holdingFee(coin.payload, round)),
      Codec.encode(CNNodeUtil.currentAmount(coin.payload, round)),
    )
  }

  private def lockedCoinToCoinPosition(
      lockedCoin: Contract[LockedCoin.ContractId, LockedCoin],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): d0.CoinPosition =
    d0.CoinPosition(
      lockedCoin.toHttp,
      round,
      Codec.encode(CNNodeUtil.holdingFee(lockedCoin.payload.coin, round)),
      Codec.encode(CNNodeUtil.currentAmount(lockedCoin.payload.coin, round)),
    )

  private[this] def getUserTreasury(user: String): Future[TreasuryService] =
    Future.successful(getUserWallet(user).treasury)

  /** Executes a wallet action by calling the `WalletAppInstall_ExecuteBatch` choice on the WalletAppInstall
    * contract of the given end user.
    *
    * The choice is always executed with the validator party as the submitter, and the
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
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"the coin operation failed with a Daml exception: ${failedOperation}."
          )
          .asRuntimeException()
      case _ =>
        ErrorUtil.internalErrorGrpc(
          s"expected to receive a coin operation outcome of type $clazz or `COO_Error` but received type ${actual.getClass} with value: $actual"
        )
    }
  }
}

object HttpWalletHandler {
  case class TapRetryable(operationName: String) extends ExceptionRetryable {
    override def retryOK(outcome: Try[_], logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = outcome match {
      case Failure(ex: io.grpc.StatusRuntimeException) if isInactiveContract(ex) =>
        logger.info(
          s"The operation $operationName failed with a ${InactiveContracts.id} error $ex."
        )
        TransientErrorKind
      case Failure(ex: io.grpc.StatusRuntimeException) if isLockedContract(ex) =>
        logger.info(
          s"The operation $operationName failed with a ${LocalReject.ConsistencyRejections.LockedContracts.id} error $ex."
        )
        TransientErrorKind
      // TODO(#3933) This is temporarily added to retry on INVALID_ARGUMENT errors when submitting transactions during topology change.
      case Failure(ex: io.grpc.StatusRuntimeException) if isNonspecificInvalidArgument(ex) =>
        logger.info(
          s"The operation $operationName failed with a nonspecifc INVALID_ARGUMENT error $ex."
        )
        TransientErrorKind
      case Failure(ex) =>
        logThrowable(ex, logger)
        FatalErrorKind
      case Success(_) => NoErrorKind
    }
  }

  private def isInactiveContract(ex: io.grpc.StatusRuntimeException): Boolean = {
    ex.getStatus.getCode == Status.Code.NOT_FOUND &&
    ErrorDetails.from(StatusProto.fromThrowable(ex)).exists {
      case ErrorInfoDetail(InactiveContracts.id, _) => true
      case _ => false
    }
  }

  private def isLockedContract(ex: io.grpc.StatusRuntimeException): Boolean = {
    ex.getStatus.getCode == Status.Code.ABORTED &&
    ErrorDetails.from(StatusProto.fromThrowable(ex)).exists {
      case ErrorInfoDetail(LocalReject.ConsistencyRejections.LockedContracts.id, _) => true
      case _ => false
    }
  }

  private def isNonspecificInvalidArgument(ex: io.grpc.StatusRuntimeException): Boolean = {
    ex.getStatus.getCode == Status.Code.INVALID_ARGUMENT && ex.getStatus.getDescription.contains(
      "An error occurred. Please contact the operator and inquire about the request"
    )
  }
}
