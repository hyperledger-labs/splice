package com.daml.network.wallet.admin.grpc

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  Contract => CodegenContract,
  ContractCompanion,
  ContractId,
  Update,
}
import com.daml.network.auth.AuthInterceptor
import com.daml.network.codegen.java.cc.coin.{Coin, LockedCoin}
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.COO_AcceptedAppPayment
import com.daml.network.codegen.java.cn.wallet.install.{
  CoinOperationOutcome,
  coinoperation,
  coinoperationoutcome,
}
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentQuantity}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.environment.CoinLedgerConnection.CommandId
import com.daml.network.environment.*
import com.daml.network.util.{CoinUtil, JavaContract => Contract, Proto}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.daml.network.wallet.v0.*
import com.daml.network.wallet.{UserWalletManager, UserWalletService, v0}
import com.daml.network.v0 as networkV0
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.Duration
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class GrpcWalletService(
    walletManager: UserWalletManager,
    ledgerClient: CoinLedgerClient,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

  private val store = walletManager.store
  private val walletServiceParty: PartyId = store.key.walletServiceParty
  private val validatorParty: PartyId = store.key.validatorParty

  private val connection = ledgerClient.connection("GrpcWalletService")

  override def userStatus(request: v0.UserStatusRequest): Future[v0.UserStatusResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        for {
          optInstall <- store.lookupInstallByName(user)
        } yield {
          v0.UserStatusResponse(
            partyId = optInstall.fold("")(co => co.payload.endUserParty),
            userOnboarded = optInstall.isDefined,
          )
        }
      }
    }

  private def coinToCoinPosition(
      coin: Contract[Coin.ContractId, Coin],
      round: Long,
  ): v0.CoinPosition =
    v0.CoinPosition(
      Some(coin.toProtoV0),
      round,
      Proto.encode(CoinUtil.holdingFee(coin.payload, round)),
      Proto.encode(CoinUtil.currentQuantity(coin.payload, round)),
    )

  private def lockedCoinToCoinPosition(
      lockedCoin: Contract[LockedCoin.ContractId, LockedCoin],
      round: Long,
  ): v0.CoinPosition =
    v0.CoinPosition(
      Some(lockedCoin.toProtoV0),
      round,
      Proto.encode(CoinUtil.holdingFee(lockedCoin.payload.coin, round)),
      Proto.encode(CoinUtil.currentQuantity(lockedCoin.payload.coin, round)),
    )

  private def withAuth[A](f: String => A)(implicit tc: TraceContext): A = {
    val userO = AuthInterceptor.extractSubjectFromContext()
    userO match {
      case None =>
        logger.warn(s"No user defined in token")
        throw new StatusRuntimeException(
          Status.UNAUTHENTICATED.withDescription("Token did not specify a user")
        )
      case Some(user) => f(user)
    }
  }

  private[this] def getUserWallet(user: String): UserWalletService =
    walletManager
      .lookupUserWallet(user)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(show"User ${user.singleQuoted}")
        )
      )

  private[this] def getUserStore(user: String): Future[UserWalletStore] = Future {
    getUserWallet(user).store
  }

  private[this] def getUserTreasury(user: String): Future[TreasuryService] = Future {
    getUserWallet(user).treasury
  }

  private def listContracts[TC <: CodegenContract[TCid, T], TCid <: ContractId[
    T
  ], T <: Template, ResponseT](
      templateCompanion: ContractCompanion[TC, TCid, T],
      mkResponse: Seq[networkV0.Contract] => ResponseT,
  ): Future[ResponseT] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        for {
          userStore <- getUserStore(user)
          contracts <- userStore.acs.listContracts(templateCompanion)
        } yield mkResponse(contracts.map(_.toProtoV0))
      }
    }

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        for {
          userStore <- getUserStore(user)
          now = clock.now
          currentRound <- store.getLatestOpenMiningRound(now).map(_.payload.round.number)
          coins <- userStore.acs.listContracts(coinCodegen.Coin.COMPANION)
          lockedCoins <- userStore.acs.listContracts(
            coinCodegen.LockedCoin.COMPANION
          )
        } yield v0.ListResponse(
          coins.map(coinToCoinPosition(_, currentRound)),
          lockedCoins.map(lockedCoinToCoinPosition(_, currentRound)),
        )
      }
    }

  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        val quantity = Proto.tryDecode(Proto.JavaBigDecimal)(request.quantity)
        for {
          userStore <- getUserStore(user)
          result <- exerciseWalletCoinAction(
            new coinoperation.CO_Tap(
              quantity
            ),
            user,
            (outcome: coinoperationoutcome.COO_Tap) =>
              v0.TapResponse(Proto.encodeContractId(outcome.contractIdValue)),
          )
        } yield result
      }
    }

  override def listAppPaymentRequests(
      request: v0.ListAppPaymentRequestsRequest
  ): Future[v0.ListAppPaymentRequestsResponse] =
    listContracts(
      walletCodegen.AppPaymentRequest.COMPANION,
      v0.ListAppPaymentRequestsResponse(_),
    )

  override def getBalance(
      request: v0.GetBalanceRequest
  ): Future[v0.GetBalanceResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
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
            coins.view.map(c => BigDecimal(CoinUtil.currentQuantity(c.payload, currentRound))).sum
          val lockedQty =
            lockedCoins.view
              .map(c => BigDecimal(CoinUtil.currentQuantity(c.payload.coin, currentRound)))
              .sum

          v0.GetBalanceResponse(
            currentRound,
            Proto.encode(unlockedQty),
            Proto.encode(lockedQty),
            Proto.encode(unlockedHoldingFees),
          )
        }
      }
    }

  override def acceptAppPaymentRequest(
      request: v0.AcceptAppPaymentRequestRequest
  ): Future[v0.AcceptAppPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        val requestCid = Proto.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
          request.requestContractId
        )
        exerciseWalletCoinAction(
          new coinoperation.CO_AppPayment(requestCid),
          user,
          (outcome: COO_AcceptedAppPayment) =>
            v0.AcceptAppPaymentRequestResponse(
              Proto.encodeContractId(outcome.contractIdValue)
            ),
        )
      }
    }

  override def rejectAppPaymentRequest(
      request: v0.RejectAppPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
            request.requestContractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_AppPaymentRequest_Reject(
              requestCid
            )
          )
        })(user, _ => Empty())
      }
    }

  override def listAcceptedAppPayments(
      request: v0.ListAcceptedAppPaymentsRequest
  ): Future[v0.ListAcceptedAppPaymentsResponse] =
    listContracts(
      walletCodegen.AcceptedAppPayment.COMPANION,
      v0.ListAcceptedAppPaymentsResponse(_),
    )

  override def listSubscriptionRequests(
      request: v0.ListSubscriptionRequestsRequest
  ): Future[v0.ListSubscriptionRequestsResponse] =
    listContracts(
      subsCodegen.SubscriptionRequest.COMPANION,
      v0.ListSubscriptionRequestsResponse(_),
    )

  override def listSubscriptionInitialPayments(
      request: v0.ListSubscriptionInitialPaymentsRequest
  ): Future[v0.ListSubscriptionInitialPaymentsResponse] =
    listContracts(
      subsCodegen.SubscriptionInitialPayment.COMPANION,
      v0.ListSubscriptionInitialPaymentsResponse(_),
    )

  override def listSubscriptions(
      request: v0.ListSubscriptionsRequest
  ): Future[v0.ListSubscriptionsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
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
          val mainMap = subscriptions.map(sub => (sub.contractId -> Some(sub.toProtoV0))).toMap
          val idleStates = subscriptionIdleStates.map(state =>
            (state.payload.subscription, v0.Subscription.State.Idle(state.toProtoV0))
          )
          val payments = subscriptionPayments.map(state =>
            (state.payload.subscription, v0.Subscription.State.Payment(state.toProtoV0))
          )
          v0.ListSubscriptionsResponse(
            for {
              (mainId, state) <- idleStates ++ payments
              main <- mainMap.get(mainId)
            } yield {
              v0.Subscription(main, state)
            }
          )
        }
      }
    }

  override def acceptSubscriptionRequest(
      request: v0.AcceptSubscriptionRequestRequest
  ): Future[v0.AcceptSubscriptionRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        val requestCid =
          Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
            request.requestContractId
          )
        exerciseWalletCoinAction(
          new coinoperation.CO_SubscriptionAcceptAndMakeInitialPayment(requestCid),
          user,
          (outcome: coinoperationoutcome.COO_SubscriptionInitialPayment) =>
            v0.AcceptSubscriptionRequestResponse(
              Proto.encodeContractId(outcome.contractIdValue)
            ),
        )
      }
    }

  override def rejectSubscriptionRequest(
      request: v0.RejectSubscriptionRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
            request.requestContractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_SubscriptionRequest_Reject(
              requestCid
            )
          )
        })(user, _ => Empty())
      }
    }

  override def cancelSubscription(
      request: v0.CancelSubscriptionRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionIdleState.COMPANION)(
              request.idleStateContractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_SubscriptionIdleState_CancelSubscription(
              requestCid
            )
          )
        })(user, _ => Empty())
      }
    }

  override def listAppRewardCoupons(
      request: v0.ListAppRewardCouponsRequest
  ): Future[v0.ListAppRewardCouponsResponse] =
    listContracts(
      coinCodegen.AppRewardCoupon.COMPANION,
      v0.ListAppRewardCouponsResponse(_),
    )

  override def listValidatorRewardCoupons(
      request: v0.ListValidatorRewardCouponsRequest
  ): Future[v0.ListValidatorRewardCouponsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        for {
          userStore <- getUserStore(user)
          validatorRewardCoupons <- walletManager.listValidatorRewardCouponsCollectableBy(userStore)
        } yield v0.ListValidatorRewardCouponsResponse(validatorRewardCoupons.map(_.toProtoV0))
      }
    }

  override def createTransferOffer(
      request: CreateTransferOfferRequest
  ): Future[CreateTransferOfferResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        {
          val sender = getUserWallet(user).store.key.endUserParty
          exerciseWalletAction((installCid, _) => {
            val receiver = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
            val quantity = Proto.tryDecode(Proto.JavaBigDecimal)(request.quantity)
            val expiresAt = Proto.tryDecode(Proto.Timestamp)(request.expiresAt)
            val senderTransferFeeRatio =
              Proto.tryDecode(Proto.JavaBigDecimal)(request.senderTransferFeeRatio)
            Future.successful(
              installCid.exerciseWalletAppInstall_CreateTransferOffer(
                receiver.toProtoPrimitive,
                new PaymentQuantity(quantity, Currency.CC),
                request.description,
                expiresAt.toInstant,
                senderTransferFeeRatio,
              )
            )
          })(
            user,
            cid => v0.CreateTransferOfferResponse(Proto.encodeContractId(cid.exerciseResult)),
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
          )
        }
      }
    }

  override def listTransferOffers(
      request: Empty
  ): Future[ListTransferOffersResponse] =
    listContracts(
      transferOffersCodegen.TransferOffer.COMPANION,
      v0.ListTransferOffersResponse(_),
    )

  override def acceptTransferOffer(
      request: AcceptTransferOfferRequest
  ): Future[AcceptTransferOfferResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
              request.offerContractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_TransferOffer_Accept(
              requestCid
            )
          )
        })(
          user,
          cid => v0.AcceptTransferOfferResponse(Proto.encodeContractId(cid.exerciseResult)),
        )
      }
    }

  override def listAcceptedTransferOffers(
      request: Empty
  ): Future[ListAcceptedTransferOffersResponse] =
    listContracts(
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
      v0.ListAcceptedTransferOffersResponse(_),
    )

  override def rejectTransferOffer(
      request: v0.RejectTransferOfferRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
              request.offerContractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_TransferOffer_Reject(
              requestCid
            )
          )
        })(
          user,
          _ => Empty(),
        )
      }
    }

  override def withdrawTransferOffer(
      request: v0.WithdrawTransferOfferRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(transferOffersCodegen.TransferOffer.COMPANION)(
              request.offerContractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_TransferOffer_Withdraw(
              requestCid
            )
          )
        })(
          user,
          _ => Empty(),
        )
      }
    }

  override def listConnectedDomains(request: Empty): Future[v0.ListConnectedDomainsResponse] =
    withSpanFromGrpcContext("GrpcSplitwiseService") { _ => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield {
        v0.ListConnectedDomainsResponse(Some(Proto.encode(domains)))
      }
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
      LookupResult,
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
      result <- dedup match {
        case None =>
          connection.submitWithResultNoDedup(
            Seq(walletServiceParty),
            Seq(validatorParty, userParty),
            update,
          )
        case Some((commandId, dedupConfig)) =>
          connection
            .submitWithResult(
              Seq(walletServiceParty),
              Seq(validatorParty, userParty),
              update,
              commandId,
              dedupConfig,
            )
      }

    } yield {
      getResponse(result)
    }
  }
}
