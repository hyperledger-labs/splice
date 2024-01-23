package com.daml.network.wallet.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  coinrules as coinrulesCodegen,
  round as roundCodegen,
}
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.codegen.java.cn.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ParticipantAdminConnection.HasParticipantId
import com.daml.network.store.MultiDomainAcsStore.*
import com.daml.network.store.{CNNodeAppStore, Limit, PageLimit, TxLogStore}
import com.daml.network.util.*
import com.daml.network.wallet.store.UserWalletStore.*
import com.daml.network.wallet.store.db.{DbUserWalletStore, WalletTables}
import com.daml.network.wallet.store.db.WalletTables.UserWalletAcsStoreRowData
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries for a specific wallet end-user. */
trait UserWalletStore extends CNNodeAppStore[TxLogEntry] with NamedLogging {

  /** The key identifying the parties considered by this store. */
  def key: UserWalletStore.Key

  final def lookupInstall()(implicit tc: TraceContext): Future[
    Option[
      ContractWithState[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
    ]
  ] =
    // Note: there is nothing that prevents a party from having multiple WalletAppInstall contracts
    // here we just take the first one, preferring an assigned one if available
    lookupArbitraryPreferAssigned(installCodegen.WalletAppInstall.COMPANION)

  final def getInstall()(implicit ec: ExecutionContext, tc: TraceContext): Future[
    AssignedContract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ] = for {
    ct <- lookupInstall()
  } yield assignedOrNotFound(installCodegen.WalletAppInstall.COMPANION)(ct)

  def signalWhenIngestedOrShutdown(offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] = multiDomainAcsStore.signalWhenIngestedOrShutdown(offset)

  def listExpiredTransferOffers: ListExpiredContracts[
    transferOffersCodegen.TransferOffer.ContractId,
    transferOffersCodegen.TransferOffer,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(transferOffersCodegen.TransferOffer.COMPANION)(
      _.expiresAt
    )

  def listExpiredAcceptedTransferOffers: ListExpiredContracts[
    transferOffersCodegen.AcceptedTransferOffer.ContractId,
    transferOffersCodegen.AcceptedTransferOffer,
  ] = multiDomainAcsStore.listExpiredFromPayloadExpiry(
    transferOffersCodegen.AcceptedTransferOffer.COMPANION
  )(
    _.expiresAt
  )

  def getLatestTransferOfferEventByTrackingId(
      trackingId: String
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[TxLogEntry.TransferOffer]]]

  def listExpiredBuyTrafficRequests: ListExpiredContracts[
    trafficRequestCodegen.BuyTrafficRequest.ContractId,
    trafficRequestCodegen.BuyTrafficRequest,
  ] = multiDomainAcsStore.listExpiredFromPayloadExpiry(
    trafficRequestCodegen.BuyTrafficRequest.COMPANION
  )(
    _.expiresAt
  )

  def getLatestBuyTrafficRequestEventByTrackingId(
      trackingId: String
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[TxLogEntry.BuyTrafficRequest]]]

  final def listAppPaymentRequests(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[
    Seq[Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]]
  ] = for {
    contracts <- multiDomainAcsStore.listContracts(
      walletCodegen.AppPaymentRequest.COMPANION,
      limit,
    )
  } yield contracts map (_.contract)

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
  ] = multiDomainAcsStore.listExpiredFromPayloadExpiry(walletCodegen.AppPaymentRequest.COMPANION)(
    _.expiresAt
  )

  def listSubscriptionStatesReadyForPayment: ListExpiredContracts[
    subsCodegen.SubscriptionIdleState.ContractId,
    subsCodegen.SubscriptionIdleState,
  ] = (now: CantonTimestamp, limit: PageLimit) =>
    implicit traceContext => {
      def isReadyForPayment(state: subsCodegen.SubscriptionIdleState): Boolean =
        now.toInstant.isAfter(
          state.nextPaymentDueAt.minus(CNNodeUtil.relTimeToDuration(state.payData.paymentDuration))
        )

      for {
        idleStates <- multiDomainAcsStore.listAssignedContracts(
          subsCodegen.SubscriptionIdleState.COMPANION
        )
      } yield idleStates
        .filter(s => isReadyForPayment(s.payload))
        .take(limit.limit)
    }

  final def listSubscriptions(limit: Limit = Limit.DefaultLimit)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[Subscription]] = {
    // This is racy: you can miss subscriptions if their state change concurrently, i.e., you don't see them
    // as either idle or payment. This is okay since it'll just refresh in the UI.
    for {
      subscriptions <- multiDomainAcsStore.listContracts(
        subsCodegen.Subscription.COMPANION,
        limit,
      )
      subscriptionIdleStates <- multiDomainAcsStore.listContracts(
        subsCodegen.SubscriptionIdleState.COMPANION,
        limit,
      )
      subscriptionPayments <- multiDomainAcsStore.listContracts(
        subsCodegen.SubscriptionPayment.COMPANION,
        limit,
      )
    } yield {
      val mainMap = subscriptions.map(sub => sub.contractId -> sub).toMap
      val idleStates: Seq[(subsCodegen.Subscription.ContractId, SubscriptionState)] =
        subscriptionIdleStates.map(state =>
          (state.payload.subscription, SubscriptionIdleState(state.contract))
        )
      val payments: Seq[(subsCodegen.Subscription.ContractId, SubscriptionState)] =
        subscriptionPayments.map(state =>
          (state.payload.subscription, SubscriptionPaymentState(state.contract))
        )
      val states: Seq[(subsCodegen.Subscription.ContractId, SubscriptionState)] =
        (idleStates ++ payments).distinctBy(_._1)
      for {
        (mainId, state) <- states
        main <- mainMap.get(mainId)
      } yield Subscription(main.contract, state)
    }
  }

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

  /** List all non-expired coins owned by a user in descending order according to their current amount in the given submitting round. */
  def listSortedCoinsAndQuantity(
      submittingRound: Long,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[(BigDecimal, coinrulesCodegen.transferinput.InputCoin)]] = for {
    coins <- multiDomainAcsStore.listContracts(coinCodegen.Coin.COMPANION)
  } yield coins
    .map(c =>
      (
        CNNodeUtil
          .currentAmount(c.payload, submittingRound),
        c,
      )
    )
    .filter { quantityAndCoin => quantityAndCoin._1.compareTo(BigDecimal.valueOf(0)) > 0 }
    .sortBy(quantityAndCoin =>
      // negating because largest values should come first.
      quantityAndCoin._1.negate()
    )
    .take(limit.limit)
    .map(quantityAndCoin =>
      (
        quantityAndCoin._1,
        new coinrulesCodegen.transferinput.InputCoin(
          quantityAndCoin._2.contractId
        ),
      )
    )

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]]

  /** Returns the validator reward coupon sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  def listSortedAppRewards(
      issuingRoundsMap: Map[cc.types.Round, roundCodegen.IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]]

  final def lookupFeaturedAppRight()(implicit ec: ExecutionContext, tc: TraceContext): Future[
    Option[Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] =
    // Note: there is nothing that prevents a party from having multiple FeaturedAppRight contracts
    // here we just take the first one.
    lookupArbitraryPreferAssigned(coinCodegen.FeaturedAppRight.COMPANION)
      .map(_ map (_.contract))

  /** Lists all the validator rights where the corresponding user is entered as the validator. */
  final def getValidatorRightsWhereUserIsValidator()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]] =
    multiDomainAcsStore
      .listContracts(coinCodegen.ValidatorRight.COMPANION)
      .map(_ map (_.contract))

  def listTransactions(
      beginAfterEventId: Option[String],
      limit: PageLimit,
  )(implicit lc: TraceContext): Future[Seq[TxLogEntry.TransactionHistoryTxLogEntry]]

  def listCnsEntries(limit: Limit = Limit.DefaultLimit)(implicit tc: TraceContext): Future[
    Seq[UserWalletStore.CnsEntryWithPayData]
  ] = {
    import UserWalletStore.{
      CnsEntryWithPayData,
      EntryWithSubscriptionContext,
      SubscriptionIdleState,
      SubscriptionPayData,
      SubscriptionPaymentState,
    }
    for {
      // NOTE: entries and entry contexts are sometimes out of sync so we just filter out entries in such cases
      entries <- multiDomainAcsStore.listContracts(
        cnsCodegen.CnsEntry.COMPANION,
        limit,
      )
      entryContexts <- multiDomainAcsStore.listContracts(
        cnsCodegen.CnsEntryContext.COMPANION,
        limit,
      )
      subscriptions <- listSubscriptions(limit)
    } yield {
      val entriesWithSubs = entries.foldLeft(Seq.empty[EntryWithSubscriptionContext]) {
        (acc, entry) =>
          val context = entryContexts.find(_.payload.name == entry.payload.name)
          context match {
            case Some(c) =>
              acc :+ EntryWithSubscriptionContext(entry.contract, c.payload.reference)
            case _ => acc
          }
      }

      val subscriptionsPayData = subscriptions.map { s =>
        s.state match {
          case SubscriptionIdleState(contract) =>
            SubscriptionPayData(
              contract.payload.subscription,
              s.subscription.payload.reference,
              contract.payload.payData,
            )
          case SubscriptionPaymentState(contract) =>
            SubscriptionPayData(
              contract.payload.subscription,
              s.subscription.payload.reference,
              contract.payload.payData,
            )
        }
      }

      val subRefToPayData =
        subscriptionsPayData.map(spd => spd.subscriptionReference -> spd.payData).toMap

      entriesWithSubs.flatMap { es =>
        subRefToPayData
          .get(es.subscriptionReference)
          .map(subPayData =>
            CnsEntryWithPayData(
              contractId = es.entry.contractId,
              expiresAt = es.entry.payload.expiresAt,
              entryName = es.entry.payload.name,
              amount = subPayData.paymentAmount.amount,
              currency = subPayData.paymentAmount.currency,
              paymentInterval = subPayData.paymentInterval,
              paymentDuration = subPayData.paymentDuration,
            )
          )
      }
    }
  }

  final def listLaggingCoinRulesFollowers(
      targetDomain: DomainId,
      participantIdSource: HasParticipantId,
  )(implicit
      tc: TraceContext
  ): Future[Seq[AssignedContract[?, ?]]] =
    multiDomainAcsStore.listAssignedContractsNotOnDomainN(
      targetDomain,
      participantIdSource,
      templatesMovedByMyAutomation,
    )

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

  private[this] def assignedOrNotFound[TCid, T](
      companion: Contract.Companion.Template[TCid, T]
  )(ct: Option[ContractWithState[TCid, T]]) =
    ct flatMap (_.toAssignedContract) getOrElse {
      throw Status.NOT_FOUND
        .withDescription(s"${companion.TEMPLATE_ID.getEntityName} contract not found")
        .asRuntimeException()
    }

  override lazy val txLogConfig = new TxLogStore.Config[TxLogEntry] {
    override val parser =
      new UserWalletTxLogParser(loggerFactory, key.endUserParty, key.endUserName)
    override def entryToRow = WalletTables.UserWalletTxLogStoreRowData.fromTxLogEntry
    override def encodeEntry = TxLogEntry.encode
    override def decodeEntry = TxLogEntry.decode
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

  final case class SubscriptionPayData(
      subscription: subsCodegen.Subscription.ContractId,
      subscriptionReference: subsCodegen.SubscriptionRequest.ContractId,
      payData: subsCodegen.SubscriptionPayData,
  )
  final case class CnsEntryWithPayData(
      contractId: cnsCodegen.CnsEntry.ContractId,
      expiresAt: Instant,
      entryName: String,
      amount: java.math.BigDecimal,
      currency: walletCodegen.Currency,
      paymentInterval: RelTime,
      paymentDuration: RelTime,
  )
  final case class EntryWithSubscriptionContext(
      entry: Contract[cnsCodegen.CnsEntry.ContractId, cnsCodegen.CnsEntry],
      subscriptionReference: subsCodegen.SubscriptionRequest.ContractId,
  )

  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): UserWalletStore = {
    storage match {
      case _: MemoryStorage =>
        new InMemoryUserWalletStore(
          key,
          loggerFactory,
          retryProvider,
        )
      case dbStorage: DbStorage =>
        new DbUserWalletStore(
          key,
          dbStorage,
          loggerFactory,
          retryProvider,
        )
    }
  }

  private[network] val templatesMovedByMyAutomation: Seq[ConstrainedTemplate] = {
    Seq[ConstrainedTemplate](
      coinCodegen.AppRewardCoupon.COMPANION,
      coinCodegen.Coin.COMPANION,
      coinCodegen.LockedCoin.COMPANION,
      coinCodegen.ValidatorRewardCoupon.COMPANION,
      subsCodegen.Subscription.COMPANION,
      subsCodegen.SubscriptionRequest.COMPANION,
      subsCodegen.SubscriptionInitialPayment.COMPANION,
      subsCodegen.SubscriptionIdleState.COMPANION,
      subsCodegen.SubscriptionPayment.COMPANION,
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
      transferOffersCodegen.TransferOffer.COMPANION,
      walletCodegen.AcceptedAppPayment.COMPANION,
      walletCodegen.AppPaymentRequest.COMPANION,
    )
  }

  case class Key(
      /** The party-id of the SVC issuing CC managed by this end-user wallet. */
      svcParty: PartyId,

      /** The party-id of the wallet's validator */
      validatorParty: PartyId,

      /** The participant user name of the end-user */
      endUserName: String,

      /** The party-id of the end-user, which is the primary party of its participant user */
      endUserParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("endUserName", _.endUserName.singleQuoted),
      param("endUserParty", _.endUserParty),
      param("svcParty", _.svcParty),
    )
  }

  /** Contract of a wallet store for a specific user party. */
  def contractFilter(key: Key): ContractFilter[UserWalletAcsStoreRowData] = {
    val endUser = key.endUserParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    SimpleContractFilter(
      key.endUserParty,
      Map(
        // Install
        mkFilter(installCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.svcParty == svc &&
            co.payload.endUserParty == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Coins
        mkFilter(coinCodegen.Coin.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.owner == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(coinCodegen.LockedCoin.COMPANION)(co =>
          co.payload.coin.svc == svc &&
            co.payload.coin.owner == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Rewards
        mkFilter(coinCodegen.AppRewardCoupon.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.provider == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(coinCodegen.ValidatorRewardCoupon.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.user == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(coinCodegen.ValidatorRight.COMPANION)(co =>
          // All validator rights where the current user is the validator.
          co.payload.svc == svc &&
            co.payload.validator == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Transfer offers
        mkFilter(transferOffersCodegen.TransferOffer.COMPANION)(co =>
          co.payload.svc == svc &&
            (co.payload.sender == endUser ||
              co.payload.receiver == endUser)
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
        mkFilter(transferOffersCodegen.AcceptedTransferOffer.COMPANION)(co =>
          co.payload.svc == svc &&
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
          co.payload.svc == svc &&
            co.payload.sender == endUser
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Subscriptions
        mkFilter(subsCodegen.Subscription.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(subsCodegen.SubscriptionRequest.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(subsCodegen.SubscriptionIdleState.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(subsCodegen.SubscriptionInitialPayment.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        mkFilter(subsCodegen.SubscriptionPayment.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        )(UserWalletAcsStoreRowData(_)),
        // Featured app right
        mkFilter(coinCodegen.FeaturedAppRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.provider == endUser
        )(UserWalletAcsStoreRowData(_)),
        // CNS entry
        mkFilter(cnsCodegen.CnsEntry.COMPANION)(co => co.payload.user == endUser)(
          UserWalletAcsStoreRowData(_)
        ),
        mkFilter(cnsCodegen.CnsEntryContext.COMPANION)(co => co.payload.user == endUser)(
          UserWalletAcsStoreRowData(_)
        ),
        // Buy traffic requests
        mkFilter(trafficRequestCodegen.BuyTrafficRequest.COMPANION)(co =>
          co.payload.svc == svc && co.payload.endUserParty == endUser
        )(contract =>
          UserWalletAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        ),
      ),
    )
  }
}
