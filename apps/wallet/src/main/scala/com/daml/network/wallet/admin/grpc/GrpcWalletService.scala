package com.daml.network.wallet.admin.grpc

import cats.implicits.*
import com.daml.ledger.client.binding.{TemplateCompanion, ValueDecoder, Primitive as P}
import com.daml.network.auth.AuthInterceptor
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
import com.daml.network.codegen.CC.{Coin as coinCodegen, CoinRules as coinRulesCodegen}
import com.daml.network.codegen.CN.Wallet.CoinOperationOutcome.COO_AcceptedAppPayment
import com.daml.network.codegen.CN.Wallet.{
  CoinOperation,
  CoinOperationOutcome,
  Subscriptions as subsCodegen,
}
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.{CoinUtil, Contract, Proto, Value}
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.daml.network.wallet.treasury.{
  CoinOperationRequest,
  EndUserTreasuryService,
  TreasuryServices,
}
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{CollectRewardsRequest, WalletServiceGrpc}
import com.daml.network.v0 as networkV0
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class GrpcWalletService(
    store: WalletStore,
    treasuries: TreasuryServices,
    ledgerClient: CoinLedgerClient,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

  private val walletServiceParty: PartyId = store.key.walletServiceParty
  private val validatorParty: PartyId = store.key.validatorParty

  private val connection = ledgerClient.connection("GrpcWalletService")

  @nowarn("cat=unused")
  override def userStatus(request: v0.UserStatusRequest): Future[v0.UserStatusResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        QueryResult(_, optInstall) <- store.lookupInstallByName(request.getWalletCtx.userName)
      } yield {
        v0.UserStatusResponse(
          partyId = optInstall.fold("")(co => P.Party.unwrap(co.payload.endUserParty)),
          userOnboarded = optInstall.isDefined,
        )
      }
    }

  private def coinToCoinPosition(coin: Contract[Coin], round: Long): v0.CoinPosition =
    v0.CoinPosition(
      Some(coin.toProtoV0),
      round,
      Proto.encode(CoinUtil.holdingFee(coin.payload, round)),
      Proto.encode(CoinUtil.currentQuantity(coin.payload, round)),
    )

  private def lockedCoinToCoinPosition(
      lockedCoin: Contract[LockedCoin],
      round: Long,
  ): v0.CoinPosition =
    v0.CoinPosition(
      Some(lockedCoin.toProtoV0),
      round,
      Proto.encode(CoinUtil.holdingFee(lockedCoin.payload.coin, round)),
      Proto.encode(CoinUtil.currentQuantity(lockedCoin.payload.coin, round)),
    )

  private def withAuth[A](ctx: v0.WalletContext)(f: String => A)(implicit tc: TraceContext): A = {
    // TODO(i1012) - remove wallet context and enforce auth token
    val user = AuthInterceptor.extractSubjectFromContext().getOrElse(ctx.userName)
    if (user.isEmpty) {
      logger.warn(s"No user defined in token or context")
    }
    f(user)
  }

  private[this] def getUserStore(user: String): Future[EndUserWalletStore] = Future {
    store
      .lookupEndUserStore(user)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"User ${user.singleQuoted}")
        )
      )
  }

  private[this] def getUserTreasury(user: String): Future[EndUserTreasuryService] = Future {
    treasuries
      .lookupEndUserTreasury(user)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"User ${user.singleQuoted}")
        )
      )
  }

  private def listContracts[T, ResponseT](
      context: v0.WalletContext,
      templateCompanion: TemplateCompanion[T],
      mkResponse: Seq[networkV0.Contract] => ResponseT,
  ): Future[ResponseT] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(context) { user =>
        for {
          userStore <- getUserStore(user)
          QueryResult(_, contracts) <- userStore.listContracts(templateCompanion)
        } yield mkResponse(contracts.map(_.toProtoV0))
      }
    }

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          validatorStore <- getUserStore(store.key.validatorUserName)
          currentRound <- validatorStore
            .getLatestOpenMiningRound(retryProvider)
            .map(_.value.payload.round.number)
          QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin)
          QueryResult(_, lockedCoins) <- userStore.listContracts(coinCodegen.LockedCoin)
        } yield {
          v0.ListResponse(
            coins.map(coinToCoinPosition(_, currentRound)),
            lockedCoins.map(lockedCoinToCoinPosition(_, currentRound)),
          )
        }
      }
    }

  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletCoinAction((c, endUserWalletStore) => {
          val quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
          Future.successful(
            CoinOperationRequest(
              (_: Unit) =>
                CoinOperation.CO_Tap(
                  store.key.svcParty.toPrim,
                  validatorParty.toPrim,
                  endUserWalletStore.key.endUserParty.toPrim,
                  quantity,
                ),
              () => Future.successful(()),
            )
          )
        })(
          user,
          (outcome: CoinOperationOutcome.COO_Tap) => v0.TapResponse(Proto.encode(outcome.body)),
        )
      }
    }

  override def listAppPaymentRequests(
      request: v0.ListAppPaymentRequestsRequest
  ): Future[v0.ListAppPaymentRequestsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.AppPaymentRequest,
      v0.ListAppPaymentRequestsResponse(_),
    )

  override def getBalance(
      request: v0.GetBalanceRequest
  ): Future[v0.GetBalanceResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          validatorStore <- getUserStore(store.key.validatorUserName)
          QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin)
          QueryResult(_, lockedCoins) <- userStore.listContracts(coinCodegen.LockedCoin)
          currentRound <- validatorStore
            .getLatestOpenMiningRound(retryProvider)
            .map(_.value.payload.round.number)
        } yield {
          val unlockedHoldingFees =
            coins.view.map(c => CoinUtil.holdingFee(c.payload, currentRound)).sum
          val unlockedQty =
            coins.view.map(c => CoinUtil.currentQuantity(c.payload, currentRound)).sum
          val lockedQty =
            lockedCoins.view.map(c => CoinUtil.currentQuantity(c.payload.coin, currentRound)).sum

          v0.GetBalanceResponse(
            currentRound,
            Proto.encode(unlockedQty),
            Proto.encode(lockedQty),
            Proto.encode(unlockedHoldingFees),
          )
        }
      }
    }

  private def getQueryResult[T](
      result: QueryResult[Option[Contract[T]]],
      errorMsg: String,
  ): Contract[T] = result match {
    case QueryResult(_, None) =>
      throw new StatusRuntimeException(
        Status.NOT_FOUND.withDescription(errorMsg)
      )
    case QueryResult(_, Some(install)) => install
  }

  override def acceptAppPaymentRequest(
      request: v0.AcceptAppPaymentRequestRequest
  ): Future[v0.AcceptAppPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletCoinAction((installCid, userStore) => {
          val requestCid = Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](
            request.requestContractId
          )

          def lookups = () =>
            for {
              paymentRequestO <- userStore.lookupAppPaymentRequestById(requestCid)
              paymentRequest = getQueryResult(
                paymentRequestO,
                s"app payment request with cid $requestCid",
              )
              _ <- lookupDeliveryOffer(
                userStore.key.endUserParty,
                paymentRequest.payload.deliveryOffer,
              )
            } yield ()

          Future.successful(
            CoinOperationRequest((_: Unit) => CoinOperation.CO_AppPayment(requestCid), lookups)
          )
        })(
          user,
          (outcome: COO_AcceptedAppPayment) =>
            v0.AcceptAppPaymentRequestResponse(
              Proto.encode(outcome.body)
            ),
        )
      }
    }

  override def rejectAppPaymentRequest(
      request: v0.RejectAppPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](
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
      request.getWalletCtx,
      walletCodegen.AcceptedAppPayment,
      v0.ListAcceptedAppPaymentsResponse(_),
    )

  override def listSubscriptionRequests(
      request: v0.ListSubscriptionRequestsRequest
  ): Future[v0.ListSubscriptionRequestsResponse] =
    listContracts(
      request.getWalletCtx,
      subsCodegen.SubscriptionRequest,
      v0.ListSubscriptionRequestsResponse(_),
    )

  override def listSubscriptionInitialPayments(
      request: v0.ListSubscriptionInitialPaymentsRequest
  ): Future[v0.ListSubscriptionInitialPaymentsResponse] =
    listContracts(
      request.getWalletCtx,
      subsCodegen.SubscriptionInitialPayment,
      v0.ListSubscriptionInitialPaymentsResponse(_),
    )

  override def listSubscriptions(
      request: v0.ListSubscriptionsRequest
  ): Future[v0.ListSubscriptionsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          QueryResult(_, subscriptions) <- userStore.listContracts(
            subsCodegen.Subscription
          )
          QueryResult(_, subscriptionIdleStates) <- userStore.listContracts(
            subsCodegen.SubscriptionIdleState
          )
          QueryResult(_, subscriptionPayments) <- userStore.listContracts(
            subsCodegen.SubscriptionPayment
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
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletCoinAction((installCid, userStore) => {
          val requestCid =
            Proto.tryDecodeContractId[subsCodegen.SubscriptionRequest](
              request.requestContractId
            )

          def lookups = () =>
            for {
              subscriptionRequestO <- userStore.lookupSubscriptionRequestById(requestCid)
              subscriptionRequest = getQueryResult(
                subscriptionRequestO,
                s"subscription request with cid $requestCid",
              )
              _ <- lookupSubscriptionContext(
                userStore.key.endUserParty,
                subscriptionRequest.payload.subscriptionData.context,
              )
            } yield ()

          Future.successful(
            CoinOperationRequest(
              (_: Unit) => CoinOperation.CO_SubscriptionAcceptAndMakeInitialPayment(requestCid),
              lookups,
            )
          )
        })(
          user,
          (outcome: CoinOperationOutcome.COO_SubscriptionInitialPayment) =>
            v0.AcceptSubscriptionRequestResponse(
              Proto.encode(outcome.body)
            ),
        )
      }
    }

  override def rejectSubscriptionRequest(
      request: v0.RejectSubscriptionRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeContractId[subsCodegen.SubscriptionRequest](
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

  override def makeSubscriptionPayment(
      request: v0.MakeSubscriptionPaymentRequest
  ): Future[v0.MakeSubscriptionPaymentResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletCoinAction((installCid, userStore) => {
          val stateCid =
            Proto.tryDecodeContractId[subsCodegen.SubscriptionIdleState](
              request.idleStateContractId
            )

          def lookups = () =>
            for {
              subscriptionStateO <- userStore.lookupSubscriptionIdleStateById(stateCid)
              subscriptionState = getQueryResult(
                subscriptionStateO,
                s"subscription idle state cid $stateCid",
              )
              _ <- lookupSubscriptionContext(
                userStore.key.endUserParty,
                subscriptionState.payload.subscriptionData.context,
              )
            } yield ()

          Future.successful(
            CoinOperationRequest(
              (_: Unit) => CoinOperation.CO_SubscriptionMakePayment(stateCid),
              lookups,
            )
          )
        })(
          user,
          (outcome: CoinOperationOutcome.COO_SubscriptionPayment) =>
            v0.MakeSubscriptionPaymentResponse(
              Proto.encode(outcome.body)
            ),
        )
      }
    }

  override def cancelSubscription(
      request: v0.CancelSubscriptionRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeContractId[subsCodegen.SubscriptionIdleState](
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

  override def listPaymentChannelProposals(
      request: v0.ListPaymentChannelProposalsRequest
  ): Future[v0.ListPaymentChannelProposalsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.PaymentChannelProposal,
      v0.ListPaymentChannelProposalsResponse(_),
    )

  override def listPaymentChannels(
      request: v0.ListPaymentChannelsRequest
  ): Future[v0.ListPaymentChannelsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.PaymentChannel,
      v0.ListPaymentChannelsResponse(_),
    )

  override def proposePaymentChannel(
      request: v0.ProposePaymentChannelRequest
  ): Future[v0.ProposePaymentChannelResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, userStore) => {
          val receiver = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          val proposer = userStore.key.endUserParty.toPrim
          val channel = walletCodegen.PaymentChannel(
            sender = userStore.key.endUserParty.toPrim,
            receiver = receiver.toPrim,
            svc = userStore.key.svcParty.toPrim,
            allowRequests = request.allowRequests,
            allowOffers = request.allowOffers,
            allowDirectTransfers = request.allowDirectTransfers,
            senderTransferFeeRatio =
              Proto.tryDecode(Proto.BigDecimal)(request.senderTransferFeeRatio),
          )
          val replacesChannel = request.replacesChannelId.map(
            Proto.tryDecodeContractId[walletCodegen.PaymentChannel]
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_CreatePaymentChannelProposal(
              proposer,
              channel,
              replacesChannel,
            )
          )
        })(user, cid => v0.ProposePaymentChannelResponse(Proto.encode(cid)))
      }
    }

  override def acceptPaymentChannelProposal(
      request: v0.AcceptPaymentChannelProposalRequest
  ): Future[v0.AcceptPaymentChannelProposalResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeContractId[walletCodegen.PaymentChannelProposal](
            request.proposalContractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_PaymentChannelProposal_Accept(
              requestCid
            )
          )
        })(user, cid => v0.AcceptPaymentChannelProposalResponse(Proto.encode(cid)))
      }
    }

  override def cancelPaymentChannelBySender(
      request: v0.CancelPaymentChannelBySenderRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, userStore) => {
          val senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
          val svcParty = userStore.key.svcParty
          for {
            channel <- lookupPaymentChannel(
              userStore,
              svcParty.toPrim,
              senderP = senderParty.toPrim,
              receiverP = userStore.key.endUserParty.toPrim,
            )
          } yield installCid.exerciseWalletAppInstall_PaymentChannel_Cancel_By_Sender(
            channel
          )
        })(user, _ => Empty())
      }
    }

  override def cancelPaymentChannelByReceiver(
      request: v0.CancelPaymentChannelByReceiverRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, userStore) => {
          val receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          val svcParty = userStore.key.svcParty
          for {
            channel <- lookupPaymentChannel(
              userStore,
              svcParty.toPrim,
              senderP = userStore.key.endUserParty.toPrim,
              receiverP = receiverParty.toPrim,
            )
          } yield installCid.exerciseWalletAppInstall_PaymentChannel_Cancel_By_Receiver(
            channel
          )
        })(user, _ => Empty())
      }
    }

  override def executeDirectTransfer(
      request: v0.ExecuteDirectTransferRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletCoinAction((c, userStore) => {
          val endUserParty = userStore.key.endUserParty
          val quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
          val receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          Future.successful(
            CoinOperationRequest(
              (channelId) =>
                CoinOperation.CO_ChannelTransfer(
                  channelId,
                  quantity,
                  "wallet: execute direct transfer",
                ),
              () =>
                lookupPaymentChannel(
                  userStore,
                  store.key.svcParty.toPrim,
                  receiverP = receiverParty.toPrim,
                  senderP = endUserParty.toPrim,
                ),
            )
          )
        })(
          user,
          (_: CoinOperationOutcome.COO_AcceptedChannelTransfer) => Empty(),
        )
      }
    }

  override def listAppRewards(
      request: v0.ListAppRewardsRequest
  ): Future[v0.ListAppRewardsResponse] =
    listContracts(
      request.getWalletCtx,
      coinCodegen.AppReward,
      v0.ListAppRewardsResponse(_),
    )

  override def listValidatorRewards(
      request: v0.ListValidatorRewardsRequest
  ): Future[v0.ListValidatorRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          validatorRewards <- store.listValidatorRewardsCollectableBy(userStore)
        } yield v0.ListValidatorRewardsResponse(validatorRewards.map(_.toProtoV0))
      }
    }

  // TODO(#1351) - Make this a `CoinOperation` too
  override def collectRewards(request: CollectRewardsRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          validatorStore <- getUserStore(store.key.validatorUserName)
          validatorRewards <- store.listValidatorRewardsCollectableBy(userStore)
          validatorRewardInputs = validatorRewards
            .filter(c => c.payload.round.number == request.round)
            .map(c => coinRulesCodegen.TransferInput.InputValidatorReward(c.contractId))
          QueryResult(_, appRewards) <- userStore.listContracts(coinCodegen.AppReward)
          appRewardInputs = appRewards
            .filter(c => c.payload.round.number == request.round)
            .map(c => coinRulesCodegen.TransferInput.InputAppReward(c.contractId))
          coinCid <- selectCoin(userStore, 0)
          inputCoin = coinRulesCodegen.TransferInput.InputCoin(coinCid)
          inputs = (inputCoin +: validatorRewardInputs :++ appRewardInputs)
          outputs = Seq(
            coinRulesCodegen.TransferOutput.OutputSenderCoin(exactQuantity = None, lock = None)
          )
          coins <- redistribute(userStore, validatorStore, inputs, outputs)
        } yield {
          require(coins.size == 1, "Expected exactly one coin")
          Empty()
        }
      }
    }

  override def createOnChannelPaymentRequest(
      request: v0.CreateOnChannelPaymentRequestRequest
  ): Future[v0.CreateOnChannelPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, userStore) => {
          val senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
          val svcParty = userStore.key.svcParty
          val quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
          val description = request.description
          for {
            channel <- lookupPaymentChannel(
              userStore,
              svcParty.toPrim,
              senderP = senderParty.toPrim,
              receiverP = userStore.key.endUserParty.toPrim,
            )
          } yield installCid.exerciseWalletAppInstall_PaymentChannel_CreatePaymentRequest(
            channel,
            quantity,
            description,
          )
        })(
          user,
          cid => v0.CreateOnChannelPaymentRequestResponse(requestContractId = Proto.encode(cid)),
        )
      }
    }

  override def listOnChannelPaymentRequests(
      request: v0.ListOnChannelPaymentRequestsRequest
  ): Future[v0.ListOnChannelPaymentRequestsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.OnChannelPaymentRequest,
      v0.ListOnChannelPaymentRequestsResponse(_),
    )

  override def acceptOnChannelPaymentRequest(
      request: v0.AcceptOnChannelPaymentRequestRequest
  ): Future[Empty] = withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
    withAuth(request.getWalletCtx) { user =>
      exerciseWalletCoinAction((installCid, userStore) => {
        val requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )

        def lookups = () =>
          for {
            paymentRequestO <- userStore.lookupOnChannelPaymentRequestById(requestCid)
            paymentRequest = getQueryResult(
              paymentRequestO,
              s"channel payment request with cid $requestCid",
            )
            _ <- lookupPaymentChannel(
              userStore,
              paymentRequest.payload.svc,
              receiverP = paymentRequest.payload.receiver,
              senderP = userStore.key.endUserParty.toPrim,
            )
          } yield ()

        Future.successful(
          CoinOperationRequest((_: Unit) => CoinOperation.CO_ChannelPayment(requestCid), lookups)
        )
      })(user, (_: CoinOperationOutcome.COO_AcceptedChannelPayment) => Empty())
    }
  }

  override def rejectOnChannelPaymentRequest(
      request: v0.RejectOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
            request.requestContractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_OnChannelPaymentRequest_Reject(
              requestCid
            )
          )
        })(user, _ => Empty())
      }
    }

  override def withdrawOnChannelPaymentRequest(
      request: v0.WithdrawOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
            request.requestContractId
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_OnChannelPaymentRequest_Withdraw(
              requestCid
            )
          )
        })(user, _ => Empty())
      }
    }

  // TODO(#1351) - Remove this
  private def redistribute(
      userStore: EndUserWalletStore,
      validatorStore: EndUserWalletStore,
      inputs: Seq[coinRulesCodegen.TransferInput],
      outputs: Seq[coinRulesCodegen.TransferOutput],
  )(implicit tc: TraceContext): Future[Seq[P.ContractId[coinCodegen.Coin]]] = {
    val user = userStore.key.endUserName
    val party = userStore.key.endUserParty
    exerciseWalletAction((installCid, _) => {
      val transfer = coinRulesCodegen.Transfer(
        sender = party.toPrim,
        provider = party.toPrim,
        inputs = inputs,
        outputs = outputs,
        payload = "redistribute",
      )
      for {
        transferContext <- validatorStore.getPaymentTransferContext(retryProvider)
      } yield installCid.exerciseWalletAppInstall_CoinRules_TryTransfer(
        transferContext.coinRules,
        transfer,
        transferContext.context,
      )
    })(
      user,
      _.createdCoins.collect { case coinRulesCodegen.CreatedCoin.TransferResultCoin(cid) =>
        cid
      },
    )
  }

  // TODO(#1351) - Remove this
  override def redistribute(request: v0.RedistributeRequest): Future[v0.RedistributeResponse] = {
    def toOutput(output: v0.RedistributeOutput): coinRulesCodegen.TransferOutput =
      coinRulesCodegen.TransferOutput.OutputSenderCoin(
        lock = None,
        exactQuantity =
          if (output.quantity.isEmpty) None
          else Some(Proto.tryDecode(Proto.BigDecimal)(output.quantity)),
      )

    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          validatorStore <- getUserStore(store.key.validatorUserName)
          inputs = request.inputs
            .traverse(Value.fromProto[coinRulesCodegen.TransferInput](_).map(_.value))
            .valueOr(err => throw err.toAdminError.asGrpcError)
          outputs = request.outputs.map(toOutput)
          createdCoins <- redistribute(userStore, validatorStore, inputs, outputs)
        } yield {
          v0.RedistributeResponse(createdCoins.map(cid => Proto.encode(cid)))
        }
      }
    }
  }

  private def getUserInstallContract(
      userWalletStore: EndUserWalletStore,
      userParty: PartyId,
  ): Future[Contract[walletCodegen.WalletAppInstall]] = for {
    installO <- userWalletStore.lookupInstall()
    install = installO match {
      case QueryResult(_, None) =>
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"WalletAppInstall contract of user $userParty")
        )
      case QueryResult(_, Some(install)) => install
    }
  } yield install

  /** Executes a wallet action by calling the `WalletAppInstall_ExecuteBatch` choice on the WalletAppInstall
    * contract of the given end user.
    *
    * The choice is always executed with the wallet service party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Additionally, the validator service party is also a readAs party (workaround for lack
    * of explicit disclosure for CoinRules).
    *
    * Note: curried syntax helps with type inference
    */
  private def exerciseWalletCoinAction[
      LookupResult,
      ExpectedCOO <: CoinOperationOutcome: ClassTag,
      ProtoResponse <: scalapb.GeneratedMessage,
  ](
      constructCoinOperation: (
          P.ContractId[walletCodegen.WalletAppInstall],
          EndUserWalletStore,
          // TODO(#1351): also require quantity to reject commands early?
      ) => Future[CoinOperationRequest[LookupResult]]
  )(
      user: String,
      processResponse: ExpectedCOO => ProtoResponse,
  )(implicit tc: TraceContext): Future[ProtoResponse] =
    for {
      userStore <- getUserStore(user)
      userTreasury <- getUserTreasury(user)
      userParty = userStore.key.endUserParty
      install <- getUserInstallContract(userStore, userParty)
      operation <- constructCoinOperation(install.contractId, userStore)
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
      actual: walletCodegen.CoinOperationOutcome
  )(implicit tc: TraceContext): ProtoReturnType = {
    // I (Arne) did not find a way to avoid ClassTag usage (or passing along a partial function) here
    // For example, passing along the `ExpectedCOO` type to the treasury service doesn't work
    // because inside the EndUserTreasuryService we have a Queue of
    // different coin operation outcomes and thus the type of that Queue needs to be CoinOperationOutcome
    // and it can't be the type of a particular coin operation outcome (like `ExpectedCOO`)
    val clazz = implicitly[ClassTag[ExpectedCOO]].runtimeClass
    actual match {
      case result: ExpectedCOO if clazz.isInstance(result) => process(result)
      case failedOperation: CoinOperationOutcome.COO_Error =>
        throw new StatusRuntimeException(
          Status.FAILED_PRECONDITION.withDescription(
            s"the coin operation failed with a Daml exception: ${failedOperation.body}."
          )
        )
      case other =>
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
          P.ContractId[walletCodegen.WalletAppInstall],
          EndUserWalletStore,
      ) => Future[P.Update[ChoiceResult]]
  )(
      user: String,
      getResponse: ChoiceResult => Response,
  )(implicit
      valueDecoder: ValueDecoder[ChoiceResult],
      traceContext: TraceContext,
  ): Future[Response] =
    for {
      userStore <- getUserStore(user)
      userParty = userStore.key.endUserParty
      install <- getUserInstallContract(userStore, userParty)
      update <- getUpdate(install.contractId, userStore)
      result <- connection
        .submitWithResult(
          Seq(walletServiceParty),
          Seq(validatorParty, userParty),
          update,
        )
    } yield getResponse(result)

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def selectCoin(
      userStore: EndUserWalletStore,
      quantity: BigDecimal,
  )(implicit tc: TraceContext): Future[P.ContractId[coinCodegen.Coin]] = {
    val owner = userStore.key.endUserParty
    for {
      validatorStore <- getUserStore(store.key.validatorUserName)
      currentRound <- validatorStore
        .getLatestOpenMiningRound(retryProvider)
        .map(_.value.payload.round.number)
      QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin)
      candidates = coins
        .filter(c => CoinUtil.currentQuantity(c.payload, currentRound) >= quantity)
    } yield {
      logger.debug(
        s"Selecting a coin for $owner with a remaining quantity of $quantity in round $currentRound. " +
          s"There are ${coins.length} coins, ${candidates.length} of which are big enough."
      )
      if (coins.isEmpty) {
        throw new RuntimeException(
          s"Party $owner has no coins. " +
            "Note that there is a very small delay before coins appear in the wallet. " +
            "If you have recently acquired some coins, retry the command."
        )
      } else if (candidates.isEmpty) {
        throw new RuntimeException(
          s"Party $owner has ${coins.length} coins, but none of them have enough remaining quantity." +
            "Note that there is a very small delay before coins appear in the wallet. " +
            "If you have recently acquired some coins, retry the command."
        )
      } else {
        // Use the coin that is most expensive to hold
        candidates.maxBy(_.payload.quantity.ratePerRound.rate).contractId
      }
    }
  }

  /** Verifies that exactly one delivery offer with the given contract id is active and returns a failed future with
    * an [[io.grpc.StatusRuntimeException]] otherwise.
    */
  private def lookupDeliveryOffer(
      endUserParty: PartyId,
      contractId: P.ContractId[walletCodegen.DeliveryOffer],
  ): Future[Unit] = {
    for {
      // TODO(#1267): use store instead
      activeDeliveryOffers <- connection.activeContracts(
        CoinLedgerConnection.transactionInterfaceFilterByParty(
          Map(endUserParty -> Seq(walletCodegen.DeliveryOffer.id))
        )
      )
      _ = if (
        activeDeliveryOffers
          .count(event => event.contractId == contractId) != 1
      )
        throw new StatusRuntimeException(
          Status.FAILED_PRECONDITION.withDescription(
            s"exactly one DeliveryOffer for payment request with cid $contractId."
          )
        )
    } yield ()
  }

  /** Verifies that exactly one subscription context with the given contract id is active and returns a failed future with
    * an [[io.grpc.StatusRuntimeException]] otherwise.
    */
  private def lookupSubscriptionContext(
      endUserParty: PartyId,
      contractId: P.ContractId[subsCodegen.SubscriptionContext],
  ): Future[Unit] = {
    for {
      // TODO(#1267): use store instead
      activeSubscriptionContexts <- connection.activeContracts(
        CoinLedgerConnection.transactionInterfaceFilterByParty(
          Map(endUserParty -> Seq(subsCodegen.SubscriptionContext.id))
        )
      )
      _ = if (
        activeSubscriptionContexts
          .count(event => event.contractId == contractId) != 1
      )
        throw new StatusRuntimeException(
          Status.FAILED_PRECONDITION.withDescription(
            s"exactly one SubscriptionContext with cid $contractId."
          )
        )
    } yield ()
  }

  /** Verifies that the given payment channel is active and returns a failed future with
    * an [[io.grpc.StatusRuntimeException]] otherwise.
    */
  private def lookupPaymentChannel(
      userStore: EndUserWalletStore,
      svcP: P.Party,
      senderP: P.Party,
      receiverP: P.Party,
  ): Future[P.ContractId[walletCodegen.PaymentChannel]] = for {
    channelO <- userStore
      .findContract(
        walletCodegen.PaymentChannel,
        filter = { c: Contract[walletCodegen.PaymentChannel] =>
          c.payload.svc == svcP && c.payload.receiver == receiverP && c.payload.sender == senderP
        },
      )
    channel = channelO.value.getOrElse(
      throw new StatusRuntimeException(
        Status.NOT_FOUND.withDescription(
          s"PaymentChannel between $svcP, $receiverP and ${userStore.key.endUserParty}."
        )
      )
    )
  } yield channel.contractId
}
