// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  amulettransferinstruction as amuletTransferInstructionCodegen,
  round as roundCodegen,
  validatorlicense as validatorCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as ansCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  install as installCodegen,
  mintingdelegation as mintingDelegationCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
  transferpreapproval as preapprovalCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.*
import org.lfdecentralizedtrust.splice.store.{
  Limit,
  PageLimit,
  ResultsPage,
  SortOrder,
  TransferInputStore,
  TxLogAppStore,
}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore.*
import org.lfdecentralizedtrust.splice.wallet.store.db.DbUserWalletStore
import org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.{
  UserWalletAcsInterfaceViewRowData,
  UserWalletAcsStoreRowData,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.lfdecentralizedtrust.splice.config.IngestionConfig

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A store for serving all queries for a specific wallet end-user. */
trait UserWalletStore extends TxLogAppStore[TxLogEntry] with TransferInputStore with NamedLogging {

  /** The key identifying the parties considered by this store. */
  def key: UserWalletStore.Key

  def domainMigrationId: Long

  final def lookupInstall()(implicit tc: TraceContext): Future[
    Option[
      ContractWithState[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
    ]
  ] =
    // Note: a party can have WalletAppInstall contracts if there are multiple end-users that share the same party
    // here we just take the first one, preferring an assigned one if available
    // TODO(DACH-NY/canton-network-node#12550): remove this confusing behavior and create only one WalletAppInstall per user-party
    lookupArbitraryPreferAssigned(installCodegen.WalletAppInstall.COMPANION)

  final def getInstall()(implicit ec: ExecutionContext, tc: TraceContext): Future[
    AssignedContract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ] = for {
    ct <- lookupInstall()
  } yield assignedOrNotFound(installCodegen.WalletAppInstall.COMPANION)(ct)

  def signalWhenIngestedOrShutdown(offset: Long)(implicit
      tc: TraceContext
  ): Future[Unit] = multiDomainAcsStore.signalWhenIngestedOrShutdown(offset)

  def listExpiredTransferOffers: ListExpiredContracts[
    transferOffersCodegen.TransferOffer.ContractId,
    transferOffersCodegen.TransferOffer,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(transferOffersCodegen.TransferOffer.COMPANION)

  def listExpiredAcceptedTransferOffers: ListExpiredContracts[
    transferOffersCodegen.AcceptedTransferOffer.ContractId,
    transferOffersCodegen.AcceptedTransferOffer,
  ] = multiDomainAcsStore.listExpiredFromPayloadExpiry(
    transferOffersCodegen.AcceptedTransferOffer.COMPANION
  )

  def getLatestTransferOfferEventByTrackingId(
      trackingId: String
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[TransferOfferTxLogEntry]]]

  def listExpiredBuyTrafficRequests: ListExpiredContracts[
    trafficRequestCodegen.BuyTrafficRequest.ContractId,
    trafficRequestCodegen.BuyTrafficRequest,
  ] = multiDomainAcsStore.listExpiredFromPayloadExpiry(
    trafficRequestCodegen.BuyTrafficRequest.COMPANION
  )

  def getLatestBuyTrafficRequestEventByTrackingId(
      trackingId: String
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[BuyTrafficRequestTxLogEntry]]]

  final def listAppPaymentRequests(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[
    Seq[
      ContractWithState[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
    ]
  ] = for {
    contracts <- multiDomainAcsStore.listContracts(
      walletCodegen.AppPaymentRequest.COMPANION,
      limit,
    )
  } yield contracts

  def getAppPaymentRequest(
      cid: walletCodegen.AppPaymentRequest.ContractId
  )(implicit tc: TraceContext): Future[
    ContractWithState[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
  ] =
    for {
      appPaymentRequest <- multiDomainAcsStore.getContractById(
        walletCodegen.AppPaymentRequest.COMPANION
      )(cid)
    } yield appPaymentRequest

  def listExpiredAppPaymentRequests: ListExpiredContracts[
    walletCodegen.AppPaymentRequest.ContractId,
    walletCodegen.AppPaymentRequest,
  ] = multiDomainAcsStore.listExpiredFromPayloadExpiry(walletCodegen.AppPaymentRequest.COMPANION)

  def listSubscriptionStatesReadyForPayment: ListExpiredContracts[
    subsCodegen.SubscriptionIdleState.ContractId,
    subsCodegen.SubscriptionIdleState,
  ] = (now: CantonTimestamp, limit: PageLimit) =>
    implicit traceContext => {
      def isReadyForPayment(state: subsCodegen.SubscriptionIdleState): Boolean =
        now.toInstant.isAfter(
          state.nextPaymentDueAt.minus(SpliceUtil.relTimeToDuration(state.payData.paymentDuration))
        )

      for {
        idleStates <- multiDomainAcsStore.listAssignedContracts(
          subsCodegen.SubscriptionIdleState.COMPANION
        )
      } yield idleStates
        .filter(s => isReadyForPayment(s.payload))
        .take(limit.limit)
    }

  def listSubscriptions(now: CantonTimestamp, limit: Limit = Limit.DefaultLimit)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[Subscription]]

  final def getSubscriptionRequest(
      cid: subsCodegen.SubscriptionRequest.ContractId
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[
    Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]
  ] = for {
    contract <- multiDomainAcsStore.getContractById(
      subsCodegen.SubscriptionRequest.COMPANION
    )(cid)
  } yield contract.contract

  final def listSubscriptionRequests(limit: Limit = Limit.DefaultLimit)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    Seq[Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]]
  ] = for {
    requests <- multiDomainAcsStore.listContracts(subsCodegen.SubscriptionRequest.COMPANION, limit)
  } yield requests map (_.contract)

  final def listMintingDelegationProposals(
      after: Option[Long],
      limit: PageLimit,
  )(implicit tc: TraceContext): Future[
    ResultsPage[
      Contract[
        mintingDelegationCodegen.MintingDelegationProposal.ContractId,
        mintingDelegationCodegen.MintingDelegationProposal,
      ]
    ]
  ] = for {
    page <- multiDomainAcsStore.listContractsPaginated(
      mintingDelegationCodegen.MintingDelegationProposal.COMPANION,
      after,
      limit,
      SortOrder.Ascending,
    )
  } yield page.mapResultsInPage(_.contract)

  final def listMintingDelegations(
      after: Option[Long],
      limit: PageLimit,
  )(implicit tc: TraceContext): Future[
    ResultsPage[
      Contract[
        mintingDelegationCodegen.MintingDelegation.ContractId,
        mintingDelegationCodegen.MintingDelegation,
      ]
    ]
  ] = for {
    page <- multiDomainAcsStore.listContractsPaginated(
      mintingDelegationCodegen.MintingDelegation.COMPANION,
      after,
      limit,
      SortOrder.Ascending,
    )
  } yield page.mapResultsInPage(_.contract)

  def getAmuletBalanceWithHoldingFees(asOfRound: Long, deductHoldingFees: Boolean)(implicit
      tc: TraceContext
  ): Future[(BigDecimal, BigDecimal)] = for {
    amulets <- multiDomainAcsStore.listContracts(amuletCodegen.Amulet.COMPANION)
  } yield {
    val holdingFees =
      amulets.view
        .map(c => BigDecimal(SpliceUtil.holdingFee(c.payload, asOfRound)))
        .sum
    val totalAmount =
      amulets.view
        .map(c =>
          BigDecimal(
            SpliceUtil.currentAmount(c.payload, asOfRound, deductHoldingFees = deductHoldingFees)
          )
        )
        .sum
    (totalAmount, holdingFees)
  }

  def getLockedAmuletBalance(asOfRound: Long, deductHoldingFees: Boolean)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = for {
    lockedAmulets <- multiDomainAcsStore.listContracts(
      amuletCodegen.LockedAmulet.COMPANION
    )
  } yield {
    val totalAmount = lockedAmulets.view
      .map(c =>
        BigDecimal(
          SpliceUtil
            .currentAmount(c.payload.amulet, asOfRound, deductHoldingFees = deductHoldingFees)
        )
      )
      .sum
    totalAmount
  }

  /** Returns the validator faucet coupons sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  def listSortedValidatorFaucets(
      issuingRoundsMap: Map[splice.types.Round, roundCodegen.IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (
        Contract[
          validatorCodegen.ValidatorFaucetCoupon.ContractId,
          validatorCodegen.ValidatorFaucetCoupon,
        ],
        BigDecimal,
    )
  ]]

  /** Returns the validator activity records sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  def listSortedLivenessActivityRecords(
      issuingRoundsMap: Map[splice.types.Round, roundCodegen.IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (
        Contract[
          validatorCodegen.ValidatorLivenessActivityRecord.ContractId,
          validatorCodegen.ValidatorLivenessActivityRecord,
        ],
        BigDecimal,
    )
  ]]

  /** Returns the SV reward coupons sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  def listSortedSvRewardCoupons(
      issuingRoundsMap: Map[splice.types.Round, roundCodegen.IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (
        Contract[
          amuletCodegen.SvRewardCoupon.ContractId,
          amuletCodegen.SvRewardCoupon,
        ],
        BigDecimal,
    )
  ]]

  /** Returns the list of unclaimed activity record. */
  def listUnclaimedActivityRecords(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[Seq[
    Contract[
      amuletCodegen.UnclaimedActivityRecord.ContractId,
      amuletCodegen.UnclaimedActivityRecord,
    ]
  ]] =
    for {
      rewards <- multiDomainAcsStore.listContracts(
        amuletCodegen.UnclaimedActivityRecord.COMPANION
      )
    } yield applyLimit(
      "listUnclaimedActivityRecords",
      limit,
      rewards.view
        .map(_.contract)
        .toSeq,
    )

  final def lookupFeaturedAppRight()(implicit ec: ExecutionContext, tc: TraceContext): Future[
    Option[Contract[amuletCodegen.FeaturedAppRight.ContractId, amuletCodegen.FeaturedAppRight]]
  ] =
    // Note: there is nothing that prevents a party from having multiple FeaturedAppRight contracts
    // here we just take the first one.
    lookupArbitraryPreferAssigned(amuletCodegen.FeaturedAppRight.COMPANION)
      .map(_ map (_.contract))

  def lookupTransferPreapprovalProposal(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[QueryResult[Option[Contract[
    preapprovalCodegen.TransferPreapprovalProposal.ContractId,
    preapprovalCodegen.TransferPreapprovalProposal,
  ]]]]

  def getTransferPreapproval(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Contract[
    amuletrulesCodegen.TransferPreapproval.ContractId,
    amuletrulesCodegen.TransferPreapproval,
  ]] = lookupTransferPreapproval(receiver)
    .map(
      _.map(
        _.getOrElse(
          throw Status.NOT_FOUND
            .withDescription("No TransferPreapproval found")
            .asRuntimeException()
        )
      )
    )
    .map(_.value)

  def lookupTransferPreapproval(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[QueryResult[Option[Contract[
    amuletrulesCodegen.TransferPreapproval.ContractId,
    amuletrulesCodegen.TransferPreapproval,
  ]]]]

  /** Lists all the validator rights where the corresponding user is entered as the validator. */
  final def getValidatorRightsWhereUserIsValidator()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[amuletCodegen.ValidatorRight.ContractId, amuletCodegen.ValidatorRight]]] =
    multiDomainAcsStore
      .listContracts(amuletCodegen.ValidatorRight.COMPANION)
      .map(_ map (_.contract))

  def listTransactions(
      beginAfterEventId: Option[String],
      limit: PageLimit,
  )(implicit lc: TraceContext): Future[Seq[TxLogEntry.TransactionHistoryTxLogEntry]]

  def listAnsEntries(now: CantonTimestamp, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[UserWalletStore.AnsEntryWithPayData]]

  // For cases where `companion` can have multiple contracts, but we just need
  // an arbitrary one; prefer an Assigned contract if available but accept an
  // in-flight contract as fallback.
  private[this] def lookupArbitraryPreferAssigned[C, TCid <: ContractId[?], T](
      companion: C
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      tc: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] = {
    import cats.Eval
    import cats.data.OptionT
    import cats.syntax.semigroupk.*
    OptionT(
      multiDomainAcsStore
        .listAssignedContracts(companion, PageLimit.tryCreate(1))
        .map(_.headOption.map(_.toContractWithState))
    ).combineKEval(Eval.always {
      OptionT(
        multiDomainAcsStore
          .listContracts(companion, PageLimit.tryCreate(1))
          .map(_.headOption)
      )
    }).value
      .value
  }

  final def getOutstandingTransferOffers(
      fromParty: Option[PartyId],
      toParty: Option[PartyId],
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[
    (
        Seq[AssignedContract[
          transferOffersCodegen.TransferOffer.ContractId,
          transferOffersCodegen.TransferOffer,
        ]],
        Seq[AssignedContract[
          transferOffersCodegen.AcceptedTransferOffer.ContractId,
          transferOffersCodegen.AcceptedTransferOffer,
        ]],
    )
  ] = {
    for {
      transferOffers <- multiDomainAcsStore.listAssignedContracts(
        transferOffersCodegen.TransferOffer.COMPANION
      )
      acceptedTransferOffers <- multiDomainAcsStore.listAssignedContracts(
        transferOffersCodegen.AcceptedTransferOffer.COMPANION
      )
    } yield {
      val offersFilteredFrom = fromParty match {
        case None => transferOffers
        case Some(fromParty) =>
          transferOffers.filter(_.payload.sender == fromParty.toProtoPrimitive)
      }
      val offersFilteredTo = toParty match {
        case None => offersFilteredFrom
        case Some(toParty) =>
          offersFilteredFrom.filter(_.payload.receiver == toParty.toProtoPrimitive)
      }

      val acceptedOffersFilteredFrom = fromParty match {
        case None => acceptedTransferOffers
        case Some(fromParty) =>
          acceptedTransferOffers.filter(_.payload.sender == fromParty.toProtoPrimitive)
      }
      val acceptedOffersFilteredTo = toParty match {
        case None => acceptedOffersFilteredFrom
        case Some(toParty) =>
          acceptedOffersFilteredFrom.filter(_.payload.receiver == toParty.toProtoPrimitive)
      }
      (offersFilteredTo, acceptedOffersFilteredTo)
    }
  }

  private[this] def assignedOrNotFound[TCid, T](
      companion: Contract.Companion.Template[TCid, T]
  )(ct: Option[ContractWithState[TCid, T]]) =
    ct flatMap (_.toAssignedContract) getOrElse {
      throw Status.NOT_FOUND
        .withDescription(
          s"${companion.getTemplateIdWithPackageId.getEntityName} contract not found"
        )
        .asRuntimeException()
    }
}

object UserWalletStore {
  sealed trait SubscriptionState {
    val contract: Contract[?, ?]
  }
  final case class SubscriptionIdleState(
      contract: Contract[
        subsCodegen.SubscriptionIdleState.ContractId,
        subsCodegen.SubscriptionIdleState,
      ]
  ) extends SubscriptionState
  final case class SubscriptionPaymentState(
      contract: Contract[
        subsCodegen.SubscriptionPayment.ContractId,
        subsCodegen.SubscriptionPayment,
      ]
  ) extends SubscriptionState
  final case class Subscription(
      subscription: Contract[
        subsCodegen.Subscription.ContractId,
        subsCodegen.Subscription,
      ],
      state: SubscriptionState,
  )

  final case class AnsEntryWithPayData(
      contractId: ansCodegen.AnsEntry.ContractId,
      expiresAt: Instant,
      entryName: String,
      amount: java.math.BigDecimal,
      unit: walletCodegen.Unit,
      paymentInterval: RelTime,
      paymentDuration: RelTime,
  )
  final case class EntryWithSubscriptionContext(
      entry: Contract[ansCodegen.AnsEntry.ContractId, ansCodegen.AnsEntry],
      subscriptionReference: subsCodegen.SubscriptionRequest.ContractId,
  )

  def apply(
      key: Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): UserWalletStore = {
    new DbUserWalletStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
      ingestionConfig,
    )
  }

  case class Key(
      /** The party-id of the DSO issuing CC managed by this end-user wallet. */
      dsoParty: PartyId,

      /** The party-id of the wallet's validator */
      validatorParty: PartyId,

      /** The party-id of the end-user, which is the primary party of its participant user */
      endUserParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("endUserParty", _.endUserParty),
      param("validatorParty", _.validatorParty),
      param("dsoParty", _.dsoParty),
    )
  }

  /** Contract of a wallet store for a specific user party. */
  def contractFilter(
      key: Key,
      domainMigrationId: Long,
  ): ContractFilter[UserWalletAcsStoreRowData, UserWalletAcsInterfaceViewRowData] = {
    val endUser = key.endUserParty.toProtoPrimitive
    val validator = key.validatorParty.toProtoPrimitive
    val dso = key.dsoParty.toProtoPrimitive

    SimpleContractFilter(
      key.endUserParty,
      Map(
        // Install
        mkFilter(installCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.dsoParty == dso &&
            co.payload.endUserParty == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Amulets
        mkFilter(amuletCodegen.Amulet.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.owner == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(amuletCodegen.LockedAmulet.COMPANION)(co =>
          co.payload.amulet.dso == dso &&
            co.payload.amulet.owner == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Rewards
        mkFilter(amuletCodegen.AppRewardCoupon.COMPANION)(co =>
          co.payload.dso == dso &&
            (co.payload.provider == endUser && co.payload.beneficiary.isEmpty || co.payload.beneficiary == java.util.Optional
              .of(endUser))
        )(co =>
          UserWalletAcsStoreRowData(co, None, rewardCouponRound = Some(co.payload.round.number))
        ),
        mkFilter(amuletCodegen.ValidatorRewardCoupon.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.user == endUser
        )(co =>
          UserWalletAcsStoreRowData(co, None, rewardCouponRound = Some(co.payload.round.number))
        ),
        mkFilter(validatorCodegen.ValidatorFaucetCoupon.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.validator == endUser
        )(co =>
          UserWalletAcsStoreRowData(co, None, rewardCouponRound = Some(co.payload.round.number))
        ),
        mkFilter(validatorCodegen.ValidatorLivenessActivityRecord.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.validator == endUser
        )(co =>
          UserWalletAcsStoreRowData(co, None, rewardCouponRound = Some(co.payload.round.number))
        ),
        mkFilter(amuletCodegen.SvRewardCoupon.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.beneficiary == endUser
        )(co =>
          UserWalletAcsStoreRowData(
            co,
            None,
            rewardCouponRound = Some(co.payload.round.number),
            rewardCouponWeight = Some(co.payload.weight),
          )
        ),
        mkFilter(amuletCodegen.UnclaimedActivityRecord.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.beneficiary == endUser
        )(co =>
          UserWalletAcsStoreRowData(
            co,
            contractExpiresAt = Some(Timestamp.assertFromInstant(co.payload.expiresAt)),
          )
        ),
        mkFilter(amuletCodegen.ValidatorRight.COMPANION)(co =>
          // All validator rights where the current user is the validator.
          co.payload.dso == dso &&
            co.payload.validator == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Transfer offers
        mkFilter(transferOffersCodegen.TransferOffer.COMPANION)(co =>
          co.payload.dso == dso &&
            (co.payload.sender == endUser ||
              co.payload.receiver == endUser)
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
        mkFilter(transferOffersCodegen.AcceptedTransferOffer.COMPANION)(co =>
          co.payload.dso == dso &&
            (co.payload.sender == endUser ||
              co.payload.receiver == endUser)
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
        // We only ingest app payment contracts where the user is the sender,
        // as app payments the user is a receiver or a provider are handled by
        // the provider's app
        mkFilter(walletCodegen.AppPaymentRequest.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.sender == endUser
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Subscriptions
        mkFilter(subsCodegen.Subscription.COMPANION)(co =>
          co.payload.subscriptionData.dso == dso &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(subsCodegen.SubscriptionRequest.COMPANION)(co =>
          co.payload.subscriptionData.dso == dso &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(subsCodegen.SubscriptionIdleState.COMPANION)(co =>
          co.payload.subscriptionData.dso == dso &&
            co.payload.subscriptionData.sender == endUser
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.nextPaymentDueAt)),
          )
        ),
        mkFilter(subsCodegen.SubscriptionInitialPayment.COMPANION)(co =>
          co.payload.subscriptionData.dso == dso &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(subsCodegen.SubscriptionPayment.COMPANION)(co =>
          co.payload.subscriptionData.dso == dso &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Featured app right
        mkFilter(amuletCodegen.FeaturedAppRight.COMPANION)(co =>
          co.payload.dso == dso && co.payload.provider == endUser
        )(UserWalletAcsStoreRowData(_)),
        // ANS entry
        mkFilter(ansCodegen.AnsEntry.COMPANION)(co => co.payload.user == endUser)(
          UserWalletAcsStoreRowData(_)
        ),
        mkFilter(ansCodegen.AnsEntryContext.COMPANION)(co => co.payload.user == endUser)(
          UserWalletAcsStoreRowData(_)
        ),
        // Buy traffic requests
        mkFilter(trafficRequestCodegen.BuyTrafficRequest.COMPANION)(co =>
          co.payload.dso == dso && co.payload.endUserParty == endUser && co.payload.migrationId == domainMigrationId
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
        // Transfer preapprovals
        mkFilter(preapprovalCodegen.TransferPreapprovalProposal.COMPANION)(co =>
          // We ingest for both the receiver and the provider as the provider
          // needs the contract in its store for the payment/renewal automation to work.
          co.payload.provider == validator && (co.payload.provider == endUser || co.payload.receiver == endUser)
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            transferPreapprovalReceiver =
              Some(PartyId.tryFromProtoPrimitive(contract.payload.receiver)),
          )
        ),
        mkFilter(amuletrulesCodegen.TransferPreapproval.COMPANION)(co =>
          // We ingest for both the receiver and the provider as the provider
          // needs the contract in its store for the payment/renewal automation to work.
          co.payload.dso == dso && co.payload.provider == validator && (co.payload.provider == endUser || co.payload.receiver == endUser)
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            transferPreapprovalReceiver =
              Some(PartyId.tryFromProtoPrimitive(contract.payload.receiver)),
          )
        ),
        mkFilter(amuletTransferInstructionCodegen.AmuletTransferInstruction.COMPANION)(co =>
          co.payload.transfer.instrumentId.admin == dso && (co.payload.transfer.sender == endUser || co.payload.transfer.receiver == endUser)
        )(contract => UserWalletAcsStoreRowData(contract)),
        mkFilter(splice.amuletallocation.AmuletAllocation.COMPANION) { co =>
          val transferLeg = co.payload.allocation.transferLeg
          transferLeg.instrumentId.admin == dso && transferLeg.sender == endUser
        } { contract =>
          UserWalletAcsStoreRowData(contract)
        },
        // Minting delegations for user as the delegate
        mkFilter(mintingDelegationCodegen.MintingDelegationProposal.COMPANION)(co =>
          co.payload.delegation.dso == dso &&
            co.payload.delegation.delegate == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(mintingDelegationCodegen.MintingDelegation.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.delegate == endUser
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
      ),
      Map(
        mkFilterInterface(allocationrequestv1.AllocationRequest.INTERFACE)(co =>
          co.payload.transferLegs.asScala.exists { case (_, transferLeg) =>
            transferLeg.instrumentId.admin == dso && transferLeg.sender == endUser
          }
        )(contract => UserWalletAcsInterfaceViewRowData(contract))
      ),
    )
  }
}
