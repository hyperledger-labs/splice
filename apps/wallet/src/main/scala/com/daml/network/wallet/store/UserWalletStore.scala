package com.daml.network.wallet.store

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.transferinput.InputCoin
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.codegen.java.cn.{
  directory as directoryCodegen,
  splitwise as splitwiseCodegen,
}
import com.daml.network.store.{AcsStore, CoinAppStore}
import com.daml.network.util.{CoinUtil, JavaContract}
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A store for serving all queries for a specific wallet end-user. */
trait UserWalletStore extends CoinAppStore {

  /** The key identifying the parties considered by this store. */
  def key: UserWalletStore.Key

  def getInstall()(implicit ec: ExecutionContext): Future[
    JavaContract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ] =
    acs
      .findContract(installCodegen.WalletAppInstall.COMPANION)(_ => true)
      .map(
        _.getOrElse(
          throw Status.NOT_FOUND.withDescription("WalletAppInstall contract").asRuntimeException()
        )
      )

  def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit] =
    acs.signalWhenIngested(offset)

  def listExpiredTransferOffers(now: CantonTimestamp, limit: Int): Future[Seq[JavaContract[
    transferOffersCodegen.TransferOffer.ContractId,
    transferOffersCodegen.TransferOffer,
  ]]] =
    acs
      .listContracts(transferOffersCodegen.TransferOffer.COMPANION)
      .map(
        _.iterator
          .filter(co => now.toInstant.isAfter(co.payload.expiresAt))
          .take(limit)
          .toSeq
      )

  def listExpiredAcceptedTransferOffers(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[JavaContract[
    transferOffersCodegen.AcceptedTransferOffer.ContractId,
    transferOffersCodegen.AcceptedTransferOffer,
  ]]] =
    acs
      .listContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION)
      .map(
        _.iterator
          .filter(co => now.toInstant.isAfter(co.payload.expiresAt))
          .take(limit)
          .toSeq
      )

  def listExpiredAppPaymentRequests(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[
    Seq[JavaContract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]]
  ] =
    acs
      .listContracts(walletCodegen.AppPaymentRequest.COMPANION)
      .map(
        _.iterator
          .filter(co => now.toInstant.isAfter(co.payload.expiresAt))
          .take(limit)
          .toSeq
      )

  def listSubscriptionStatesReadyForPayment(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[
    JavaContract[subsCodegen.SubscriptionIdleState.ContractId, subsCodegen.SubscriptionIdleState]
  ]] = {
    def isReady(state: subsCodegen.SubscriptionIdleState) = now.toInstant.isAfter(
      state.nextPaymentDueAt.minus(CoinUtil.relTimeToDuration(state.payData.paymentDuration))
    )

    acs
      .listContracts(subsCodegen.SubscriptionIdleState.COMPANION)
      .map(cos => cos.iterator.filter(co => isReady(co.payload)).take(limit).toSeq)
  }

  /** List all non-expired coins owned by a user in descending order according to their current amount in the given submitting round. */
  def listSortedCoinsAndQuantity(
      maxNumInputs: Int,
      submittingRound: Long,
  ): Future[Seq[(BigDecimal, InputCoin)]] = {
    acs
      .listContracts(coinCodegen.Coin.COMPANION)
      .map(coins =>
        coins
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
      )
  }

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  def listSortedValidatorRewards(
      maxNumInputs: Option[Int],
      activeIssuingRoundsO: Option[Set[Long]],
  ): Future[Seq[
    JavaContract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]] = {
    def filterActiveRounds(round: Long) = activeIssuingRoundsO match {
      case Some(rounds) => rounds.contains(round)
      case None => true
    }
    // TODO(M3-83): use an index to access sorted rounds in the DB schema.
    acs
      .listContracts(coinCodegen.ValidatorRewardCoupon.COMPANION)
      .map(rewards => {
        rewards
          .filter(rw => filterActiveRounds(rw.payload.round.number))
          .sortBy(_.payload.round.number)
          .take(maxNumInputs.getOrElse(Int.MaxValue))
      })
  }

  /** Returns the validator reward coupon sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  def listSortedAppRewards(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  ): Future[Seq[
    (JavaContract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]] = {
    // TODO(M3-83): use an index to access sorted rounds in the DB schema.
    acs
      .listContracts(
        coinCodegen.AppRewardCoupon.COMPANION
      )
      .map(rewards =>
        rewards
          .flatMap(rw => {
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
          })
          .sorted(
            Ordering[(Long, BigDecimal)].on(
              (x: (
                  JavaContract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon],
                  BigDecimal,
              )) => (x._1.payload.round.number, -x._2)
            )
          )
          .take(maxNumInputs)
      )
  }

  def lookupFeaturedAppRight()(implicit ec: ExecutionContext): Future[
    Option[JavaContract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] = {
    acs.findContract(coinCodegen.FeaturedAppRight.COMPANION)(_ => true)
  }
}

object UserWalletStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
      futureSupervisor: FutureSupervisor,
  )(implicit
      ec: ExecutionContext
  ): UserWalletStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryUserWalletStore(key, loggerFactory, timeouts, futureSupervisor)
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
          // All validator rights that entitle the endUser to collect rewards as a validator operator
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
              splitwiseCodegen.TransferInProgress.COMPANION
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
