package com.daml.network.wallet.store

import com.daml.network.automation.MultiDomainExpiredContractTrigger
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.transferinput.InputCoin
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.ValidatorRight
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cn.{
  directory as directoryCodegen,
  splitwell as splitwellCodegen,
}
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.store.{CNNodeAppStoreWithHistory, OffsetStore}
import com.daml.network.store.MultiDomainAcsStore.*
import com.daml.network.util.{CNNodeUtil, Contract}
import com.daml.network.wallet.store.UserWalletStore.{
  AppPaymentRequest,
  Subscription,
  SubscriptionIdleState,
  SubscriptionPaymentState,
  SubscriptionRequest,
  SubscriptionState,
}
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal

/** A store for serving all queries for a specific wallet end-user. */
trait UserWalletStore
    extends CNNodeAppStoreWithHistory[
      UserWalletTxLogParser.TxLogIndexRecord,
      UserWalletTxLogParser.TxLogEntry,
    ]
    with NamedLogging {

  /** The key identifying the parties considered by this store. */
  def key: UserWalletStore.Key

  def lookupInstall()(implicit ec: ExecutionContext, tc: TraceContext): Future[
    Option[Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]]
  ] = for {
    domainId <- defaultAcsDomainIdF
    ct <- multiDomainAcsStore
      .findContractOnDomain(installCodegen.WalletAppInstall.COMPANION)(domainId, (_: Any) => true)
  } yield ct

  def getInstall()(implicit ec: ExecutionContext, tc: TraceContext): Future[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ] = for {
    ct <- lookupInstall()
  } yield ct.getOrElse(
    throw Status.NOT_FOUND.withDescription("WalletAppInstall contract").asRuntimeException()
  )

  def signalWhenIngestedOrShutdown(offset: String)(implicit tc: TraceContext): Future[Unit] =
    defaultAcsDomainIdF.flatMap(multiDomainAcsStore.signalWhenIngestedOrShutdown(_, offset))

  import MultiDomainExpiredContractTrigger.ListExpiredContracts

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
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(
      transferOffersCodegen.AcceptedTransferOffer.COMPANION
    )(
      _.expiresAt
    )

  def listAppPaymentRequests(implicit tc: TraceContext): Future[Seq[AppPaymentRequest]] = {
    for {
      domainId <- defaultAcsDomainIdF
      contracts <- multiDomainAcsStore.listContractsOnDomain(
        walletCodegen.AppPaymentRequest.COMPANION,
        domainId,
      )
      // there's a 1-1 mapping AppPaymentRequest-DeliveryOffer, so all should be included
      // This is racy: you can miss delivery offers if their state change concurrently.
      // This is okay since it'll just refresh in the UI.
      deliveryOffer <- multiDomainAcsStore.listContractsOnDomain(
        walletCodegen.DeliveryOffer.INTERFACE,
        domainId,
      )
    } yield {
      val deliveryOfferMap = deliveryOffer.map(offer => offer.contractId -> offer).toMap
      // We drop payment requests for which we can't find a corresponding delivery offer, which can be
      // the case if its transfer is still in flight.
      contracts.flatMap { c =>
        deliveryOfferMap.get(c.payload.deliveryOffer).map(AppPaymentRequest(c, _))
      }
    }
  }

  def getAppPaymentRequest(
      cid: walletCodegen.AppPaymentRequest.ContractId
  )(implicit tc: TraceContext): Future[AppPaymentRequest] = {
    for {
      domainId <- defaultAcsDomainIdF
      appPaymentRequest <- multiDomainAcsStore.getContractByIdOnDomain(
        walletCodegen.AppPaymentRequest.COMPANION
      )(domainId, cid)
      deliveryOffer <- multiDomainAcsStore.getContractByIdOnDomain(
        walletCodegen.DeliveryOffer.INTERFACE
      )(
        domainId,
        appPaymentRequest.payload.deliveryOffer,
      )
    } yield AppPaymentRequest(appPaymentRequest, deliveryOffer)
  }

  def listExpiredAppPaymentRequests: ListExpiredContracts[
    walletCodegen.AppPaymentRequest.ContractId,
    walletCodegen.AppPaymentRequest,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(walletCodegen.AppPaymentRequest.COMPANION)(
      _.expiresAt
    )

  def listSubscriptionStatesReadyForPayment: ListExpiredContracts[
    subsCodegen.SubscriptionIdleState.ContractId,
    subsCodegen.SubscriptionIdleState,
  ] = (now: CantonTimestamp, limit: Int) =>
    _ => {
      def isReadyForPayment(state: subsCodegen.SubscriptionIdleState): Boolean =
        now.toInstant.isAfter(
          state.nextPaymentDueAt.minus(CNNodeUtil.relTimeToDuration(state.payData.paymentDuration))
        )

      multiDomainAcsStore.listReadyContracts(
        subsCodegen.SubscriptionIdleState.COMPANION,
        (c: Contract[
          subsCodegen.SubscriptionIdleState.ContractId,
          subsCodegen.SubscriptionIdleState,
        ]) => isReadyForPayment(c.payload),
        Some(limit.toLong),
      )
    }

  def listSubscriptions()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[Subscription]] = {
    // This is racy: you can miss subscriptions if their state change concurrently, i.e., you don't see them
    // as either idle or payment. This is okay since it'll just refresh in the UI.
    for {
      domainId <- defaultAcsDomainIdF
      subscriptions <- multiDomainAcsStore.listContractsOnDomain(
        subsCodegen.Subscription.COMPANION,
        domainId,
      )
      // there's a 1-1 mapping Subscription-SubscriptionContext, so all should be included
      subscriptionContexts <- multiDomainAcsStore.listContractsOnDomain(
        subsCodegen.SubscriptionContext.INTERFACE,
        domainId,
      )
      subscriptionIdleStates <- multiDomainAcsStore.listContractsOnDomain(
        subsCodegen.SubscriptionIdleState.COMPANION,
        domainId,
      )
      subscriptionPayments <- multiDomainAcsStore.listContractsOnDomain(
        subsCodegen.SubscriptionPayment.COMPANION,
        domainId,
      )
    } yield {
      val mainMap = subscriptions.map(sub => sub.contractId -> sub).toMap
      val contextsMap = subscriptionContexts.map(ctx => ctx.contractId -> ctx).toMap
      val idleStates: Seq[(subsCodegen.Subscription.ContractId, SubscriptionState)] =
        subscriptionIdleStates.map(state =>
          (state.payload.subscription, SubscriptionIdleState(state))
        )
      val payments: Seq[(subsCodegen.Subscription.ContractId, SubscriptionState)] =
        subscriptionPayments.map(state =>
          (state.payload.subscription, SubscriptionPaymentState(state))
        )
      val states: Seq[(subsCodegen.Subscription.ContractId, SubscriptionState)] =
        (idleStates ++ payments).distinctBy(_._1)
      for {
        (mainId, state) <- states
        main <- mainMap.get(mainId)
        context <- contextsMap.get(main.payload.context)
      } yield {
        Subscription(main, context, state)
      }
    }
  }

  def getSubscriptionRequest(
      cid: subsCodegen.SubscriptionRequest.ContractId
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[SubscriptionRequest] = {
    for {
      domainId <- defaultAcsDomainIdF
      contract <- multiDomainAcsStore.getContractByIdOnDomain(
        subsCodegen.SubscriptionRequest.COMPANION
      )(
        domainId,
        cid,
      )
      context <- multiDomainAcsStore.getContractByIdOnDomain(
        subsCodegen.SubscriptionContext.INTERFACE
      )(
        domainId,
        contract.payload.subscriptionData.context,
      )
    } yield SubscriptionRequest(contract, context)
  }

  def listSubscriptionRequests()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[SubscriptionRequest]] = {
    for {
      domainId <- defaultAcsDomainIdF
      contracts <- multiDomainAcsStore.listContractsOnDomain(
        subsCodegen.SubscriptionRequest.COMPANION,
        domainId,
      )
      // there's a 1-1 mapping Subscription-SubscriptionContext, so all should be included
      contexts <- multiDomainAcsStore.listContractsOnDomain(
        subsCodegen.SubscriptionContext.INTERFACE,
        domainId,
      )
    } yield {
      val contextsMap = contexts.map(ctx => ctx.contractId -> ctx).toMap
      contracts.flatMap { contract =>
        // If the context is missing, that means that the SubscriptionRequest was archived right after fetching it
        contextsMap.get(contract.payload.subscriptionData.context).map { context =>
          SubscriptionRequest(contract, context)
        }
      }
    }
  }

  /** List all non-expired coins owned by a user in descending order according to their current amount in the given submitting round. */
  def listSortedCoinsAndQuantity(
      maxNumInputs: Int,
      submittingRound: Long,
  )(implicit tc: TraceContext): Future[Seq[(BigDecimal, InputCoin)]] = for {
    domainId <- defaultAcsDomainIdF
    coins <- multiDomainAcsStore.listContractsOnDomain(coinCodegen.Coin.COMPANION, domainId)
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
    .take(maxNumInputs)
    .map(quantityAndCoin =>
      (
        quantityAndCoin._1,
        new v1.coin.transferinput.InputCoin(
          quantityAndCoin._2.contractId.toInterface(v1.coin.Coin.INTERFACE)
        ),
      )
    )

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  def listSortedValidatorRewards(
      maxNumInputs: Option[Int],
      activeIssuingRoundsO: Option[Set[Long]],
  )(implicit tc: TraceContext): Future[Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]] = {
    def filterActiveRounds(round: Long) = activeIssuingRoundsO match {
      case Some(rounds) => rounds.contains(round)
      case None => true
    }
    // TODO(M3-83): use an index to access sorted rounds in the DB schema.
    for {
      domainId <- defaultAcsDomainIdF
      rewards <- multiDomainAcsStore.listContractsOnDomain(
        coinCodegen.ValidatorRewardCoupon.COMPANION,
        domainId,
      )
    } yield rewards
      .filter(rw => filterActiveRounds(rw.payload.round.number))
      .sortBy(_.payload.round.number)
      .take(maxNumInputs.getOrElse(Int.MaxValue))
  }

  /** Returns the validator reward coupon sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  def listSortedAppRewards(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]] = for {
    domainId <- defaultAcsDomainIdF
    // TODO(M3-83): use an index to access sorted rounds in the DB schema.
    rewards <- multiDomainAcsStore.listContractsOnDomain(
      coinCodegen.AppRewardCoupon.COMPANION,
      domainId,
    )
  } yield rewards
    .flatMap { rw =>
      val issuingO = issuingRoundsMap.get(rw.payload.round)
      issuingO
        .map(i => {
          val quantity =
            if (rw.payload.featured)
              rw.payload.amount.multiply(i.issuancePerFeaturedAppRewardCoupon)
            else
              rw.payload.amount.multiply(i.issuancePerUnfeaturedAppRewardCoupon)
          (rw, BigDecimal(quantity))
        })
    }
    .sorted(
      Ordering[(Long, BigDecimal)].on(
        (x: (
            Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon],
            BigDecimal,
        )) => (x._1.payload.round.number, -x._2)
      )
    )
    .take(maxNumInputs)

  def lookupFeaturedAppRight()(implicit ec: ExecutionContext, tc: TraceContext): Future[
    Option[Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(coinCodegen.FeaturedAppRight.COMPANION)(_, _ => true)
    )

  /** Lists all the validator rights where the corresponding user is entered as the validator. */
  def getValidatorRightsWhereUserIsValidator()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorRight.ContractId, ValidatorRight]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(coinCodegen.ValidatorRight.COMPANION, _)
    )

  def listTransactions(
      beginAfterEventId: Option[String],
      limit: Int,
  )(implicit lc: TraceContext): Future[Seq[UserWalletTxLogParser.TxLogEntry]] =
    for {
      domain <- domains.signalWhenConnected(defaultAcsDomain)
      entries <- beginAfterEventId.fold(
        txLogReader.getTxLogByOffset(0, limit)
      )(
        txLogReader.getTxLogAfterEventId(domain, _, limit)
      )
    } yield entries

  override protected def txLogParser =
    new UserWalletTxLogParser(
      loggerFactory,
      key.endUserParty.toProtoPrimitive,
      key.endUserName,
    )
}

object UserWalletStore {
  type SubscriptionContextContract =
    Contract[
      subsCodegen.SubscriptionContext.ContractId,
      subsCodegen.SubscriptionContextView,
    ]

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
      context: SubscriptionContextContract,
      state: SubscriptionState,
  )
  final case class SubscriptionRequest(
      subscription: Contract[
        subsCodegen.SubscriptionRequest.ContractId,
        subsCodegen.SubscriptionRequest,
      ],
      context: SubscriptionContextContract,
  )

  final case class AppPaymentRequest(
      appPaymentRequest: Contract[
        walletCodegen.AppPaymentRequest.ContractId,
        walletCodegen.AppPaymentRequest,
      ],
      deliveryOffer: Contract[
        walletCodegen.DeliveryOffer.ContractId,
        walletCodegen.DeliveryOfferView,
      ],
  )

  type TxLogIndexRecord = UserWalletTxLogParser.TxLogIndexRecord
  type TxLogEntry = UserWalletTxLogParser.TxLogEntry

  def apply(
      key: Key,
      storage: Storage,
      globalDomain: DomainAlias,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
      connection: CNLedgerConnection,
      retryProvider: RetryProvider,
      offsetStore: OffsetStore,
  )(implicit
      ec: ExecutionContext
  ): UserWalletStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryUserWalletStore(
          key,
          globalDomain,
          loggerFactory,
          futureSupervisor,
          connection,
          retryProvider,
          offsetStore,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
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
  def contractFilter(key: Key): ContractFilter = {
    val endUser = key.endUserParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    SimpleContractFilter(
      key.endUserParty,
      Map(
        // Install
        mkFilter(installCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.svcParty == svc &&
            co.payload.endUserParty == endUser
        ),
        // Coins
        mkFilter(coinCodegen.Coin.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.owner == endUser
        ),
        mkFilter(coinCodegen.LockedCoin.COMPANION)(co =>
          co.payload.coin.svc == svc &&
            co.payload.coin.owner == endUser
        ),
        // Rewards
        mkFilter(coinCodegen.AppRewardCoupon.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.provider == endUser
        ),
        mkFilter(coinCodegen.ValidatorRewardCoupon.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.user == endUser
        ),
        mkFilter(coinCodegen.ValidatorRight.COMPANION)(co =>
          // All validator rights where the current user is the validator.
          co.payload.svc == svc &&
            co.payload.validator == endUser
        ),
        // Transfer offers
        mkFilter(transferOffersCodegen.TransferOffer.COMPANION)(co =>
          co.payload.svc == svc &&
            (co.payload.sender == endUser ||
              co.payload.receiver == endUser)
        ),
        mkFilter(transferOffersCodegen.AcceptedTransferOffer.COMPANION)(co =>
          co.payload.svc == svc &&
            (co.payload.sender == endUser ||
              co.payload.receiver == endUser)
        ),
        // We only ingest app payment contracts where the user is the sender,
        // as app payments the user is a receiver or a provider are handled by
        // the provider's app
        mkFilter(walletCodegen.AppPaymentRequest.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
        // Subscriptions
        mkFilter(subsCodegen.Subscription.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionRequest.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionIdleState.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionInitialPayment.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionPayment.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionPayment.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        // Featured app right
        mkFilter(coinCodegen.FeaturedAppRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.provider == endUser
        ),
      ),
      Map(
        mkFilter(subsCodegen.SubscriptionContext.INTERFACE)(
          co =>
            co.payload.svc == svc &&
              co.payload.sender == endUser,
          Seq(
            InterfaceImplementation(directoryCodegen.DirectoryEntryContext.COMPANION)(ctx =>
              new subsCodegen.SubscriptionContextView(
                ctx.svc,
                ctx.user,
                s"""Directory entry: "${ctx.name}"""",
              )
            ),
            InterfaceImplementation(testSubsCodegen.TestSubscriptionContext.COMPANION)(ctx =>
              new subsCodegen.SubscriptionContextView(
                ctx.svc,
                ctx.user,
                ctx.description,
              )
            ),
          ),
        ),
        mkFilter(walletCodegen.DeliveryOffer.INTERFACE)(
          co =>
            co.payload.svc == svc &&
              co.payload.sender == endUser,
          Seq(
            InterfaceImplementation(
              splitwellCodegen.TransferInProgress.COMPANION
            )(offer =>
              new walletCodegen.DeliveryOfferView(
                offer.group.svc,
                offer.sender,
                s"Transfer from '${offer.sender}' to [${offer.receiverAmounts.asScala
                    .map(r => s"'${r.receiver}'")
                    .mkString(", ")}]",
              )
            ),
            InterfaceImplementation(
              testWalletCodegen.TestDeliveryOffer.COMPANION
            )(offer =>
              new walletCodegen.DeliveryOfferView(
                offer.svc,
                offer.sender,
                offer.description,
              )
            ),
          ),
        ),
      ),
    )
  }
}
