// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.admin.http

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as validatorLicenseCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, LockedAmulet}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome.COO_AcceptedAppPayment
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.{
  AmuletOperationOutcome,
  amuletoperation,
  amuletoperationoutcome,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.environment.{
  CommandPriority,
  PackageVersionSupport,
  RetryFor,
  RetryProvider,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection.CommandId
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.http.v0.wallet.{WalletResource, WalletResource as r0}
import org.lfdecentralizedtrust.splice.http.v0.{definitions as d0, wallet as v0}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.{Limit, PageLimit}
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  ContractWithState,
  DisclosedContracts,
  SpliceUtil,
}
import org.lfdecentralizedtrust.splice.wallet.{UserWalletManager, UserWalletService}
import org.lfdecentralizedtrust.splice.wallet.store.{TxLogEntry, UserWalletStore}
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import TreasuryService.AmuletOperationDedupConfig
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferpreapproval.TransferPreapprovalProposal
import org.lfdecentralizedtrust.splice.wallet.util.{TopupUtil, ValidatorTopupConfig}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import io.circe.Json
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulettransferinstruction.AmuletTransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv1.allocationinstructionresult_output.{
  AllocationInstructionResult_Completed,
  AllocationInstructionResult_Failed,
  AllocationInstructionResult_Pending,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.AnyContract
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationinstructionv1,
  allocationv1,
  holdingv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.transferinstructionresult_output.{
  TransferInstructionResult_Completed,
  TransferInstructionResult_Failed,
  TransferInstructionResult_Pending,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AllocateAmuletRequest,
  CreateTokenStandardTransferRequest,
}

import java.math.RoundingMode as JRM
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

class HttpWalletHandler(
    override protected val walletManager: UserWalletManager,
    scanConnection: BftScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    validatorTopupConfig: ValidatorTopupConfig,
    dedupDuration: DedupDuration,
    packageVersionSupport: PackageVersionSupport,
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
        amulets <- userStore.multiDomainAcsStore.listContracts(
          amuletCodegen.Amulet.COMPANION
        )
        lockedAmulets <- userStore.multiDomainAcsStore.listContracts(
          amuletCodegen.LockedAmulet.COMPANION
        )
      } yield r0.ListResponseOK(
        d0.ListResponse(
          amulets.map(c => amuletToAmuletPosition(c, currentRound)).toVector,
          lockedAmulets.map(c => lockedAmuletToAmuletPosition(c, currentRound)).toVector,
        )
      )
    }
  }

  override def listAcceptedAppPayments(
      respond: v0.WalletResource.ListAcceptedAppPaymentsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListAcceptedAppPaymentsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContractsWithState(
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
      amuletCodegen.AppRewardCoupon.COMPANION,
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
        subscriptions <- userStore.listSubscriptions(walletManager.clock.now)
      } yield {
        v0.WalletResource.ListSubscriptionsResponseOK(
          d0.ListSubscriptionsResponse(
            subscriptions.map { subscription =>
              d0.Subscription(
                subscription.subscription.toHttp,
                subscription.state match {
                  case UserWalletStore.SubscriptionIdleState(contract) =>
                    d0.SubscriptionIdleState(idle = contract.toHttp)
                  case UserWalletStore.SubscriptionPaymentState(contract) =>
                    d0.SubscriptionPaymentState(payment = contract.toHttp)
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

  override def listValidatorFaucetCoupons(
      respond: v0.WalletResource.ListValidatorFaucetCouponsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListValidatorFaucetCouponsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      validatorLicenseCodegen.ValidatorFaucetCoupon.COMPANION,
      user,
      d0.ListValidatorFaucetCouponsResponse(_),
    )
  }

  override def listValidatorLivenessActivityRecords(
      respond: v0.WalletResource.ListValidatorLivenessActivityRecordsResponse.type
  )()(tuser: TracedUser): Future[v0.WalletResource.ListValidatorLivenessActivityRecordsResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      validatorLicenseCodegen.ValidatorLivenessActivityRecord.COMPANION,
      user,
      d0.ListValidatorLivenessActivityRecordsResponse(_),
    )
  }

  override def listSvRewardCoupons(respond: v0.WalletResource.ListSvRewardCouponsResponse.type)()(
      tUser: TracedUser
  ): Future[v0.WalletResource.ListSvRewardCouponsResponse] = {
    implicit val TracedUser(user, traceContext) = tUser
    listContracts(
      amuletCodegen.SvRewardCoupon.COMPANION,
      user,
      d0.ListSvRewardCouponsResponse(_),
    )
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
      } yield {
        v0.WalletResource.ListTransactionsResponse.OK(
          d0.ListTransactionsResponse(
            items = transactions.map(TxLogEntry.Http.toResponseItem).toVector
          )
        )
      }
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
        amuletRules <- scanConnection.getAmuletRulesWithState()
        result <- exerciseWalletAction((installCid, _) =>
          Future.successful(
            installCid
              .exerciseWalletAppInstall_FeaturedAppRights_SelfGrant(
                amuletRules.contractId
              )
              .map(_.exerciseResult.featuredAppRight)
          )
        )(user, disclosedContracts = _.disclosedContracts(amuletRules))
      } yield d0.SelfGrantFeaturedAppRightResponse(Codec.encodeContractId(result))
    }
  }
  override def acceptTransferOffer(respond: r0.AcceptTransferOfferResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.AcceptTransferOfferResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.acceptTransferOffer") { implicit traceContext => _ =>
      retryProvider.retryForClientCalls(
        "accept_transfer",
        "accept transfer offer",
        for {
          userStore <- getUserStore(user)
          commandPriority <-
            if (userStore.key.endUserParty != userStore.key.validatorParty)
              Future.successful(CommandPriority.Low)
            else
              TopupUtil
                .hasSufficientFundsForTopup(
                  scanConnection,
                  userStore,
                  validatorTopupConfig,
                  walletManager.clock,
                )
                .map(if (_) CommandPriority.Low else CommandPriority.High): Future[CommandPriority]
          outcome <-
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
                      Codec.encodeContractId(cid.exerciseResult.acceptedTransferOffer)
                    ): r0.AcceptTransferOfferResponse
                  }
              )
            })(
              user,
              priority = commandPriority,
            )
        } yield outcome,
        logger,
      )
    }
  }
  override def rejectTransferOffer(respond: r0.RejectTransferOfferResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.RejectTransferOfferResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.rejectTransferOffer") { implicit traceContext => _ =>
      retryProvider.retryForClientCalls(
        "reject_transfer",
        "reject transfer offer",
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
        ),
        logger,
      )
    }
  }

  override def withdrawTransferOffer(respond: r0.WithdrawTransferOfferResponse.type)(
      contractId: String
  )(tuser: TracedUser): Future[r0.WithdrawTransferOfferResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.withdrawTransferOffer") { implicit traceContext => _ =>
      retryProvider.retryForClientCalls(
        "withdraw_transfer",
        "withdraw transfer offer",
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
        ),
        logger,
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
        "accept_app_payment",
        s"Accept app payment request  for contract $requestCid",
        exerciseWalletAmuletAction(
          new amuletoperation.CO_AppPayment(requestCid),
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
        "reject_app_payment",
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
        "accept_subscription",
        "Accept subscription and make initial payment",
        exerciseWalletAmuletAction(
          new amuletoperation.CO_SubscriptionAcceptAndMakeInitialPayment(requestCid),
          user,
          (outcome: amuletoperationoutcome.COO_SubscriptionInitialPayment) =>
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
      val requestCid =
        Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionIdleState.COMPANION)(
          contractId
        )
      retryProvider.retryForClientCalls(
        "cancel_subscription",
        "Cancel subscription",
        exerciseWalletAction((installCid, _) =>
          Future.successful(
            installCid
              .exerciseWalletAppInstall_SubscriptionIdleState_CancelSubscription(
                requestCid
              )
              .map(_ => r0.CancelSubscriptionRequestResponseOK)
          )
        )(user),
        logger,
      )
    }
  }

  override def rejectSubscriptionRequest(
      respond: r0.RejectSubscriptionRequestResponse.type
  )(contractId: String)(tuser: TracedUser): Future[r0.RejectSubscriptionRequestResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.rejectSubscriptionRequest") { implicit traceContext => _ =>
      val requestCid = Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(
        contractId
      )
      retryProvider.retryForClientCalls(
        "reject_subscription",
        "Reject subscription",
        exerciseWalletAction((installCid, _) =>
          Future.successful(
            installCid
              .exerciseWalletAppInstall_SubscriptionRequest_Reject(requestCid)
              .map(_ => r0.RejectSubscriptionRequestResponseOK)
          )
        )(user),
        logger,
      )
    }
  }

  override def getBalance(respond: r0.GetBalanceResponse.type)()(
      tuser: TracedUser
  ): Future[r0.GetBalanceResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.getBalance") { _ => _ =>
      for {
        userStore <- getUserStore(user)
        currentRound <- scanConnection
          .getLatestOpenMiningRound()
          .map(_.payload.round.number)
        (unlockedQty, unlockedHoldingFees) <- userStore.getAmuletBalanceWithHoldingFees(
          currentRound
        )
        lockedQty <- userStore.getLockedAmuletBalance(currentRound)
      } yield {
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
      for {
        userStore <- getUserStore(user)
        commandId = CommandId(
          "org.lfdecentralizedtrust.splice.wallet.tap",
          Seq(userStore.key.endUserParty),
          request.commandId.getOrElse(UUID.randomUUID().toString),
        )
        r <- retryProvider.retryForClientCalls(
          "tap",
          "Tap",
          for {
            (openRounds, _) <- scanConnection.getOpenAndIssuingMiningRounds()
            openRound = SpliceUtil.selectLatestOpenMiningRound(walletManager.clock.now, openRounds)
            result <- exerciseWalletAmuletAction[amuletoperationoutcome.COO_Tap, d0.TapResponse](
              operation = new amuletoperation.CO_Tap(
                amount.divide(openRound.payload.amuletPrice, JRM.CEILING)
              ),
              user = user,
              processResponse = (outcome: amuletoperationoutcome.COO_Tap) =>
                d0.TapResponse(Codec.encodeContractId(outcome.contractIdValue)),
              dedupConfig = Some(
                AmuletOperationDedupConfig(
                  commandId,
                  dedupDuration,
                )
              ),
            )
          } yield result,
          logger,
        )
      } yield r
    }
  }

  override def userStatus(respond: r0.UserStatusResponse.type)()(
      tuser: TracedUser
  ): Future[r0.UserStatusResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.userStatus") { implicit traceContext => _ =>
      for {
        optWallet <- walletManager.lookupUserWallet(user)
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
            retryProvider.retryForClientCalls(
              "cancel_featured_app_rights",
              "Cancel featured app rights",
              exerciseWalletAction((installCid, _) => {
                Future.successful(
                  installCid
                    .exerciseWalletAppInstall_FeaturedAppRights_Cancel(cid.contractId)
                    .map(_ => r0.CancelFeaturedAppRightsResponseOK)
                )
              })(user),
              logger,
            )
        }
      } yield result
    }
  }

  override def createTransferPreapproval(
      respond: r0.CreateTransferPreapprovalResponse.type
  )()(tuser: TracedUser): Future[r0.CreateTransferPreapprovalResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.createTransferPreapproval") { implicit traceContext => _ =>
      for {
        wallet <- getUserWallet(user)
        store = wallet.store
        domain <- scanConnection.getAmuletRulesDomain()(traceContext)
        result <- store.lookupTransferPreapproval(store.key.endUserParty) flatMap {
          case QueryResult(_, Some(existingPreapproval)) =>
            Future.successful(
              r0.CreateTransferPreapprovalResponse.Conflict(
                d0.CreateTransferPreapprovalResponse(
                  existingPreapproval.contractId.contractId
                )
              )
            )
          case QueryResult(preapprovalOffset, None) =>
            for {
              proposalCid <- store.lookupTransferPreapprovalProposal(
                store.key.endUserParty
              ) flatMap {
                case QueryResult(_, Some(proposal)) => Future.successful(proposal.contractId)
                case QueryResult(proposalOffSet, None) =>
                  val dedupOffset = Ordering[Long].min(preapprovalOffset, proposalOffSet)
                  createTransferPreapprovalProposal(wallet, domain, dedupOffset)
              }
              _ = logger.debug(
                s"Created TransferPreapprovalProposal with contract ID $proposalCid. Now waiting for automation to create the TransferPreapproval."
              )
              preapproval <- retryProvider.retry(
                RetryFor.InitializingClientCalls,
                "getTransferPreapproval",
                "wait for validator automation to create TransferPreapproval",
                store.getTransferPreapproval(store.key.endUserParty),
                logger,
              ) recover {
                case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
                  throw Status.ABORTED
                    .withDescription(
                      "Transfer preapproval creation request timed out because validator automation did not complete it in time. " +
                        "This is most likely because the validator has insufficient funds to cover the transfer preapproval creation fee. " +
                        "Please contact your validator administrator or retry in some time."
                    )
                    .asRuntimeException()
              }
            } yield r0.CreateTransferPreapprovalResponse.OK(
              d0.CreateTransferPreapprovalResponse(
                preapproval.contractId.contractId
              )
            )
        }
      } yield result
    }
  }

  private def createTransferPreapprovalProposal(
      wallet: UserWalletService,
      domain: SynchronizerId,
      dedupOffset: Long,
  )(implicit tc: TraceContext) = {
    val store = wallet.store
    wallet.connection
      .submit(
        Seq(store.key.validatorParty, store.key.endUserParty),
        Seq.empty,
        new TransferPreapprovalProposal(
          store.key.endUserParty.toProtoPrimitive,
          store.key.validatorParty.toProtoPrimitive,
        ).create,
      )
      .withDedup(
        SpliceLedgerConnection.CommandId(
          "org.lfdecentralizedtrust.splice.wallet.createTransferPreapprovalProposal",
          Seq(
            store.key.endUserParty,
            store.key.validatorParty,
          ),
        ),
        deduplicationOffset = dedupOffset,
      )
      .withSynchronizerId(domain)
      .yieldResult()
      .map(_.contractId)
  }

  def transferPreapprovalSend(respond: r0.TransferPreapprovalSendResponse.type)(
      body: d0.TransferPreapprovalSendRequest
  )(tuser: TracedUser): Future[r0.TransferPreapprovalSendResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.transferPreapprovalSend") { _ => _ =>
      val receiver = Codec.tryDecode(Codec.Party)(body.receiverPartyId)
      val amount = Codec.tryDecode(Codec.JavaBigDecimal)(body.amount)
      scanConnection.lookupTransferPreapprovalByParty(receiver).flatMap {
        case None =>
          Future.failed(
            Status.INVALID_ARGUMENT
              .withDescription(s"Receiver $receiver does not have a TransferPreapproval")
              .asRuntimeException
          )
        case Some(preapproval) =>
          for {
            wallet <- getUserWallet(user)
            sender = wallet.store.key.endUserParty
            featuredAppRight <- scanConnection
              .lookupFeaturedAppRight(PartyId.tryFromProtoPrimitive(preapproval.payload.provider))
            supportsDescription <- packageVersionSupport
              .supportsDescriptionInTransferPreapprovals(
                Seq(receiver, sender, wallet.store.key.dsoParty),
                walletManager.clock.now,
              )
              .map(_.supported)
            result <- exerciseWalletAmuletAction(
              new amuletoperation.CO_TransferPreapprovalSend(
                preapproval.contractId,
                featuredAppRight.map(_.contractId).toJava,
                amount,
                Option.when(supportsDescription)(body.description).flatten.toJava,
              ),
              user,
              (_: amuletoperationoutcome.COO_TransferPreapprovalSend) =>
                r0.TransferPreapprovalSendResponse.OK,
              extraDisclosedContracts = wallet.connection.disclosedContracts(
                preapproval
              ),
              dedupConfig = Some(
                AmuletOperationDedupConfig(
                  CommandId(
                    "transferPreapprovalSend",
                    Seq(sender),
                    body.deduplicationId,
                  ),
                  dedupDuration,
                )
              ),
            )
          } yield result
      }
    }
  }

  override def createTokenStandardTransfer(
      respond: WalletResource.CreateTokenStandardTransferResponse.type
  )(
      request: CreateTokenStandardTransferRequest
  )(extracted: TracedUser): Future[WalletResource.CreateTokenStandardTransferResponse] = {
    implicit val TracedUser(user, traceContext) = extracted
    withSpan(s"$workflowId.createTokenStandardTransfer") { _ => _ =>
      (for {
        userWallet <- getUserWallet(user)
        commandId = CommandId(
          "org.lfdecentralizedtrust.splice.wallet.createTokenStandardTransfer",
          Seq(userWallet.store.key.endUserParty),
          request.trackingId,
        )
        dedupConfig = AmuletOperationDedupConfig(
          commandId,
          dedupDuration,
        )
        result <- userWallet.treasury.enqueueTokenStandardTransferOperation(
          Codec.tryDecode(Codec.Party)(request.receiverPartyId),
          BigDecimal(request.amount),
          request.description,
          Codec.tryDecode(Codec.Timestamp)(request.expiresAt),
          dedup = Some(dedupConfig),
        )
      } yield WalletResource.CreateTokenStandardTransferResponse.OK(
        transferInstructionResultToResponse(result)
      )).transform(
        HttpErrorHandler.onGrpcAlreadyExists("CreateTokenStandardTransfer duplicate command")
      )
    }
  }

  private def transferInstructionResultToResponse(
      result: transferinstructionv1.TransferInstructionResult
  ): d0.TransferInstructionResultResponse = {
    d0.TransferInstructionResultResponse(
      result.output match {
        case completed: TransferInstructionResult_Completed =>
          d0.TransferInstructionCompleted(
            completed.receiverHoldingCids.asScala.map(_.contractId).toVector
          )
        case _: TransferInstructionResult_Failed => d0.TransferInstructionFailed()
        case pending: TransferInstructionResult_Pending =>
          d0.TransferInstructionPending(pending.transferInstructionCid.contractId)
        case x =>
          throw new IllegalArgumentException(s"Unexpected TransferInstructionResult: $x")
      },
      result.senderChangeCids.asScala.map(_.contractId).toVector,
      result.meta.values.asScala.toMap,
    )
  }

  override def listTokenStandardTransfers(
      respond: WalletResource.ListTokenStandardTransfersResponse.type
  )()(tuser: TracedUser): Future[WalletResource.ListTokenStandardTransfersResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    listContracts(
      AmuletTransferInstruction.COMPANION,
      user,
      contracts =>
        WalletResource.ListTokenStandardTransfersResponse.OK(
          d0.ListTokenStandardTransfersResponse(contracts)
        ),
    )
  }

  override def acceptTokenStandardTransfer(
      respond: WalletResource.AcceptTokenStandardTransferResponse.type
  )(contractId: String)(
      tUser: TracedUser
  ): Future[WalletResource.AcceptTokenStandardTransferResponse] = {
    implicit val TracedUser(user, traceContext) = tUser
    withSpan(s"$workflowId.acceptTokenStandardTransfer") { implicit traceContext => _ =>
      val requestCid = Codec.tryDecodeJavaContractIdInterface(
        transferinstructionv1.TransferInstruction.INTERFACE
      )(
        contractId
      )
      for {
        choiceContext <- scanConnection.getTransferInstructionAcceptContext(requestCid)
        outcome <- exerciseWalletAction((installCid, _) => {
          Future.successful(
            installCid
              .exerciseWalletAppInstall_TransferInstruction_Accept(
                requestCid,
                new transferinstructionv1.TransferInstruction_Accept(choiceContext.toExtraArgs()),
              )
          )
        })(
          user,
          disclosedContracts = _ => DisclosedContracts.fromProto(choiceContext.disclosedContracts),
        )
      } yield WalletResource.AcceptTokenStandardTransferResponseOK(
        transferInstructionResultToResponse(outcome.exerciseResult)
      )
    }
  }

  override def rejectTokenStandardTransfer(
      respond: WalletResource.RejectTokenStandardTransferResponse.type
  )(contractId: String)(
      tUser: TracedUser
  ): Future[WalletResource.RejectTokenStandardTransferResponse] = {
    implicit val TracedUser(user, traceContext) = tUser
    withSpan(s"$workflowId.rejectTokenStandardTransfer") { implicit traceContext => _ =>
      val requestCid = Codec.tryDecodeJavaContractIdInterface(
        transferinstructionv1.TransferInstruction.INTERFACE
      )(
        contractId
      )
      for {
        choiceContext <- scanConnection.getTransferInstructionRejectContext(requestCid)
        outcome <- exerciseWalletAction((installCid, _) => {
          Future.successful(
            installCid
              .exerciseWalletAppInstall_TransferInstruction_Reject(
                requestCid,
                new transferinstructionv1.TransferInstruction_Reject(choiceContext.toExtraArgs()),
              )
          )
        })(
          user,
          disclosedContracts = _ => DisclosedContracts.fromProto(choiceContext.disclosedContracts),
        )
      } yield WalletResource.RejectTokenStandardTransferResponseOK(
        transferInstructionResultToResponse(outcome.exerciseResult)
      )
    }
  }

  override def withdrawTokenStandardTransfer(
      respond: WalletResource.WithdrawTokenStandardTransferResponse.type
  )(contractId: String)(
      tUser: TracedUser
  ): Future[WalletResource.WithdrawTokenStandardTransferResponse] = {
    implicit val TracedUser(user, traceContext) = tUser
    withSpan(s"$workflowId.withdrawTokenStandardTransfer") { implicit traceContext => _ =>
      val requestCid = Codec.tryDecodeJavaContractIdInterface(
        transferinstructionv1.TransferInstruction.INTERFACE
      )(
        contractId
      )
      for {
        choiceContext <- scanConnection.getTransferInstructionWithdrawContext(requestCid)
        outcome <- exerciseWalletAction((installCid, _) => {
          Future.successful(
            installCid
              .exerciseWalletAppInstall_TransferInstruction_Withdraw(
                requestCid,
                new transferinstructionv1.TransferInstruction_Withdraw(choiceContext.toExtraArgs()),
              )
          )
        })(
          user,
          disclosedContracts = _ => DisclosedContracts.fromProto(choiceContext.disclosedContracts),
        )
      } yield WalletResource.WithdrawTokenStandardTransferResponseOK(
        transferInstructionResultToResponse(outcome.exerciseResult)
      )
    }
  }

  override def allocateAmulet(respond: WalletResource.AllocateAmuletResponse.type)(
      body: AllocateAmuletRequest
  )(extracted: TracedUser): Future[WalletResource.AllocateAmuletResponse] = {
    implicit val TracedUser(user, traceContext) = extracted
    withSpan(s"$workflowId.allocateAmulet") { _ => _ =>
      val now = walletManager.clock.now.toInstant
      for {
        userWallet <- getUserWallet(user)
        sender = userWallet.store.key.endUserParty
        commandId = CommandId(
          "org.lfdecentralizedtrust.splice.wallet.allocateAmulet",
          Seq(sender),
          Seq(
            body.settlement.executor,
            body.settlement.settlementRef.id,
            body.settlement.settlementRef.cid.getOrElse(""),
            body.transferLegId,
          ),
        )
        specification = new allocationv1.AllocationSpecification(
          new allocationv1.SettlementInfo(
            body.settlement.executor,
            new allocationv1.Reference(
              body.settlement.settlementRef.id,
              body.settlement.settlementRef.cid.map(cid => new AnyContract.ContractId(cid)).toJava,
            ),
            now,
            Codec.tryDecode(Codec.Timestamp)(body.settlement.allocateBefore).toInstant,
            Codec.tryDecode(Codec.Timestamp)(body.settlement.settleBefore).toInstant,
            new metadatav1.Metadata(body.settlement.meta.getOrElse(Map.empty).asJava),
          ),
          body.transferLegId,
          new allocationv1.TransferLeg(
            sender.toProtoPrimitive,
            body.transferLeg.receiver,
            Codec.tryDecode(Codec.JavaBigDecimal)(body.transferLeg.amount),
            new holdingv1.InstrumentId(userWallet.store.key.dsoParty.toProtoPrimitive, "Amulet"),
            new metadatav1.Metadata(body.transferLeg.meta.getOrElse(Map.empty).asJava),
          ),
        )
        dedupConfig = AmuletOperationDedupConfig(
          commandId,
          dedupDuration,
        )
        result <- userWallet.treasury.enqueueAmuletAllocationOperation(
          specification,
          requestedAt = now,
          dedup = Some(dedupConfig),
        )
      } yield WalletResource.AllocateAmuletResponse.OK(allocationResultToResponse(result))
    }
  }

  private def allocationResultToResponse(
      result: allocationinstructionv1.AllocationInstructionResult
  ): d0.AllocateAmuletResponse = {
    d0.AllocateAmuletResponse(
      result.output match {
        case completed: AllocationInstructionResult_Completed =>
          d0.AllocationInstructionResultCompleted(allocationCid =
            completed.allocationCid.contractId
          )
        case _: AllocationInstructionResult_Failed =>
          d0.AllocationInstructionResultFailed()
        case pending: AllocationInstructionResult_Pending =>
          d0.AllocationInstructionResultPending(allocationInstructionCid =
            pending.allocationInstructionCid.contractId
          )
        case x =>
          throw new IllegalArgumentException(s"Unexpected AllocationInstructionResult: $x")
      },
      result.senderChangeCids.asScala.map(_.contractId).toVector,
      result.meta.values.asScala.toMap,
    )
  }

  private def amuletToAmuletPosition(
      amulet: ContractWithState[Amulet.ContractId, Amulet],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): d0.AmuletPosition = {
    d0.AmuletPosition(
      amulet.toHttp,
      round,
      Codec.encode(SpliceUtil.holdingFee(amulet.payload, round)),
      Codec.encode(SpliceUtil.currentAmount(amulet.payload, round)),
    )
  }

  private def lockedAmuletToAmuletPosition(
      lockedAmulet: ContractWithState[LockedAmulet.ContractId, LockedAmulet],
      round: Long,
  )(implicit errorLoggingContext: ErrorLoggingContext): d0.AmuletPosition =
    d0.AmuletPosition(
      lockedAmulet.toHttp,
      round,
      Codec.encode(SpliceUtil.holdingFee(lockedAmulet.payload.amulet, round)),
      Codec.encode(SpliceUtil.currentAmount(lockedAmulet.payload.amulet, round)),
    )

  private[this] def getUserTreasury(user: String)(implicit
      tc: TraceContext
  ): Future[TreasuryService] =
    getUserWallet(user).map(_.treasury)

  /** Executes a wallet action by calling the `WalletAppInstall_ExecuteBatch` choice on the WalletAppInstall
    * contract of the given end user.
    *
    * The choice is always executed with the validator party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Additionally, the validator service party is also a readAs party (workaround for lack
    * of explicit disclosure for AmuletRules).
    */
  private def exerciseWalletAmuletAction[
      ExpectedCOO <: AmuletOperationOutcome: ClassTag,
      R,
  ](
      operation: installCodegen.AmuletOperation,
      user: String,
      processResponse: ExpectedCOO => R,
      dedupConfig: Option[AmuletOperationDedupConfig] = None,
      extraDisclosedContracts: DisclosedContracts = DisclosedContracts.Empty,
  )(implicit tc: TraceContext): Future[R] =
    for {
      userTreasury <- getUserTreasury(user)
      res <- userTreasury
        .enqueueAmuletOperation(
          operation,
          dedup = dedupConfig,
          extraDisclosedContracts = extraDisclosedContracts,
        )
        .map(processCOO[ExpectedCOO, R](processResponse))
    } yield res

  /** Helper function to process a AmuletOperationOutcome.
    * Ensures that the outcome is of the expected type and throws an appropriate exception if it isn't.
    */
  private def processCOO[
      ExpectedCOO <: AmuletOperationOutcome: ClassTag,
      R,
  ](
      process: ExpectedCOO => R
  )(
      actual: installCodegen.AmuletOperationOutcome
  )(implicit tc: TraceContext): R = {
    // I (Arne) did not find a way to avoid ClassTag usage (or passing along a partial function) here
    // For example, passing along the `ExpectedCOO` type to the treasury service doesn't work
    // because inside the TreasuryService we have a Queue of
    // different amulet operation outcomes and thus the type of that Queue needs to be AmuletOperationOutcome
    // and it can't be the type of a particular amulet operation outcome (like `ExpectedCOO`)
    val clazz = implicitly[ClassTag[ExpectedCOO]].runtimeClass
    actual match {
      case result: ExpectedCOO if clazz.isInstance(result) => process(result)
      case failedOperation: amuletoperationoutcome.COO_Error =>
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"the amulet operation failed with a Daml exception: ${failedOperation}."
          )
          .asRuntimeException()
      case _ =>
        ErrorUtil.internalErrorGrpc(
          s"expected to receive a amulet operation outcome of type $clazz or `COO_Error` but received type ${actual.getClass} with value: $actual"
        )
    }
  }

  override def featureSupport(respond: WalletResource.FeatureSupportResponse.type)()(
      tuser: TracedUser
  ): Future[WalletResource.FeatureSupportResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.featureSupport") { _ => _ =>
      val parties = Seq(store.walletKey.dsoParty)
      val now = CantonTimestamp.now()
      for {
        tokenStandard <- packageVersionSupport.supportsTokenStandard(parties, now)
        preapprovalDescription <- packageVersionSupport.supportsDescriptionInTransferPreapprovals(
          parties,
          now,
        )
      } yield WalletResource.FeatureSupportResponse.OK(
        d0.WalletFeatureSupportResponse(
          tokenStandard = tokenStandard.supported,
          transferPreapprovalDescription = preapprovalDescription.supported,
        )
      )
    }
  }
}
