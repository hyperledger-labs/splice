package com.daml.network.wallet.admin.grpc

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  Contract => CodegenContract,
  ContractCompanion,
  ContractId,
  Update,
}
import com.daml.network.auth.AuthInterceptor
import com.daml.network.codegen.java.cc.api.v1
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
  paymentchannel as channelCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.{CoinUtil, JavaContract => Contract, Proto, TimeUtil}
import com.daml.network.wallet.store.EndUserWalletStore
import com.daml.network.wallet.treasury.{CoinOperationRequest, EndUserTreasuryService}
import com.daml.network.wallet.v0.*
import com.daml.network.wallet.{EndUserWalletManager, EndUserWalletService, v0}
import com.daml.network.v0 as networkV0
import com.digitalasset.canton.config.ClockConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.reflect.ClassTag

class GrpcWalletService(
    walletManager: EndUserWalletManager,
    ledgerClient: CoinLedgerClient,
    clockConfig: ClockConfig,
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
          QueryResult(_, optInstall) <- store.lookupInstallByName(user)
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

  private[this] def getUserWallet(user: String): EndUserWalletService =
    walletManager
      .lookupEndUserWallet(user)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(show"User ${user.singleQuoted}")
        )
      )

  private[this] def getUserStore(user: String): Future[EndUserWalletStore] = Future {
    getUserWallet(user).store
  }

  private[this] def getUserTreasury(user: String): Future[EndUserTreasuryService] = Future {
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
          QueryResult(_, contracts) <- userStore.listContracts(templateCompanion)
        } yield mkResponse(contracts.map(_.toProtoV0))
      }
    }

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        for {
          userStore <- getUserStore(user)
          validatorStore <- getUserStore(store.key.validatorUserName)
          now <- TimeUtil.getTime(connection, clockConfig)
          currentRound <- validatorStore
            .getLatestOpenMiningRound(retryProvider, now)
            .map(_.value.payload.round.number)
          QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin.COMPANION)
          QueryResult(_, lockedCoins) <- userStore.listContracts(coinCodegen.LockedCoin.COMPANION)
        } yield v0.ListResponse(
          coins.map(coinToCoinPosition(_, currentRound)),
          lockedCoins.map(lockedCoinToCoinPosition(_, currentRound)),
        )
      }
    }

  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletCoinAction((c, endUserWalletStore) => {
          val quantity = Proto.tryDecode(Proto.JavaBigDecimal)(request.quantity)
          Future.successful(
            CoinOperationRequest(
              (_: Unit) =>
                new coinoperation.CO_Tap(
                  store.key.svcParty.toProtoPrimitive,
                  validatorParty.toProtoPrimitive,
                  endUserWalletStore.key.endUserParty.toProtoPrimitive,
                  quantity,
                ),
              () => Future.successful(()),
            )
          )
        })(
          user,
          (outcome: coinoperationoutcome.COO_Tap) =>
            v0.TapResponse(Proto.encodeContractId(outcome.contractIdValue)),
        )
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
          validatorStore <- getUserStore(store.key.validatorUserName)
          QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin.COMPANION)
          QueryResult(_, lockedCoins) <- userStore.listContracts(coinCodegen.LockedCoin.COMPANION)
          now <- TimeUtil.getTime(connection, clockConfig)
          currentRound <- validatorStore
            .getLatestOpenMiningRound(retryProvider, now)
            .map(_.value.payload.round.number)
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

  private def getQueryResult[TCid <: ContractId[T], T](
      result: QueryResult[Option[Contract[TCid, T]]],
      errorMsg: String,
  ): Contract[TCid, T] = result match {
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
      withAuth { user =>
        exerciseWalletCoinAction((installCid, userStore) => {
          val requestCid = Proto.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
            request.requestContractId
          )

          def lookups = () =>
            for {
              paymentRequestO <- userStore.lookupAppPaymentRequestById(requestCid)
              paymentRequest = getQueryResult(
                paymentRequestO,
                s"app payment request with cid $requestCid",
              )
              _ <- userStore.lookupDeliveryOfferById(
                paymentRequest.payload.deliveryOffer
              )
            } yield ()

          Future.successful(
            CoinOperationRequest((_: Unit) => new coinoperation.CO_AppPayment(requestCid), lookups)
          )
        })(
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
          QueryResult(_, subscriptions) <- userStore.listContracts(
            subsCodegen.Subscription.COMPANION
          )
          QueryResult(_, subscriptionIdleStates) <- userStore.listContracts(
            subsCodegen.SubscriptionIdleState.COMPANION
          )
          QueryResult(_, subscriptionPayments) <- userStore.listContracts(
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
        exerciseWalletCoinAction((installCid, userStore) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
              request.requestContractId
            )

          def lookups = () =>
            for {
              subscriptionRequestO <- userStore.lookupSubscriptionRequestById(requestCid)
              subscriptionRequest = getQueryResult(
                subscriptionRequestO,
                s"subscription request with cid $requestCid",
              )
              _ <- userStore.lookupSubscriptionContextById(
                subscriptionRequest.payload.subscriptionData.context
              )
            } yield ()

          Future.successful(
            CoinOperationRequest(
              (_: Unit) => new coinoperation.CO_SubscriptionAcceptAndMakeInitialPayment(requestCid),
              lookups,
            )
          )
        })(
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

  override def listPaymentChannelProposals(
      request: v0.ListPaymentChannelProposalsRequest
  ): Future[v0.ListPaymentChannelProposalsResponse] =
    listContracts(
      channelCodegen.PaymentChannelProposal.COMPANION,
      v0.ListPaymentChannelProposalsResponse(_),
    )

  override def listPaymentChannels(
      request: v0.ListPaymentChannelsRequest
  ): Future[v0.ListPaymentChannelsResponse] =
    listContracts(
      channelCodegen.PaymentChannel.COMPANION,
      v0.ListPaymentChannelsResponse(_),
    )

  override def proposePaymentChannel(
      request: v0.ProposePaymentChannelRequest
  ): Future[v0.ProposePaymentChannelResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        // TODO(M3-02): this command should use command dedup, and the same will hold for proposing a transfer offer.
        exerciseWalletAction((installCid, userStore) => {
          val receiver = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          val proposer = userStore.key.endUserParty.toProtoPrimitive
          val channel = new channelCodegen.PaymentChannel(
            userStore.key.endUserParty.toProtoPrimitive,
            receiver.toProtoPrimitive,
            userStore.key.svcParty.toProtoPrimitive,
            request.allowRequests,
            request.allowOffers,
            request.allowDirectTransfers,
            Proto.tryDecode(Proto.JavaBigDecimal)(request.senderTransferFeeRatio),
          )
          val replacesChannel = request.replacesChannelId.map(
            Proto.tryDecodeJavaContractId(channelCodegen.PaymentChannel.COMPANION)
          )
          Future.successful(
            installCid.exerciseWalletAppInstall_CreatePaymentChannelProposal(
              proposer,
              channel,
              replacesChannel.toJava,
            )
          )
        })(
          user,
          cid => v0.ProposePaymentChannelResponse(Proto.encodeContractId(cid.exerciseResult)),
        )
      }
    }

  override def acceptPaymentChannelProposal(
      request: v0.AcceptPaymentChannelProposalRequest
  ): Future[v0.AcceptPaymentChannelProposalResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(channelCodegen.PaymentChannelProposal.COMPANION)(
              request.proposalContractId
            )
          Future.successful(
            installCid.exerciseWalletAppInstall_PaymentChannelProposal_Accept(
              requestCid
            )
          )
        })(
          user,
          cid => v0.AcceptPaymentChannelProposalResponse(Proto.encodeContractId(cid.exerciseResult)),
        )
      }
    }

  override def cancelPaymentChannelBySender(
      request: v0.CancelPaymentChannelBySenderRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, userStore) => {
          val senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
          val svcParty = userStore.key.svcParty
          for {
            channel <- lookupPaymentChannel(
              userStore,
              svcParty.toProtoPrimitive,
              senderP = senderParty.toProtoPrimitive,
              receiverP = userStore.key.endUserParty.toProtoPrimitive,
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
      withAuth { user =>
        exerciseWalletAction((installCid, userStore) => {
          val receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          val svcParty = userStore.key.svcParty
          for {
            channel <- lookupPaymentChannel(
              userStore,
              svcParty.toProtoPrimitive,
              senderP = userStore.key.endUserParty.toProtoPrimitive,
              receiverP = receiverParty.toProtoPrimitive,
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
      withAuth { user =>
        exerciseWalletCoinAction((c, userStore) => {
          val endUserParty = userStore.key.endUserParty
          val quantity = Proto.tryDecode(Proto.JavaBigDecimal)(request.quantity)
          val receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          Future.successful(
            CoinOperationRequest[channelCodegen.PaymentChannel.ContractId](
              (channelId) =>
                new coinoperation.CO_ChannelTransfer(
                  channelId,
                  new PaymentQuantity(quantity, Currency.CC),
                  "wallet: execute direct transfer",
                ),
              () =>
                lookupPaymentChannel(
                  userStore,
                  store.key.svcParty.toProtoPrimitive,
                  receiverP = receiverParty.toProtoPrimitive,
                  senderP = endUserParty.toProtoPrimitive,
                ),
            )
          )
        })(
          user,
          (_: coinoperationoutcome.COO_AcceptedChannelTransfer) => Empty(),
        )
      }
    }

  override def listAppRewards(
      request: v0.ListAppRewardsRequest
  ): Future[v0.ListAppRewardsResponse] =
    listContracts(
      coinCodegen.AppReward.COMPANION,
      v0.ListAppRewardsResponse(_),
    )

  override def listValidatorRewards(
      request: v0.ListValidatorRewardsRequest
  ): Future[v0.ListValidatorRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        for {
          userStore <- getUserStore(user)
          validatorRewards <- walletManager.listValidatorRewardsCollectableBy(userStore)
        } yield v0.ListValidatorRewardsResponse(validatorRewards.map(_.toProtoV0))
      }
    }

  override def collectRewards(request: CollectRewardsRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        for {
          userStore <- getUserStore(user)
          validatorStore <- getUserStore(store.key.validatorUserName)
          validatorRewards <- walletManager.listValidatorRewardsCollectableBy(userStore)
          validatorRewardInputs = validatorRewards
            .filter(c => c.payload.round.number == request.round)
            .map(c =>
              new v1.coin.transferinput.InputValidatorReward(
                c.contractId.toInterface(v1.coin.ValidatorReward.INTERFACE)
              )
            )
          QueryResult(_, appRewards) <- userStore.listContracts(coinCodegen.AppReward.COMPANION)
          appRewardInputs = appRewards
            .filter(c => c.payload.round.number == request.round)
            .map(c =>
              new v1.coin.transferinput.InputAppReward(
                c.contractId.toInterface(v1.coin.AppReward.INTERFACE)
              )
            )
          coinCid <- selectCoin(userStore, 0)
          inputCoin = new v1.coin.transferinput.InputCoin(
            coinCid.toInterface(v1.coin.Coin.INTERFACE)
          )
          inputs = (inputCoin +: validatorRewardInputs :++ appRewardInputs)
          outputs = Seq(
            new v1.coin.transferoutput.OutputSenderCoin(None.toJava, None.toJava)
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
      withAuth { user =>
        // TODO(M3-02): the command below should use command dedup. However, we'll remove payment requests, which will resolve that problem.
        exerciseWalletAction((installCid, userStore) => {
          val senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
          val svcParty = userStore.key.svcParty
          val quantity = Proto.tryDecode(Proto.JavaBigDecimal)(request.quantity)
          val description = request.description
          for {
            channel <- lookupPaymentChannel(
              userStore,
              svcParty.toProtoPrimitive,
              senderP = senderParty.toProtoPrimitive,
              receiverP = userStore.key.endUserParty.toProtoPrimitive,
            )
          } yield installCid.exerciseWalletAppInstall_PaymentChannel_CreatePaymentRequest(
            channel,
            new PaymentQuantity(quantity, Currency.CC),
            description,
          )
        })(
          user,
          cid =>
            v0.CreateOnChannelPaymentRequestResponse(requestContractId =
              Proto.encodeContractId(cid.exerciseResult)
            ),
        )
      }
    }

  override def listOnChannelPaymentRequests(
      request: v0.ListOnChannelPaymentRequestsRequest
  ): Future[v0.ListOnChannelPaymentRequestsResponse] =
    listContracts(
      channelCodegen.OnChannelPaymentRequest.COMPANION,
      v0.ListOnChannelPaymentRequestsResponse(_),
    )

  override def acceptOnChannelPaymentRequest(
      request: v0.AcceptOnChannelPaymentRequestRequest
  ): Future[Empty] = withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
    withAuth { user =>
      exerciseWalletCoinAction((installCid, userStore) => {
        val requestCid =
          Proto.tryDecodeJavaContractId(channelCodegen.OnChannelPaymentRequest.COMPANION)(
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
              senderP = userStore.key.endUserParty.toProtoPrimitive,
            )
          } yield ()

        Future.successful(
          CoinOperationRequest(
            (_: Unit) => new coinoperation.CO_ChannelPayment(requestCid),
            lookups,
          )
        )
      })(user, (_: coinoperationoutcome.COO_AcceptedChannelPayment) => Empty())
    }
  }

  override def rejectOnChannelPaymentRequest(
      request: v0.RejectOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(channelCodegen.OnChannelPaymentRequest.COMPANION)(
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
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val requestCid =
            Proto.tryDecodeJavaContractId(channelCodegen.OnChannelPaymentRequest.COMPANION)(
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

  override def createTransferOffer(
      request: CreateTransferOfferRequest
  ): Future[CreateTransferOfferResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth { user =>
        exerciseWalletAction((installCid, _) => {
          val receiver = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          val quantity = Proto.tryDecode(Proto.JavaBigDecimal)(request.quantity)
          val expiresAt = Proto.tryDecode(Proto.Timestamp)(request.expiresAt)
          Future.successful(
            installCid.exerciseWalletAppInstall_CreateTransferOffer(
              receiver.toProtoPrimitive,
              new PaymentQuantity(quantity, Currency.CC),
              request.description,
              expiresAt,
            )
          )
        })(
          user,
          cid => v0.CreateTransferOfferResponse(Proto.encodeContractId(cid.exerciseResult)),
        )
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

  // TODO(#1815) - Remove this
  private def redistribute(
      userStore: EndUserWalletStore,
      validatorStore: EndUserWalletStore,
      inputs: Seq[v1.coin.TransferInput],
      outputs: Seq[v1.coin.TransferOutput],
  )(implicit tc: TraceContext): Future[Seq[v1.coin.Coin.ContractId]] = {
    val user = userStore.key.endUserName
    val party = userStore.key.endUserParty
    exerciseWalletAction((installCid, _) => {
      val transfer = new v1.coin.Transfer(
        party.toProtoPrimitive,
        party.toProtoPrimitive,
        inputs.asJava,
        outputs.asJava,
        "redistribute",
      )
      for {
        now <- TimeUtil.getTime(connection, clockConfig)
        transferContext <- validatorStore.getPaymentTransferContext(retryProvider, now)
      } yield installCid.exerciseWalletAppInstall_CoinRules_TryTransfer(
        transferContext.coinRules,
        transfer,
        transferContext.context,
      )
    })(
      user,
      _.exerciseResult.createdCoins.asScala
        .collect { case coin: v1.coin.createdcoin.TransferResultCoin =>
          coin.contractIdValue
        }
        .toSeq,
    )
  }

  private def getUserInstallContract(
      userWalletStore: EndUserWalletStore,
      userParty: PartyId,
  ): Future[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ] =
    for {
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
          installCodegen.WalletAppInstall.ContractId,
          EndUserWalletStore,
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
      actual: installCodegen.CoinOperationOutcome
  )(implicit tc: TraceContext): ProtoReturnType = {
    // I (Arne) did not find a way to avoid ClassTag usage (or passing along a partial function) here
    // For example, passing along the `ExpectedCOO` type to the treasury service doesn't work
    // because inside the EndUserTreasuryService we have a Queue of
    // different coin operation outcomes and thus the type of that Queue needs to be CoinOperationOutcome
    // and it can't be the type of a particular coin operation outcome (like `ExpectedCOO`)
    val clazz = implicitly[ClassTag[ExpectedCOO]].runtimeClass
    actual match {
      case result: ExpectedCOO if clazz.isInstance(result) => process(result)
      case failedOperation: coinoperationoutcome.COO_Error =>
        throw new StatusRuntimeException(
          Status.FAILED_PRECONDITION.withDescription(
            s"the coin operation failed with a Daml exception: ${failedOperation.stringValue}."
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
          EndUserWalletStore,
      ) => Future[Update[ChoiceResult]]
  )(
      user: String,
      getResponse: ChoiceResult => Response,
  )(implicit
      traceContext: TraceContext
  ): Future[Response] =
    for {
      userStore <- getUserStore(user)
      userParty = userStore.key.endUserParty
      install <- getUserInstallContract(userStore, userParty)
      update <- getUpdate(install.contractId, userStore)
      result <- connection
        .submitWithResultNoDedup(
          Seq(walletServiceParty),
          Seq(validatorParty, userParty),
          update,
        )
    } yield getResponse(result)

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def selectCoin(
      userStore: EndUserWalletStore,
      quantity: BigDecimal,
  )(implicit tc: TraceContext): Future[coinCodegen.Coin.ContractId] = {
    val owner = userStore.key.endUserParty
    for {
      validatorStore <- getUserStore(store.key.validatorUserName)
      now <- TimeUtil.getTime(connection, clockConfig)
      currentRound <- validatorStore
        .getLatestOpenMiningRound(retryProvider, now)
        .map(_.value.payload.round.number)
      QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin.COMPANION)
      candidates = coins
        .filter(c => CoinUtil.currentQuantity(c.payload, currentRound).compareTo(quantity) >= 0)
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

  /** Verifies that the given payment channel is active and returns a failed future with
    * an [[io.grpc.StatusRuntimeException]] otherwise.
    */
  private def lookupPaymentChannel(
      userStore: EndUserWalletStore,
      svcP: String,
      senderP: String,
      receiverP: String,
  ): Future[channelCodegen.PaymentChannel.ContractId] = for {
    channelO <- userStore
      .findContract(
        channelCodegen.PaymentChannel.COMPANION,
        filter = {
          c: Contract[channelCodegen.PaymentChannel.ContractId, channelCodegen.PaymentChannel] =>
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
