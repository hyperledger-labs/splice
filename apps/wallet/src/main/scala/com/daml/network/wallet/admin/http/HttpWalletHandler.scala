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
import com.daml.network.environment.{CNLedgerClient, CNLedgerConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.environment.CNLedgerConnection.CommandId
import com.daml.network.http.v0.{definitions as d0, wallet as v0}
import com.daml.network.http.v0.wallet.WalletResource as r0
import com.daml.network.wallet.{UserWalletManager, UserWalletService}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import com.daml.network.util.{Codec, CNNodeUtil, Contract}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.environment.ledger.api.{DedupConfig, DedupDuration}
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
    ledgerClient: CNLedgerClient,
    clock: Clock,
    scanConnection: ScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit
    mat: Materializer,
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.WalletHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val store = walletManager.store
  private val walletServiceParty: PartyId = store.walletKey.walletServiceParty
  private val validatorParty: PartyId = store.walletKey.validatorParty

  private val connection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)

  override def list(respond: r0.ListResponse.type)()(
      user: String
  ): Future[r0.ListResponse] = withNewTrace(workflowId) { implicit traceContext => _ =>
    for {
      userStore <- getUserStore(user)
      currentRound <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
      coins <- userStore.multiDomainAcsStore.listContracts(coinCodegen.Coin.COMPANION)
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

  override def listAcceptedAppPayments(
      respond: v0.WalletResource.ListAcceptedAppPaymentsResponse.type
  )()(user: String): Future[v0.WalletResource.ListAcceptedAppPaymentsResponse] =
    listContracts(
      walletCodegen.AcceptedAppPayment.COMPANION,
      user,
      d0.ListAcceptedAppPaymentsResponse(_),
    )

  override def listAcceptedTransferOffers(
      respond: v0.WalletResource.ListAcceptedTransferOffersResponse.type
  )()(user: String): Future[v0.WalletResource.ListAcceptedTransferOffersResponse] =
    listContracts(
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
      user,
      d0.ListAcceptedTransferOffersResponse(_),
    )

  override def getAppPaymentRequest(respond: r0.GetAppPaymentRequestResponse.type)(
      contractId: String
  )(user: String): Future[r0.GetAppPaymentRequestResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val requestCid =
        Codec.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(contractId)
      for {
        userStore <- getUserStore(user)
        appPaymentRequest <- userStore.getAppPaymentRequest(requestCid)
      } yield r0.GetAppPaymentRequestResponseOK(
        d0.AppPaymentRequest(
          appPaymentRequest.appPaymentRequest.toJson,
          appPaymentRequest.deliveryOffer.toJson,
        )
      )
    }
  }

  override def listAppPaymentRequests(
      respond: v0.WalletResource.ListAppPaymentRequestsResponse.type
  )()(user: String): Future[v0.WalletResource.ListAppPaymentRequestsResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        appPaymentRequests <- userStore.listAppPaymentRequests
      } yield d0.ListAppPaymentRequestsResponse(appPaymentRequests.map { appPaymentRequest =>
        d0.AppPaymentRequest(
          appPaymentRequest.appPaymentRequest.toJson,
          appPaymentRequest.deliveryOffer.toJson,
        )
      }.toVector)
    }
  }

  override def listAppRewardCoupons(respond: v0.WalletResource.ListAppRewardCouponsResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListAppRewardCouponsResponse] =
    listContracts(
      coinCodegen.AppRewardCoupon.COMPANION,
      user,
      d0.ListAppRewardCouponsResponse(_),
    )

  override def listSubscriptionInitialPayments(
      respond: v0.WalletResource.ListSubscriptionInitialPaymentsResponse.type
  )()(user: String): Future[v0.WalletResource.ListSubscriptionInitialPaymentsResponse] = {
    listContracts(
      subsCodegen.SubscriptionInitialPayment.COMPANION,
      user,
      d0.ListSubscriptionInitialPaymentsResponse(_),
    )
  }

  override def listSubscriptionRequests(
      respond: v0.WalletResource.ListSubscriptionRequestsResponse.type
  )()(user: String): Future[v0.WalletResource.ListSubscriptionRequestsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        subRequests <- userStore.listSubscriptionRequests()
      } yield {
        d0.ListSubscriptionRequestsResponse(subRequests.map { subRequest =>
          d0.SubscriptionRequest(
            subRequest.subscription.toJson,
            subRequest.context.toJson,
          )
        }.toVector)
      }
    }

  override def listSubscriptions(respond: v0.WalletResource.ListSubscriptionsResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListSubscriptionsResponse] = withNewTrace(workflowId) {
    implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        subscriptions <- userStore.listSubscriptions()
      } yield {
        v0.WalletResource.ListSubscriptionsResponseOK(
          d0.ListSubscriptionsResponse(
            subscriptions.map { subscription =>
              d0.Subscription(
                subscription.subscription.toJson,
                subscription.state match {
                  case UserWalletStore.SubscriptionIdleState(contract) =>
                    d0.SubscriptionState(idle = Some(contract.toJson))
                  case UserWalletStore.SubscriptionPaymentState(contract) =>
                    d0.SubscriptionState(payment = Some(contract.toJson))
                },
                subscription.context.toJson,
              )
            }.toVector
          )
        )
      }
  }

  override def listTransferOffers(respond: v0.WalletResource.ListTransferOffersResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListTransferOffersResponse] = {
    listContracts(
      transferOffersCodegen.TransferOffer.COMPANION,
      user,
      d0.ListTransferOffersResponse(_),
    )
  }

  override def listValidatorRewardCoupons(
      respond: v0.WalletResource.ListValidatorRewardCouponsResponse.type
  )()(user: String): Future[v0.WalletResource.ListValidatorRewardCouponsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        validatorRewardCoupons <- walletManager.listValidatorRewardCouponsCollectableBy(
          userStore,
          None,
          None,
        )
      } yield d0.ListValidatorRewardCouponsResponse(
        validatorRewardCoupons.map(_.toJson).toVector
      )
    }

  override def listTransactions(
      respond: v0.WalletResource.ListTransactionsResponse.type
  )(
      request: d0.ListTransactionsRequest
  )(user: String): Future[r0.ListTransactionsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        beginAfterId = if (request.beginAfterId.exists(_.isEmpty)) None else request.beginAfterId
        transactions <- userStore.listTransactions(beginAfterId, request.pageSize.toInt)
      } yield v0.WalletResource.ListTransactionsResponse.OK(
        d0.ListTransactionsResponse(
          items = transactions.map(_.toResonseItem).toVector
        )
      )
    }

  private def listContracts[TCid <: ContractId[T], T <: Template, ResponseT](
      templateCompanion: Contract.Companion.Template[TCid, T],
      user: String,
      mkResponse: Vector[d0.Contract] => ResponseT,
  ): Future[ResponseT] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        userStore <- getUserStore(user)
        contracts <- userStore.multiDomainAcsStore.listContracts(templateCompanion)
      } yield mkResponse(contracts.map(_.contract.toJson).toVector)
    }

  override def selfGrantFeatureAppRight(
      respond: v0.WalletResource.SelfGrantFeatureAppRightResponse.type
  )(request: Option[Json])(
      user: String
  ): Future[r0.SelfGrantFeatureAppRightResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        coinRules <- scanConnection.getCoinRules()
        result <- exerciseWalletAction((installCid, _) =>
          Future.successful(
            installCid.exerciseWalletAppInstall_FeaturedAppRights_SelfGrant(coinRules.contractId)
          )
        )(user, _.exerciseResult, dislosedContracts = Seq(coinRules.toDisclosedContract))
      } yield d0.SelfGrantFeaturedAppRightResponse(Codec.encodeContractId(result))
    }

  override def acceptTransferOffer(respond: r0.AcceptTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[r0.AcceptTransferOfferResponse] =
    withNewTrace(workflowId) { _ => _ =>
      exerciseWalletAction((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
            contractId
          )
        Future.successful(
          installCid.exerciseWalletAppInstall_TransferOffer_Accept(
            requestCid
          )
        )
      })(
        user,
        cid => d0.AcceptTransferOfferResponse(Codec.encodeContractId(cid.exerciseResult)),
      )
    }

  override def rejectTransferOffer(respond: r0.RejectTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[r0.RejectTransferOfferResponse] =
    withNewTrace(workflowId) { _ => _ =>
      exerciseWalletAction[r0.RejectTransferOfferResponse, Exercised[DUnit]]((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
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
      )
    }

  override def withdrawTransferOffer(respond: r0.WithdrawTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[r0.WithdrawTransferOfferResponse] =
    withNewTrace(workflowId) { _ => _ =>
      exerciseWalletAction[r0.WithdrawTransferOfferResponse, Exercised[DUnit]]((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
            contractId
          )
        Future.successful(
          installCid.exerciseWalletAppInstall_TransferOffer_Withdraw(
            requestCid
          )
        )
      })(
        user,
        _ => r0.WithdrawTransferOfferResponseOK,
      )
    }

  override def acceptAppPaymentRequest(
      respond: r0.AcceptAppPaymentRequestResponse.type
  )(contractId: String)(user: String): Future[r0.AcceptAppPaymentRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
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

  override def rejectAppPaymentRequest(
      respond: r0.RejectAppPaymentRequestResponse.type
  )(contractId: String)(user: String): Future[r0.RejectAppPaymentRequestResponse] =
    withNewTrace(workflowId) { _ => _ =>
      exerciseWalletAction((installCid, _) => {
        val requestCid = Codec.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
          contractId
        )
        Future.successful(
          installCid.exerciseWalletAppInstall_AppPaymentRequest_Reject(
            requestCid
          )
        )
      })(user, _ => r0.RejectAppPaymentRequestResponseOK)
    }

  override def getSubscriptionRequest(respond: r0.GetSubscriptionRequestResponse.type)(
      contractId: String
  )(user: String): Future[r0.GetSubscriptionRequestResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val requestCid =
        Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(contractId)
      for {
        userStore <- getUserStore(user)
        subscriptionRequest <- userStore.getSubscriptionRequest(requestCid)
      } yield r0.GetSubscriptionRequestResponseOK(
        d0.SubscriptionRequest(
          subscriptionRequest.subscription.toJson,
          subscriptionRequest.context.toJson,
        )
      )
    }
  }

  override def acceptSubscriptionRequest(
      respond: r0.AcceptSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[r0.AcceptSubscriptionRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
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

  override def cancelSubscriptionRequest(
      respond: r0.CancelSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[r0.CancelSubscriptionRequestResponse] =
    withNewTrace(workflowId) { _ => _ =>
      exerciseWalletAction((installCid, _) => {
        val requestCid =
          Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionIdleState.COMPANION)(
            contractId
          )
        Future.successful(
          installCid.exerciseWalletAppInstall_SubscriptionIdleState_CancelSubscription(
            requestCid
          )
        )
      })(user, _ => r0.CancelSubscriptionRequestResponseOK)
    }

  override def rejectSubscriptionRequest(
      respond: r0.RejectSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[r0.RejectSubscriptionRequestResponse] =
    withNewTrace(workflowId) { _ => _ =>
      exerciseWalletAction((installCid, _) => {
        val requestCid = Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
          contractId
        )
        Future.successful(
          installCid.exerciseWalletAppInstall_SubscriptionRequest_Reject(
            requestCid
          )
        )
      })(user, _ => r0.RejectSubscriptionRequestResponseOK)
    }

  override def getBalance(respond: r0.GetBalanceResponse.type)()(
      user: String
  ): Future[r0.GetBalanceResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      for {
        userStore <- getUserStore(user)
        coins <- userStore.multiDomainAcsStore.listContracts(coinCodegen.Coin.COMPANION)
        lockedCoins <- userStore.multiDomainAcsStore.listContracts(coinCodegen.LockedCoin.COMPANION)
        now = clock.now
        currentRound <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
      } yield {
        val unlockedHoldingFees =
          coins.view
            .map(c => BigDecimal(CNNodeUtil.holdingFee(c.contract.payload, currentRound)))
            .sum
        val unlockedQty =
          coins.view
            .map(c => BigDecimal(CNNodeUtil.currentAmount(c.contract.payload, currentRound)))
            .sum
        val lockedQty =
          lockedCoins.view
            .map(c => BigDecimal(CNNodeUtil.currentAmount(c.contract.payload.coin, currentRound)))
            .sum

        d0.GetBalanceResponse(
          currentRound,
          Codec.encode(unlockedQty),
          Codec.encode(lockedQty),
          Codec.encode(unlockedHoldingFees),
        )
      }
    }

  override def createTransferOffer(respond: r0.CreateTransferOfferResponse.type)(
      request: d0.CreateTransferOfferRequest
  )(user: String): Future[r0.CreateTransferOfferResponse] =
    withNewTrace(workflowId) { _ => _ =>
      val sender = getUserWallet(user).store.key.endUserParty
      exerciseWalletAction((installCid, _) => {
        val receiver = Codec.tryDecode(Codec.Party)(request.receiverPartyId)
        val amount = Codec.tryDecode(Codec.JavaBigDecimal)(request.amount)
        val expiresAt = Codec.tryDecode(Codec.Timestamp)(request.expiresAt)
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
            d0.CreateTransferOfferResponse(Codec.encodeContractId(cid.exerciseResult))
          ),
        dedup = Some(
          (
            CNLedgerConnection.CommandId(
              "com.daml.network.wallet.createTransferOffer",
              Seq(
                sender,
                Codec.tryDecode(Codec.Party)(request.receiverPartyId),
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

  override def tap(respond: r0.TapResponse.type)(request: d0.TapRequest)(
      user: String
  ): Future[r0.TapResponse] = withNewTrace(workflowId) { implicit traceContext => _ =>
    val amount = Codec.tryDecode(Codec.JavaBigDecimal)(request.amount)
    (for {
      result <- exerciseWalletCoinAction(
        new coinoperation.CO_Tap(
          amount
        ),
        user,
        (outcome: coinoperationoutcome.COO_Tap) =>
          d0.TapResponse(Codec.encodeContractId(outcome.contractIdValue)),
      )
    } yield result)
  }

  override def userStatus(respond: r0.UserStatusResponse.type)()(
      user: String
  ): Future[r0.UserStatusResponse] = withNewTrace(workflowId) { _ => _ =>
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

  override def cancelFeaturedAppRights(
      respond: r0.CancelFeaturedAppRightsResponse.type
  )()(user: String): Future[r0.CancelFeaturedAppRightsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
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
      Codec.encode(CNNodeUtil.holdingFee(coin.payload, round)),
      Codec.encode(CNNodeUtil.currentAmount(coin.payload, round)),
    )
  }

  private def lockedCoinToCoinPosition(
      lockedCoin: Contract[LockedCoin.ContractId, LockedCoin],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): d0.CoinPosition =
    d0.CoinPosition(
      lockedCoin.toJson,
      round,
      Codec.encode(CNNodeUtil.holdingFee(lockedCoin.payload.coin, round)),
      Codec.encode(CNNodeUtil.currentAmount(lockedCoin.payload.coin, round)),
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
}
