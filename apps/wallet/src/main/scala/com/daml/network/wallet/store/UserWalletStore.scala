package com.daml.network.wallet.store

import com.daml.network.automation.ExpiredContractTrigger
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.transferinput.InputCoin
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.ValidatorRight
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.codegen.java.cn.{
  directory as directoryCodegen,
  splitwell as splitwellCodegen,
}
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.store.{AcsStore, CoinAppStoreWithHistory}
import com.daml.network.util.{CoinUtil, Contract}
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
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
    extends CoinAppStoreWithHistory[
      UserWalletTxLogParser.TxLogIndexRecord,
      UserWalletTxLogParser.TxLogEntry,
    ]
    with NamedLogging {

  /** The key identifying the parties considered by this store. */
  def key: UserWalletStore.Key

  def getInstall()(implicit ec: ExecutionContext): Future[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ] = for {
    acs <- defaultAcs
    ct <- acs
      .findContract(installCodegen.WalletAppInstall.COMPANION)(_ => true)
  } yield ct.getOrElse(
    throw Status.NOT_FOUND.withDescription("WalletAppInstall contract").asRuntimeException()
  )

  def signalWhenIngestedOrShutdown(offset: String)(implicit tc: TraceContext): Future[Unit] =
    defaultAcs.flatMap(_.signalWhenIngestedOrShutdown(offset))

  import ExpiredContractTrigger.ListExpiredContracts
  import AcsStore.listExpiredFromPayloadExpiry

  def listExpiredTransferOffers: ListExpiredContracts[
    transferOffersCodegen.TransferOffer.ContractId,
    transferOffersCodegen.TransferOffer,
  ] = listExpiredFromPayloadExpiry(defaultAcs, transferOffersCodegen.TransferOffer.COMPANION)(
    _.expiresAt
  )

  def listExpiredAcceptedTransferOffers: ListExpiredContracts[
    transferOffersCodegen.AcceptedTransferOffer.ContractId,
    transferOffersCodegen.AcceptedTransferOffer,
  ] =
    listExpiredFromPayloadExpiry(defaultAcs, transferOffersCodegen.AcceptedTransferOffer.COMPANION)(
      _.expiresAt
    )

  def listExpiredAppPaymentRequests: ListExpiredContracts[
    walletCodegen.AppPaymentRequest.ContractId,
    walletCodegen.AppPaymentRequest,
  ] =
    listExpiredFromPayloadExpiry(defaultAcs, walletCodegen.AppPaymentRequest.COMPANION)(_.expiresAt)

  def listSubscriptionStatesReadyForPayment(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[
    Contract[subsCodegen.SubscriptionIdleState.ContractId, subsCodegen.SubscriptionIdleState]
  ]] = {
    def isReady(state: subsCodegen.SubscriptionIdleState) = now.toInstant.isAfter(
      state.nextPaymentDueAt.minus(CoinUtil.relTimeToDuration(state.payData.paymentDuration))
    )

    for {
      acs <- defaultAcs
      cos <- acs.listContracts(subsCodegen.SubscriptionIdleState.COMPANION)
    } yield cos.iterator.filter(co => isReady(co.payload)).take(limit).toSeq
  }

  /** List all non-expired coins owned by a user in descending order according to their current amount in the given submitting round. */
  def listSortedCoinsAndQuantity(
      maxNumInputs: Int,
      submittingRound: Long,
  ): Future[Seq[(BigDecimal, InputCoin)]] = for {
    acs <- defaultAcs
    coins <- acs.listContracts(coinCodegen.Coin.COMPANION)
  } yield coins
    .map(c =>
      (
        CoinUtil
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
  ): Future[Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]] = {
    def filterActiveRounds(round: Long) = activeIssuingRoundsO match {
      case Some(rounds) => rounds.contains(round)
      case None => true
    }
    // TODO(M3-83): use an index to access sorted rounds in the DB schema.
    for {
      acs <- defaultAcs
      rewards <- acs.listContracts(coinCodegen.ValidatorRewardCoupon.COMPANION)
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
  ): Future[Seq[
    (Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]] = for {
    acs <- defaultAcs
    // TODO(M3-83): use an index to access sorted rounds in the DB schema.
    rewards <- acs.listContracts(coinCodegen.AppRewardCoupon.COMPANION)
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

  def lookupFeaturedAppRight()(implicit ec: ExecutionContext): Future[
    Option[Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] =
    defaultAcs.flatMap(_.findContract(coinCodegen.FeaturedAppRight.COMPANION)(_ => true))

  /** Lists all the validator rights where the corresponding user is entered as the validator. */
  def getValidatorRightsWhereUserIsValidator()
      : Future[Seq[Contract[ValidatorRight.ContractId, ValidatorRight]]] =
    defaultAcs.flatMap(_.listContracts(coinCodegen.ValidatorRight.COMPANION))

  def listTransactions(
      beginAfterEventId: Option[String],
      limit: Int,
  )(implicit lc: TraceContext): Future[Seq[UserWalletTxLogParser.TxLogEntry]] =
    defaultTxLogReader.flatMap { txLogReader =>
      beginAfterEventId.fold(
        txLogReader.getTxLogByOffset(0, limit)
      )(
        txLogReader.getTxLogAfterEventId(_, limit)
      )
    }

  override protected def txLogParser =
    new UserWalletTxLogParser(loggerFactory, key.endUserParty.toProtoPrimitive)
}

object UserWalletStore {
  type TxLogIndexRecord = UserWalletTxLogParser.TxLogIndexRecord
  type TxLogEntry = UserWalletTxLogParser.TxLogEntry

  def apply(
      key: Key,
      storage: Storage,
      globalDomain: DomainAlias,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
      futureSupervisor: FutureSupervisor,
      connection: CoinLedgerConnection,
      retryProvider: CoinRetries,
  )(implicit
      ec: ExecutionContext
  ): UserWalletStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryUserWalletStore(
          key,
          globalDomain,
          loggerFactory,
          timeouts,
          futureSupervisor,
          connection,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  case class Key(
      /** The party-id of the SVC issuing CC managed by this end-user wallet. */
      svcParty: PartyId,

      /** The party-id of the wallet's service party */
      walletServiceParty: PartyId,

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

  /** Contract of a wallet store for a specific wallet-service party. */
  def contractFilter(key: Key): AcsStore.ContractFilter = {
    import AcsStore.{InterfaceImplementation, mkFilter}
    val endUser = key.endUserParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
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
