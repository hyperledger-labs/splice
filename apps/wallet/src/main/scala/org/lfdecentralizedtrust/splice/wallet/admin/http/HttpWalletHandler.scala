// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.admin.http

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation as amuletAllocationCodegen
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
  mintingdelegation as mintingDelegationCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
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
  ChoiceContextWithDisclosures,
  Codec,
  ContractWithState,
  DisclosedContracts,
  SpliceUtil,
}
import org.lfdecentralizedtrust.splice.wallet.{UserWalletManager, UserWalletService}
import org.lfdecentralizedtrust.splice.wallet.store.{TxLogEntry, UserWalletStore}
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import TreasuryService.AmuletOperationDedupConfig
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
  allocationrequestv1,
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
import org.lfdecentralizedtrust.splice.wallet.admin.http.UserWalletAuthExtractor.WalletUserRequest

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
) extends v0.WalletHandler[WalletUserRequest]
    with HttpWalletHandlerUtil {
  protected val workflowId = this.getClass.getSimpleName

  override def list(respond: r0.ListResponse.type)()(
      tuser: WalletUserRequest
  ): Future[r0.ListResponse] = {
    implicit val WalletUserRequest(_, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.list") { _ => _ =>
      for {
        currentRound <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
        amulets <- userWallet.store.multiDomainAcsStore.listContracts(
          amuletCodegen.Amulet.COMPANION
        )
        lockedAmulets <- userWallet.store.multiDomainAcsStore.listContracts(
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
  )()(tuser: WalletUserRequest): Future[v0.WalletResource.ListAcceptedAppPaymentsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    listContractsWithState(
      walletCodegen.AcceptedAppPayment.COMPANION,
      userWallet.store,
      d0.ListAcceptedAppPaymentsResponse(_),
    )
  }

  override def listAcceptedTransferOffers(
      respond: v0.WalletResource.ListAcceptedTransferOffersResponse.type
  )()(tuser: WalletUserRequest): Future[v0.WalletResource.ListAcceptedTransferOffersResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    listContracts(
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
      userWallet.store,
      d0.ListAcceptedTransferOffersResponse(_),
    )
  }

  override def getAppPaymentRequest(respond: r0.GetAppPaymentRequestResponse.type)(
      contractId: String
  )(tuser: WalletUserRequest): Future[r0.GetAppPaymentRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.getAppPaymentRequest") { _ => _ =>
      val requestCid =
        Codec.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(contractId)
      for {
        appPaymentRequest <- userWallet.store.getAppPaymentRequest(requestCid)
      } yield r0.GetAppPaymentRequestResponseOK(
        appPaymentRequest.toHttp
      )
    }
  }

  override def listAppPaymentRequests(
      respond: v0.WalletResource.ListAppPaymentRequestsResponse.type
  )()(tuser: WalletUserRequest): Future[v0.WalletResource.ListAppPaymentRequestsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listAppPaymentRequests") { _ => _ =>
      for {
        appPaymentRequests <- userWallet.store.listAppPaymentRequests()
      } yield d0.ListAppPaymentRequestsResponse(appPaymentRequests.map(_.toHttp).toVector)
    }
  }

  override def listAppRewardCoupons(respond: v0.WalletResource.ListAppRewardCouponsResponse.type)()(
      tuser: WalletUserRequest
  ): Future[v0.WalletResource.ListAppRewardCouponsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    listContracts(
      amuletCodegen.AppRewardCoupon.COMPANION,
      userWallet.store,
      d0.ListAppRewardCouponsResponse(_),
    )
  }

  override def listSubscriptionInitialPayments(
      respond: v0.WalletResource.ListSubscriptionInitialPaymentsResponse.type
  )()(
      tuser: WalletUserRequest
  ): Future[v0.WalletResource.ListSubscriptionInitialPaymentsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    listContracts(
      subsCodegen.SubscriptionInitialPayment.COMPANION,
      userWallet.store,
      d0.ListSubscriptionInitialPaymentsResponse(_),
    )
  }

  override def listSubscriptionRequests(
      respond: v0.WalletResource.ListSubscriptionRequestsResponse.type
  )()(tuser: WalletUserRequest): Future[v0.WalletResource.ListSubscriptionRequestsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listSubscriptionRequests") { _ => _ =>
      for {
        subRequests <- userWallet.store.listSubscriptionRequests()
      } yield {
        d0.ListSubscriptionRequestsResponse(subRequests.map(_.toHttp).toVector)
      }
    }
  }

  override def listSubscriptions(respond: v0.WalletResource.ListSubscriptionsResponse.type)()(
      tuser: WalletUserRequest
  ): Future[v0.WalletResource.ListSubscriptionsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser

    withSpan(s"$workflowId.listSubscriptions") { implicit traceContext => _ =>
      for {
        subscriptions <- userWallet.store.listSubscriptions(walletManager.clock.now)
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
  )()(tuser: WalletUserRequest): Future[v0.WalletResource.ListValidatorRewardCouponsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listValidatorRewardCoupons") { implicit traceContext => _ =>
      for {
        validatorRewardCoupons <- walletManager.listValidatorRewardCouponsCollectableBy(
          userWallet.store,
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
  )()(tuser: WalletUserRequest): Future[v0.WalletResource.ListValidatorFaucetCouponsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    listContracts(
      validatorLicenseCodegen.ValidatorFaucetCoupon.COMPANION,
      userWallet.store,
      d0.ListValidatorFaucetCouponsResponse(_),
    )
  }

  override def listValidatorLivenessActivityRecords(
      respond: v0.WalletResource.ListValidatorLivenessActivityRecordsResponse.type
  )()(
      tuser: WalletUserRequest
  ): Future[v0.WalletResource.ListValidatorLivenessActivityRecordsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    listContracts(
      validatorLicenseCodegen.ValidatorLivenessActivityRecord.COMPANION,
      userWallet.store,
      d0.ListValidatorLivenessActivityRecordsResponse(_),
    )
  }

  override def listSvRewardCoupons(respond: v0.WalletResource.ListSvRewardCouponsResponse.type)()(
      tUser: WalletUserRequest
  ): Future[v0.WalletResource.ListSvRewardCouponsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tUser
    listContracts(
      amuletCodegen.SvRewardCoupon.COMPANION,
      userWallet.store,
      d0.ListSvRewardCouponsResponse(_),
    )
  }

  override def listTransactions(
      respond: v0.WalletResource.ListTransactionsResponse.type
  )(
      request: d0.ListTransactionsRequest
  )(tuser: WalletUserRequest): Future[r0.ListTransactionsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listTransactions") { implicit traceContext => _ =>
      val beginAfterId = if (request.beginAfterId.exists(_.isEmpty)) None else request.beginAfterId
      for {
        transactions <- userWallet.store.listTransactions(
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
      tuser: WalletUserRequest
  ): Future[r0.SelfGrantFeatureAppRightResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
        )(userWallet, disclosedContracts = _.disclosedContracts(amuletRules))
      } yield d0.SelfGrantFeaturedAppRightResponse(Codec.encodeContractId(result))
    }
  }
  override def acceptTransferOffer(respond: r0.AcceptTransferOfferResponse.type)(
      contractId: String
  )(tuser: WalletUserRequest): Future[r0.AcceptTransferOfferResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.acceptTransferOffer") { implicit traceContext => _ =>
      retryProvider.retryForClientCalls(
        "accept_transfer",
        "accept transfer offer",
        for {
          commandPriority <-
            if (userWallet.store.key.endUserParty != userWallet.store.key.validatorParty)
              Future.successful(CommandPriority.Low)
            else
              TopupUtil
                .hasSufficientFundsForTopup(
                  scanConnection,
                  userWallet.store,
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
              userWallet,
              priority = commandPriority,
            )
        } yield outcome,
        logger,
      )
    }
  }
  override def rejectTransferOffer(respond: r0.RejectTransferOfferResponse.type)(
      contractId: String
  )(tuser: WalletUserRequest): Future[r0.RejectTransferOfferResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
          userWallet
        ),
        logger,
      )
    }
  }

  override def withdrawTransferOffer(respond: r0.WithdrawTransferOfferResponse.type)(
      contractId: String
  )(tuser: WalletUserRequest): Future[r0.WithdrawTransferOfferResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
          userWallet
        ),
        logger,
      )
    }
  }

  override def acceptAppPaymentRequest(
      respond: r0.AcceptAppPaymentRequestResponse.type
  )(contractId: String)(tuser: WalletUserRequest): Future[r0.AcceptAppPaymentRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.acceptAppPaymentRequest") { _ => _ =>
      val requestCid = Codec.tryDecodeJavaContractId(walletCodegen.AppPaymentRequest.COMPANION)(
        contractId
      )
      retryProvider.retryForClientCalls(
        "accept_app_payment",
        "Accept app payment request",
        exerciseWalletAmuletAction(
          new amuletoperation.CO_AppPayment(requestCid),
          userWallet,
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
  )(contractId: String)(tuser: WalletUserRequest): Future[r0.RejectAppPaymentRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
        })(userWallet),
        logger,
      )
    }
  }

  override def getSubscriptionRequest(respond: r0.GetSubscriptionRequestResponse.type)(
      contractId: String
  )(tuser: WalletUserRequest): Future[r0.GetSubscriptionRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.getSubscriptionRequest") { implicit traceContext => _ =>
      val requestCid =
        Codec.tryDecodeJavaContractId(subsCodegen.SubscriptionRequest.COMPANION)(contractId)
      for {
        subscriptionRequest <- userWallet.store.getSubscriptionRequest(requestCid)
      } yield r0.GetSubscriptionRequestResponseOK(
        subscriptionRequest.toHttp
      )
    }
  }

  override def acceptSubscriptionRequest(
      respond: r0.AcceptSubscriptionRequestResponse.type
  )(contractId: String)(tuser: WalletUserRequest): Future[r0.AcceptSubscriptionRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
          userWallet,
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
  )(contractId: String)(tuser: WalletUserRequest): Future[r0.CancelSubscriptionRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
        )(userWallet),
        logger,
      )
    }
  }

  override def rejectSubscriptionRequest(
      respond: r0.RejectSubscriptionRequestResponse.type
  )(contractId: String)(tuser: WalletUserRequest): Future[r0.RejectSubscriptionRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
        )(userWallet),
        logger,
      )
    }
  }

  override def getBalance(respond: r0.GetBalanceResponse.type)()(
      tuser: WalletUserRequest
  ): Future[r0.GetBalanceResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.getBalance") { _ => _ =>
      for {
        noHoldingFeesOnTransfers <- packageVersionSupport.noHoldingFeesOnTransfers(
          userWallet.store.key.dsoParty,
          walletManager.clock.now,
        )
        deductHoldingFees = !noHoldingFeesOnTransfers.supported
        currentRound <- scanConnection
          .getLatestOpenMiningRound()
          .map(_.payload.round.number)
        (unlockedQty, unlockedHoldingFees) <- userWallet.store.getAmuletBalanceWithHoldingFees(
          currentRound,
          deductHoldingFees = deductHoldingFees,
        )
        lockedQty <- userWallet.store.getLockedAmuletBalance(
          currentRound,
          deductHoldingFees = deductHoldingFees,
        )
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
      tuser: WalletUserRequest
  ): Future[r0.TapResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.tap") { _ => _ =>
      val amount = Codec.tryDecode(Codec.JavaBigDecimal)(request.amount)
      val commandId = CommandId(
        "org.lfdecentralizedtrust.splice.wallet.tap",
        Seq(userWallet.store.key.endUserParty),
        request.commandId.getOrElse(UUID.randomUUID().toString),
      )
      for {
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
              userWallet = userWallet,
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

  override def cancelFeaturedAppRights(
      respond: r0.CancelFeaturedAppRightsResponse.type
  )()(tuser: WalletUserRequest): Future[r0.CancelFeaturedAppRightsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.cancelFeaturedAppRights") { implicit traceContext => _ =>
      for {
        featuredAppRight <- userWallet.store.lookupFeaturedAppRight()
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
              })(userWallet),
              logger,
            )
        }
      } yield result
    }
  }

  override def createTransferPreapproval(
      respond: r0.CreateTransferPreapprovalResponse.type
  )()(tuser: WalletUserRequest): Future[r0.CreateTransferPreapprovalResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.createTransferPreapproval") { implicit traceContext => _ =>
      val store = userWallet.store
      for {
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
                  createTransferPreapprovalProposal(userWallet, domain, dedupOffset)
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
    for {
      supportsExpectedDsoParty <- packageVersionSupport
        .supportsExpectedDsoParty(
          Seq(store.key.validatorParty, store.key.endUserParty, store.key.dsoParty),
          walletManager.clock.now,
        )
        .map(_.supported)
      _ <- wallet.connection
        .submit(
          Seq(store.key.validatorParty, store.key.endUserParty),
          Seq.empty,
          new TransferPreapprovalProposal(
            store.key.endUserParty.toProtoPrimitive,
            store.key.validatorParty.toProtoPrimitive,
            Option.when(supportsExpectedDsoParty)(store.key.dsoParty.toProtoPrimitive).toJava,
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
    } yield ()
  }

  def transferPreapprovalSend(respond: r0.TransferPreapprovalSendResponse.type)(
      body: d0.TransferPreapprovalSendRequest
  )(tuser: WalletUserRequest): Future[r0.TransferPreapprovalSendResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
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
          val sender = userWallet.store.key.endUserParty
          for {
            featuredAppRight <- scanConnection
              .lookupFeaturedAppRight(PartyId.tryFromProtoPrimitive(preapproval.payload.provider))
            supportsDescription <- packageVersionSupport
              .supportsDescriptionInTransferPreapprovals(
                Seq(receiver, sender, userWallet.store.key.dsoParty),
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
              userWallet,
              (_: amuletoperationoutcome.COO_TransferPreapprovalSend) =>
                r0.TransferPreapprovalSendResponse.OK,
              extraDisclosedContracts = userWallet.connection.disclosedContracts(
                preapproval,
                // Approximating the state of featured app right to be the same as the preapproval
                // as scan currently does not return a ContractWithState.
                featuredAppRight.map(c => ContractWithState(c, preapproval.state)).toList*
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
  )(extracted: WalletUserRequest): Future[WalletResource.CreateTokenStandardTransferResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = extracted
    withSpan(s"$workflowId.createTokenStandardTransfer") { _ => _ =>
      val commandId = CommandId(
        "org.lfdecentralizedtrust.splice.wallet.createTokenStandardTransfer",
        Seq(userWallet.store.key.endUserParty),
        request.trackingId,
      )
      val dedupConfig = AmuletOperationDedupConfig(
        commandId,
        dedupDuration,
      )
      (for {
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
  )()(tuser: WalletUserRequest): Future[WalletResource.ListTokenStandardTransfersResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    listContracts(
      AmuletTransferInstruction.COMPANION,
      userWallet.store,
      contracts =>
        WalletResource.ListTokenStandardTransfersResponse.OK(
          d0.ListTokenStandardTransfersResponse(contracts)
        ),
    )
  }

  override def acceptTokenStandardTransfer(
      respond: WalletResource.AcceptTokenStandardTransferResponse.type
  )(contractId: String)(
      tUser: WalletUserRequest
  ): Future[WalletResource.AcceptTokenStandardTransferResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tUser
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
          userWallet,
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
      tUser: WalletUserRequest
  ): Future[WalletResource.RejectTokenStandardTransferResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tUser
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
          userWallet,
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
      tUser: WalletUserRequest
  ): Future[WalletResource.WithdrawTokenStandardTransferResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tUser
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
          userWallet,
          disclosedContracts = _ => DisclosedContracts.fromProto(choiceContext.disclosedContracts),
        )
      } yield WalletResource.WithdrawTokenStandardTransferResponseOK(
        transferInstructionResultToResponse(outcome.exerciseResult)
      )
    }
  }

  override def allocateAmulet(respond: WalletResource.AllocateAmuletResponse.type)(
      body: AllocateAmuletRequest
  )(extracted: WalletUserRequest): Future[WalletResource.AllocateAmuletResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = extracted
    withSpan(s"$workflowId.allocateAmulet") { _ => _ =>
      val now = walletManager.clock.now.toInstant
      val sender = userWallet.store.key.endUserParty
      val commandId = CommandId(
        "org.lfdecentralizedtrust.splice.wallet.allocateAmulet",
        Seq(sender),
        Seq(
          body.settlement.executor,
          body.settlement.settlementRef.id,
          body.settlement.settlementRef.cid.getOrElse(""),
          body.transferLegId,
        ),
      )
      val specification = new allocationv1.AllocationSpecification(
        new allocationv1.SettlementInfo(
          body.settlement.executor,
          new allocationv1.Reference(
            body.settlement.settlementRef.id,
            body.settlement.settlementRef.cid.map(cid => new AnyContract.ContractId(cid)).toJava,
          ),
          Codec.tryDecode(Codec.Timestamp)(body.settlement.requestedAt).toInstant,
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
      val dedupConfig = AmuletOperationDedupConfig(
        commandId,
        dedupDuration,
      )
      for {
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

  override def listAmuletAllocations(
      respond: WalletResource.ListAmuletAllocationsResponse.type
  )()(tUser: WalletUserRequest): Future[WalletResource.ListAmuletAllocationsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tUser
    listContracts(
      amuletAllocationCodegen.AmuletAllocation.COMPANION,
      userWallet.store,
      contracts => d0.ListAllocationsResponse(contracts.map(d0.Allocation(_))),
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

  override def withdrawAmuletAllocation(
      respond: WalletResource.WithdrawAmuletAllocationResponse.type
  )(
      contractId: String
  )(tUser: WalletUserRequest): Future[WalletResource.WithdrawAmuletAllocationResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tUser
    withSpan(s"$workflowId.withdrawAmuletAllocation") { implicit traceContext => _ =>
      val allocationCid = Codec.tryDecodeJavaContractId(
        amuletAllocationCodegen.AmuletAllocation.COMPANION
      )(
        contractId
      )
      val store = userWallet.store
      for {
        allocation <- store.multiDomainAcsStore
          .getContractById(
            amuletAllocationCodegen.AmuletAllocation.COMPANION
          )(allocationCid)
          .map(
            _.toAssignedContract.getOrElse(
              throw Status.Code.FAILED_PRECONDITION.toStatus
                .withDescription(s"AmuletAllocation is not assigned to a synchronizer.")
                .asRuntimeException()
            )
          )
        context <- scanConnection.getAllocationWithdrawContext(
          allocation.contractId.toInterface(allocationv1.Allocation.INTERFACE)
        )
        result <- userWallet.connection
          .submit(
            Seq(store.key.validatorParty, store.key.endUserParty),
            Seq.empty,
            allocation.exercise(
              _.toInterface(allocationv1.Allocation.INTERFACE)
                .exerciseAllocation_Withdraw(context.toExtraArgs())
            ),
          )
          .noDedup
          .withSynchronizerId(allocation.domain)
          .withDisclosedContracts(DisclosedContracts.fromProto(context.disclosedContracts))
          .yieldResult()
      } yield WalletResource.WithdrawAmuletAllocationResponseOK(
        d0.AmuletAllocationWithdrawResult(
          result.exerciseResult.senderHoldingCids.asScala.map(_.contractId).toVector,
          result.exerciseResult.meta.values.asScala.toMap,
        )
      )
    }
  }

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
      userWallet: UserWalletService,
      processResponse: ExpectedCOO => R,
      dedupConfig: Option[AmuletOperationDedupConfig] = None,
      extraDisclosedContracts: DisclosedContracts = DisclosedContracts.Empty,
  )(implicit tc: TraceContext): Future[R] =
    for {
      res <- userWallet.treasury
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

  override def listAllocationRequests(
      respond: WalletResource.ListAllocationRequestsResponse.type
  )()(tuser: WalletUserRequest): Future[WalletResource.ListAllocationRequestsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listInterfaces") { _ => _ =>
      for {
        contracts <- userWallet.store.multiDomainAcsStore.listInterfaceViews(
          allocationrequestv1.AllocationRequest.INTERFACE
        )
      } yield d0.ListAllocationRequestsResponse(
        contracts
          .map(_.toHttp)
          .toVector
          .map(d0.AllocationRequest(_))
      )
    }
  }

  override def rejectAllocationRequest(
      respond: WalletResource.RejectAllocationRequestResponse.type
  )(
      contractId: String
  )(tUser: WalletUserRequest): Future[WalletResource.RejectAllocationRequestResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tUser
    withSpan(s"$workflowId.rejectAllocationRequest") { implicit traceContext => _ =>
      val allocationRequestCid = Codec.tryDecodeJavaContractIdInterface(
        allocationrequestv1.AllocationRequest.INTERFACE
      )(
        contractId
      )
      val store = userWallet.store
      for {
        allocationRequest <- store.multiDomainAcsStore
          .findInterfaceViewByContractId(
            allocationrequestv1.AllocationRequest.INTERFACE
          )(allocationRequestCid)
          .map(
            _.getOrElse(
              throw Status.NOT_FOUND
                .withDescription(s"No AllocationRequest with contract id $contractId found.")
                .asRuntimeException()
            ).toAssignedContract.getOrElse(
              throw Status.Code.FAILED_PRECONDITION.toStatus
                .withDescription(s"AmuletAllocation is not assigned to a synchronizer.")
                .asRuntimeException()
            )
          )
        result <- userWallet.connection
          .submit(
            Seq(store.key.validatorParty, store.key.endUserParty),
            Seq.empty,
            allocationRequest.exercise(
              _.exerciseAllocationRequest_Reject(
                store.key.endUserParty.toProtoPrimitive,
                ChoiceContextWithDisclosures.emptyExtraArgs,
              )
            ),
          )
          .noDedup
          .withSynchronizerId(allocationRequest.domain)
          .yieldResult()
      } yield WalletResource.RejectAllocationRequestResponseOK(
        d0.ChoiceExecutionMetadata(result.exerciseResult.meta.values.asScala.toMap)
      )
    }
  }

  override def listMintingDelegationProposals(
      respond: WalletResource.ListMintingDelegationProposalsResponse.type
  )(after: Option[Long], limit: Option[Int])(
      tuser: WalletUserRequest
  ): Future[WalletResource.ListMintingDelegationProposalsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listMintingDelegationProposals") { _ => _ =>
      val pageLimit = PageLimit.tryCreate(limit.getOrElse(Limit.DefaultMaxPageSize))
      for {
        page <- userWallet.store.listMintingDelegationProposals(after, pageLimit)
      } yield WalletResource.ListMintingDelegationProposalsResponseOK(
        d0.ListMintingDelegationProposalsResponse(
          page.resultsInPage.map(_.toHttp).toVector,
          page.nextPageToken,
        )
      )
    }
  }

  // Accepts a MintingDelegationProposal, atomically archiving (one) existing delegation from the
  // same beneficiary if present. Any additional delegations from the same beneficiary are rejected
  // as well, as there should be only one active delegation per beneficiary.
  override def acceptMintingDelegationProposal(
      respond: WalletResource.AcceptMintingDelegationProposalResponse.type
  )(contractId: String)(
      tuser: WalletUserRequest
  ): Future[WalletResource.AcceptMintingDelegationProposalResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.acceptMintingDelegationProposal") { implicit traceContext => _ =>
      val proposalCid = Codec.tryDecodeJavaContractId(
        mintingDelegationCodegen.MintingDelegationProposal.COMPANION
      )(contractId)
      val store = userWallet.store
      for {
        // Accept the proposal while archiving existing delegation, and get any remaining delegations to clean up
        (newDelegationCid, remainingDelegations) <- retryProvider.retryForClientCalls(
          "accept_minting_delegation_proposal",
          "Accept minting delegation proposal",
          for {
            proposal <- store.multiDomainAcsStore
              .getContractById(mintingDelegationCodegen.MintingDelegationProposal.COMPANION)(
                proposalCid
              )
              .map(
                _.toAssignedContract.getOrElse(
                  throw Status.Code.FAILED_PRECONDITION.toStatus
                    .withDescription(
                      s"MintingDelegationProposal is not assigned to a synchronizer."
                    )
                    .asRuntimeException()
                )
              )
            beneficiary = proposal.payload.delegation.beneficiary
            existingDelegations <- store.listMintingDelegations(None, PageLimit.tryCreate(100))
            sameBeneficiaryDelegations = existingDelegations.resultsInPage.toList.filter(
              _.payload.beneficiary == beneficiary
            )
            existingDelegationCidOpt = sameBeneficiaryDelegations.headOption
              .map(_.contractId)
              .toJava
            result <- userWallet.connection
              .submit(
                Seq(store.key.endUserParty),
                Seq.empty,
                proposal.exercise(
                  _.exerciseMintingDelegationProposal_Accept(existingDelegationCidOpt)
                ),
              )
              .noDedup
              .withSynchronizerId(proposal.domain)
              .yieldResult()
          } yield (result.exerciseResult.mintingDelegationCid, sameBeneficiaryDelegations.drop(1)),
          logger,
        )
        // Reject any remaining delegations outside the retry block
        // In normal circumstances we should never have more than one active
        // delegation, so the following cleanup is mainly for some edge cases.
        _ <- remainingDelegations.foldLeft(Future.successful(())) { (acc, delegation) =>
          acc.flatMap { _ =>
            (for {
              assignedDelegation <- store.multiDomainAcsStore
                .getContractById(mintingDelegationCodegen.MintingDelegation.COMPANION)(
                  delegation.contractId
                )
                .map(_.toAssignedContract)
              _ <- assignedDelegation match {
                case Some(assigned) =>
                  userWallet.connection
                    .submit(
                      Seq(store.key.endUserParty),
                      Seq.empty,
                      assigned.exercise(_.exerciseMintingDelegation_Reject()),
                    )
                    .noDedup
                    .withSynchronizerId(assigned.domain)
                    .yieldUnit()
                case None =>
                  Future.unit
              }
            } yield ()).recover { case ex =>
              logger.warn(
                s"Failed to reject stale MintingDelegation ${delegation.contractId}: ${ex.getMessage}"
              )
            }
          }
        }
      } yield WalletResource.AcceptMintingDelegationProposalResponseOK(
        d0.AcceptMintingDelegationProposalResponse(
          Codec.encodeContractId(newDelegationCid)
        )
      )
    }
  }

  override def rejectMintingDelegationProposal(
      respond: WalletResource.RejectMintingDelegationProposalResponse.type
  )(contractId: String)(
      tuser: WalletUserRequest
  ): Future[WalletResource.RejectMintingDelegationProposalResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.rejectMintingDelegationProposal") { implicit traceContext => _ =>
      val proposalCid = Codec.tryDecodeJavaContractId(
        mintingDelegationCodegen.MintingDelegationProposal.COMPANION
      )(contractId)
      val store = userWallet.store
      retryProvider.retryForClientCalls(
        "reject_minting_delegation_proposal",
        "Reject minting delegation proposal",
        for {
          proposal <- store.multiDomainAcsStore
            .getContractById(mintingDelegationCodegen.MintingDelegationProposal.COMPANION)(
              proposalCid
            )
            .map(
              _.toAssignedContract.getOrElse(
                throw Status.Code.FAILED_PRECONDITION.toStatus
                  .withDescription(s"MintingDelegationProposal is not assigned to a synchronizer.")
                  .asRuntimeException()
              )
            )
          _ <- userWallet.connection
            .submit(
              Seq(store.key.endUserParty),
              Seq.empty,
              proposal.exercise(_.exerciseMintingDelegationProposal_Reject()),
            )
            .noDedup
            .withSynchronizerId(proposal.domain)
            .yieldUnit()
        } yield WalletResource.RejectMintingDelegationProposalResponseOK,
        logger,
      )
    }
  }

  override def listMintingDelegations(
      respond: WalletResource.ListMintingDelegationsResponse.type
  )(after: Option[Long], limit: Option[Int])(
      tuser: WalletUserRequest
  ): Future[WalletResource.ListMintingDelegationsResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.listMintingDelegations") { _ => _ =>
      val pageLimit = PageLimit.tryCreate(limit.getOrElse(Limit.DefaultMaxPageSize))
      for {
        page <- userWallet.store.listMintingDelegations(after, pageLimit)
      } yield WalletResource.ListMintingDelegationsResponseOK(
        d0.ListMintingDelegationsResponse(
          page.resultsInPage.map(_.toHttp).toVector,
          page.nextPageToken,
        )
      )
    }
  }

  override def rejectMintingDelegation(
      respond: WalletResource.RejectMintingDelegationResponse.type
  )(contractId: String)(
      tuser: WalletUserRequest
  ): Future[WalletResource.RejectMintingDelegationResponse] = {
    implicit val WalletUserRequest(user, userWallet, traceContext) = tuser
    withSpan(s"$workflowId.rejectMintingDelegation") { implicit traceContext => _ =>
      val delegationCid = Codec.tryDecodeJavaContractId(
        mintingDelegationCodegen.MintingDelegation.COMPANION
      )(contractId)
      val store = userWallet.store
      retryProvider.retryForClientCalls(
        "reject_minting_delegation",
        "Reject minting delegation",
        for {
          delegation <- store.multiDomainAcsStore
            .getContractById(mintingDelegationCodegen.MintingDelegation.COMPANION)(delegationCid)
            .map(
              _.toAssignedContract.getOrElse(
                throw Status.Code.FAILED_PRECONDITION.toStatus
                  .withDescription(s"MintingDelegation is not assigned to a synchronizer.")
                  .asRuntimeException()
              )
            )
          _ <- userWallet.connection
            .submit(
              Seq(store.key.endUserParty),
              Seq.empty,
              delegation.exercise(_.exerciseMintingDelegation_Reject()),
            )
            .noDedup
            .withSynchronizerId(delegation.domain)
            .yieldUnit()
        } yield WalletResource.RejectMintingDelegationResponseOK,
        logger,
      )
    }
  }
}
