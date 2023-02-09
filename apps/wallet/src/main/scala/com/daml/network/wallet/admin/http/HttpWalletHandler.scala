package com.daml.network.wallet.admin.http

import akka.stream.Materializer
import cats.implicits.showInterpolator
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  ContractId,
  Update,
  Contract as CodegenContract,
}
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
import com.daml.network.http.v0.{definitions, wallet as v0}
import com.daml.network.wallet.{UserWalletManager, UserWalletService}
import com.daml.network.wallet.v0 as protov0
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import com.daml.network.util.{CoinUtil, Contract, Proto}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.http.v0.wallet.WalletResource
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

  override def listAcceptedAppPayments(
      respond: v0.WalletResource.ListAcceptedAppPaymentsResponse.type
  )()(user: String): Future[v0.WalletResource.ListAcceptedAppPaymentsResponse] =
    listContracts(
      walletCodegen.AcceptedAppPayment.COMPANION,
      user,
      definitions.ListAcceptedAppPaymentsResponse(_),
    )

  override def selfGrantFeatureAppRight(
      respond: v0.WalletResource.SelfGrantFeatureAppRightResponse.type
  )(request: Option[Json])(
      user: String
  ): Future[v0.WalletResource.SelfGrantFeatureAppRightResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        coinRules <- scanConnection.getCoinRules()
        result <- exerciseWalletAction((installCid, _) =>
          Future.successful(
            installCid.exerciseWalletAppInstall_FeaturedAppRights_SelfGrant(coinRules.contractId)
          )
        )(user, _.exerciseResult)
      } yield definitions.SelfGrantFeaturedAppRightResponse(Proto.encodeContractId(result))
    }

  override def acceptTransferOffer(respond: v0.WalletResource.AcceptTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[v0.WalletResource.AcceptTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
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
        cid => protov0.AcceptTransferOfferResponse(Proto.encodeContractId(cid.exerciseResult)),
      ).map { pv0 =>
        definitions.AcceptTransferOfferResponse(pv0.acceptedOfferContractId)
      }
    }

  override def rejectTransferOffer(respond: v0.WalletResource.RejectTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[v0.WalletResource.RejectTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      exerciseWalletAction((installCid, _) => {
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
        _ => WalletResource.RejectTransferOfferResponseOK,
      )
    }

  override def withdrawTransferOffer(respond: v0.WalletResource.WithdrawTransferOfferResponse.type)(
      contractId: String
  )(user: String): Future[v0.WalletResource.WithdrawTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      exerciseWalletAction((installCid, _) => {
        val requestCid =
          Proto.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
            contractId
          )
        Future.successful(
          installCid.exerciseWalletAppInstall_TransferOffer_Withdraw(
            requestCid
          )
        )
      })(
        user,
        _ => WalletResource.WithdrawTransferOfferResponseOK,
      )
    }

  override def acceptAppPaymentRequest(
      respond: v0.WalletResource.AcceptAppPaymentRequestResponse.type
  )(contractId: String)(user: String): Future[v0.WalletResource.AcceptAppPaymentRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val requestCid = Proto.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
        contractId
      )
      handleContractIdNotFound(
        "acceptAppPaymentRequest",
        contractId,
        v0.WalletResource.AcceptAppPaymentRequestResponseNotFound,
        exerciseWalletCoinAction(
          new coinoperation.CO_AppPayment(requestCid),
          user,
          (outcome: COO_AcceptedAppPayment) =>
            protov0.AcceptAppPaymentRequestResponse(
              Proto.encodeContractId(outcome.contractIdValue)
            ),
        ).map { pv0 =>
          v0.WalletResource.AcceptAppPaymentRequestResponse.OK(
            definitions.AcceptAppPaymentRequestResponse(pv0.acceptedPaymentContractId)
          )
        },
      )
    }
  private def handleContractIdNotFound[T, N](
      api: String,
      contractId: String,
      notFoundResponse: T,
      rpcCall: Future[T],
  )(implicit traceContext: TraceContext): Future[T] = {
    rpcCall.recover {
      case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.NOT_FOUND =>
        logger.error(s"${api}: contract_id not found: $contractId", e)
        notFoundResponse
    }
  }
  override def rejectAppPaymentRequest(
      respond: v0.WalletResource.RejectAppPaymentRequestResponse.type
  )(contractId: String)(user: String): Future[v0.WalletResource.RejectAppPaymentRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val exercise: Future[v0.WalletResource.RejectAppPaymentRequestResponse] =
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
            contractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_AppPaymentRequest_Reject(
              requestCid
            )
          )
        })(user, _ => v0.WalletResource.RejectAppPaymentRequestResponseOK)

      handleContractIdNotFound(
        "rejectAppPaymentRequest",
        contractId,
        v0.WalletResource.RejectAppPaymentRequestResponseNotFound,
        exercise,
      )
    }

  override def acceptSubscriptionRequest(
      respond: v0.WalletResource.AcceptSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[v0.WalletResource.AcceptSubscriptionRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val requestCid =
        Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
          contractId
        )
      handleContractIdNotFound(
        "acceptSubscriptionRequest",
        contractId,
        v0.WalletResource.AcceptSubscriptionRequestResponseNotFound,
        exerciseWalletCoinAction(
          new coinoperation.CO_SubscriptionAcceptAndMakeInitialPayment(requestCid),
          user,
          (outcome: coinoperationoutcome.COO_SubscriptionInitialPayment) =>
            protov0.AcceptSubscriptionRequestResponse(
              Proto.encodeContractId(outcome.contractIdValue)
            ),
        ).map { pv0 =>
          definitions.AcceptSubscriptionRequestResponse(pv0.initialPaymentContractId)
        },
      )
    }

  override def cancelSubscriptionRequest(
      respond: v0.WalletResource.CancelSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[v0.WalletResource.CancelSubscriptionRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val exercise: Future[v0.WalletResource.CancelSubscriptionRequestResponse] =
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
        })(user, _ => WalletResource.CancelSubscriptionRequestResponseOK)

      handleContractIdNotFound(
        "cancelSubscriptionRequest",
        contractId,
        v0.WalletResource.CancelSubscriptionRequestResponseNotFound,
        exercise,
      )
    }

  override def rejectSubscriptionRequest(
      respond: v0.WalletResource.RejectSubscriptionRequestResponse.type
  )(contractId: String)(user: String): Future[v0.WalletResource.RejectSubscriptionRequestResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val exercise: Future[v0.WalletResource.RejectSubscriptionRequestResponse] =
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
            contractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_SubscriptionRequest_Reject(
              requestCid
            )
          )
        })(user, _ => WalletResource.RejectSubscriptionRequestResponseOK)

      handleContractIdNotFound(
        "rejectSubscriptionRequest",
        contractId,
        v0.WalletResource.RejectSubscriptionRequestResponseNotFound,
        exercise,
      )
    }

  override def listAcceptedTransferOffers(
      respond: v0.WalletResource.ListAcceptedTransferOffersResponse.type
  )()(user: String): Future[v0.WalletResource.ListAcceptedTransferOffersResponse] =
    listContracts(
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
      user,
      definitions.ListAcceptedTransferOffersResponse(_),
    )

  override def listAppPaymentRequests(
      respond: v0.WalletResource.ListAppPaymentRequestsResponse.type
  )()(user: String): Future[v0.WalletResource.ListAppPaymentRequestsResponse] =
    listContracts(
      walletCodegen.AppPaymentRequest.COMPANION,
      user,
      definitions.ListAppPaymentRequestsResponse(_),
    )

  override def listAppRewardCoupons(respond: v0.WalletResource.ListAppRewardCouponsResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListAppRewardCouponsResponse] =
    listContracts(
      coinCodegen.AppRewardCoupon.COMPANION,
      user,
      definitions.ListAppRewardCouponsResponse(_),
    )

  override def getBalance(respond: v0.WalletResource.GetBalanceResponse.type)()(
      user: String
  ): Future[v0.WalletResource.GetBalanceResponse] = withNewTrace(workflowId) { _ => _ =>
    for {
      userStore <- getUserStore(user)
      coins <- userStore.acs.listContracts(coinCodegen.Coin.COMPANION)
      lockedCoins <- userStore.acs.listContracts(coinCodegen.LockedCoin.COMPANION)
      now = clock.now
      currentRound <- store.getLatestOpenMiningRound(now).map(_.payload.round.number)
    } yield {
      val unlockedHoldingFees =
        coins.view.map(c => BigDecimal(CoinUtil.holdingFee(c.payload, currentRound))).sum
      val unlockedQty =
        coins.view.map(c => BigDecimal(CoinUtil.currentAmount(c.payload, currentRound))).sum
      val lockedQty =
        lockedCoins.view
          .map(c => BigDecimal(CoinUtil.currentAmount(c.payload.coin, currentRound)))
          .sum

      definitions.GetBalanceResponse(
        currentRound,
        Proto.encode(unlockedQty),
        Proto.encode(lockedQty),
        Proto.encode(unlockedHoldingFees),
      )
    }
  }

  override def list(respond: v0.WalletResource.ListResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListResponse] = withNewTrace(workflowId) {
    implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        now = clock.now
        currentRound <- store.getLatestOpenMiningRound(now).map(_.payload.round.number)
        coins <- userStore.acs.listContracts(coinCodegen.Coin.COMPANION)
        lockedCoins <- userStore.acs.listContracts(
          coinCodegen.LockedCoin.COMPANION
        )
      } yield definitions.ListResponse(
        coins.map(coinToCoinPosition(_, currentRound)).toVector,
        lockedCoins.map(lockedCoinToCoinPosition(_, currentRound)).toVector,
      )
  }

  override def createTransferOffer(respond: v0.WalletResource.CreateTransferOfferResponse.type)(
      request: definitions.CreateTransferOfferRequest
  )(user: String): Future[v0.WalletResource.CreateTransferOfferResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
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
        cid => protov0.CreateTransferOfferResponse(Proto.encodeContractId(cid.exerciseResult)),
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
      ).map { pv0 =>
        v0.WalletResource.CreateTransferOfferResponse.OK(
          definitions.CreateTransferOfferResponse(pv0.offerContractId)
        )
      }.recover {
        case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.ALREADY_EXISTS =>
          v0.WalletResource.CreateTransferOfferResponse.Conflict
      }
    }

  override def listSubscriptionInitialPayments(
      respond: v0.WalletResource.ListSubscriptionInitialPaymentsResponse.type
  )()(user: String): Future[v0.WalletResource.ListSubscriptionInitialPaymentsResponse] =
    listContracts(
      subsCodegen.SubscriptionInitialPayment.COMPANION,
      user,
      definitions.ListSubscriptionInitialPaymentsResponse(_),
    )

  override def listSubscriptionRequests(
      respond: v0.WalletResource.ListSubscriptionRequestsResponse.type
  )()(user: String): Future[v0.WalletResource.ListSubscriptionRequestsResponse] =
    withNewTrace(workflowId) { _ => _ =>
      listContracts(
        subsCodegen.SubscriptionRequest.COMPANION,
        user,
        definitions.ListSubscriptionRequestsResponse(_),
      )
    }

  override def listSubscriptions(respond: v0.WalletResource.ListSubscriptionsResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListSubscriptionsResponse] = withNewTrace(workflowId) {
    implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        subscriptions <- userStore.acs.listContracts(subsCodegen.Subscription.COMPANION)
        subscriptionIdleStates <- userStore.acs.listContracts(
          subsCodegen.SubscriptionIdleState.COMPANION
        )
        subscriptionPayments <- userStore.acs.listContracts(
          subsCodegen.SubscriptionPayment.COMPANION
        )
      } yield {
        val mainMap = subscriptions.map(sub => (sub.contractId -> sub.toJson)).toMap
        val idleStates = subscriptionIdleStates.map(state =>
          (state.payload.subscription, definitions.SubscriptionState(idle = Some(state.toJson)))
        )
        val payments = subscriptionPayments.map(state =>
          (state.payload.subscription, definitions.SubscriptionState(payment = Some(state.toJson)))
        )
        definitions.ListSubscriptionsResponse(
          (for {
            (mainId, state) <- idleStates ++ payments
            main <- mainMap.get(mainId)
          } yield {
            definitions.Subscription(main, state)
          }).toVector
        )
      }
  }

  override def tap(respond: v0.WalletResource.TapResponse.type)(request: definitions.TapRequest)(
      user: String
  ): Future[v0.WalletResource.TapResponse] = withNewTrace(workflowId) {
    implicit traceContext => span =>
      val amount = Proto.tryDecode(Proto.JavaBigDecimal)(request.amount)
      (for {
        userStore <- getUserStore(user)
        result <- exerciseWalletCoinAction(
          new coinoperation.CO_Tap(
            amount
          ),
          user,
          (outcome: coinoperationoutcome.COO_Tap) =>
            protov0.TapResponse(Proto.encodeContractId(outcome.contractIdValue)),
        ).map { pv0 =>
          v0.WalletResource.TapResponseOK(definitions.TapResponse(pv0.contractId))
        }
      } yield result).recover {
        case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.NOT_FOUND =>
          v0.WalletResource.TapResponseNotFound
        case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.ABORTED =>
          v0.WalletResource.TapResponseGone
      }
  }

  override def listTransferOffers(respond: v0.WalletResource.ListTransferOffersResponse.type)()(
      user: String
  ): Future[v0.WalletResource.ListTransferOffersResponse] =
    listContracts(
      transferOffersCodegen.TransferOffer.COMPANION,
      user,
      definitions.ListTransferOffersResponse(_),
    )

  override def userStatus(respond: v0.WalletResource.UserStatusResponse.type)()(
      user: String
  ): Future[v0.WalletResource.UserStatusResponse] = withNewTrace(workflowId) { _ => _ =>
    for {
      optInstall <- store.lookupInstallByName(user)
      hasFeaturedAppRight <- walletManager.lookupUserWallet(user) match {
        case None => Future(false)
        case Some(wallet) =>
          wallet.store.lookupFeaturedAppRight().map(_.isDefined)
      }
    } yield {
      definitions.UserStatusResponse(
        partyId = optInstall.fold("")(co => co.payload.endUserParty),
        userOnboarded = optInstall.isDefined,
        hasFeaturedAppRight = hasFeaturedAppRight,
      )
    }
  }

  override def listValidatorRewardCoupons(
      respond: v0.WalletResource.ListValidatorRewardCouponsResponse.type
  )()(user: String): Future[v0.WalletResource.ListValidatorRewardCouponsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        validatorRewardCoupons <- walletManager.listValidatorRewardCouponsCollectableBy(
          userStore,
          None,
          None,
        )
      } yield definitions.ListValidatorRewardCouponsResponse(
        validatorRewardCoupons.map(_.toJson).toVector
      )
    }

  override def cancelFeaturedAppRights(
      respond: v0.WalletResource.CancelFeaturedAppRightsResponse.type
  )()(user: String): Future[WalletResource.CancelFeaturedAppRightsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        featuredAppRight <- userStore.lookupFeaturedAppRight()
        result <- featuredAppRight match {
          case None =>
            logger.info(s"No featured app right found for user ${user} - nothing to cancel")
            Future.successful(WalletResource.CancelFeaturedAppRightsResponseOK)
          case Some(cid) =>
            exerciseWalletAction((installCid, _) => {
              Future.successful(
                installCid.exerciseWalletAppInstall_FeaturedAppRights_Cancel(
                  cid.contractId
                )
              )
            })(user, _ => WalletResource.CancelFeaturedAppRightsResponseOK)
        }
      } yield result
    }

  override def listConnectedDomains(respond: v0.WalletResource.ListConnectedDomainsResponse.type)()(
      user: String
  ): Future[WalletResource.ListConnectedDomainsResponse] =
    withNewTrace(workflowId) { _ => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield v0.WalletResource.ListConnectedDomainsResponse.OK(
        definitions.ListConnectedDomainsResponse(
          domains.view.map { case (k, v) =>
            k.toProtoPrimitive -> v.toProtoPrimitive
          }.toMap
        )
      )
    }

  private def coinToCoinPosition(
      coin: Contract[Coin.ContractId, Coin],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): definitions.CoinPosition = {
    definitions.CoinPosition(
      coin.toJson,
      round,
      Proto.encode(CoinUtil.holdingFee(coin.payload, round)),
      Proto.encode(CoinUtil.currentAmount(coin.payload, round)),
    )
  }

  private def lockedCoinToCoinPosition(
      lockedCoin: Contract[LockedCoin.ContractId, LockedCoin],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): definitions.CoinPosition =
    definitions.CoinPosition(
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

  private def listContracts[TC <: CodegenContract[TCid, T], TCid <: ContractId[
    T
  ], T <: Template, ResponseT](
      templateCompanion: ContractCompanion[TC, TCid, T],
      user: String,
      mkResponse: Vector[definitions.Contract] => ResponseT,
  ): Future[ResponseT] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        contracts <- userStore.acs.listContracts(templateCompanion)
      } yield mkResponse(contracts.map(_.toJson).toVector)
    }

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
      ProtoResponse <: scalapb.GeneratedMessage,
  ](
      operation: installCodegen.CoinOperation,
      user: String,
      processResponse: ExpectedCOO => ProtoResponse,
  )(implicit tc: TraceContext): Future[ProtoResponse] =
    for {
      userTreasury <- getUserTreasury(user)
      res <- userTreasury
        .enqueueCoinOperation(operation)
        .map(processCOO[ExpectedCOO, ProtoResponse](processResponse))
    } yield res

  /** Helper function to process a CoinOperationOutcome.
    * Ensures that the outcome is of the expected type and throws an appropriate exception if it isn't.
    */
  private def processCOO[
      ExpectedCOO <: CoinOperationOutcome: ClassTag,
      ProtoReturnType <: scalapb.GeneratedMessage,
  ](
      process: ExpectedCOO => ProtoReturnType
  )(
      actual: installCodegen.CoinOperationOutcome
  )(implicit tc: TraceContext): ProtoReturnType = {
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
            )
      }

    } yield {
      getResponse(result)
    }
  }
}
