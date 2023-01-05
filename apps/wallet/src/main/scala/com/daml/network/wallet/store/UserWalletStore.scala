package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.store.AcsStore
import com.daml.network.util.{CoinUtil, JavaContract}
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries for a specific wallet end-user. */
trait UserWalletStore extends FlagCloseable with NamedLogging {
  import AcsStore.QueryResult

  /** The sink to use for ingesting data from the ledger into this store. */
  def acsIngestionSink: AcsStore.IngestionSink

  protected def acsStore: AcsStore

  /** A direct way to access the generic methods for querying the underlying ACS store. */
  def acs: AcsStore = acsStore

  /** The key identifying the parties considered by this store. */
  def key: UserWalletStore.Key

  /** Lookup the end-users install contract.
    *
    * Returns an Option, as there can be races where this fails, and the caller has better context on
    * how to deal with this error.
    */
  def lookupInstall(): Future[QueryResult[
    Option[
      JavaContract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
    ]
  ]] =
    acsStore.findContract(installCodegen.WalletAppInstall.COMPANION)(_ => true)

  def getInstall()(implicit ec: ExecutionContext): Future[
    JavaContract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ] =
    lookupInstall()
      .map(
        _.value.getOrElse(
          throw Status.NOT_FOUND.withDescription("WalletAppInstall contract").asRuntimeException()
        )
      )

  def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit] =
    acsStore.signalWhenIngested(offset)

  def streamAcceptedTransferOffers: Source[
    JavaContract[
      transferOffersCodegen.AcceptedTransferOffer.ContractId,
      transferOffersCodegen.AcceptedTransferOffer,
    ],
    NotUsed,
  ] =
    acsStore.streamContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION)

  def listExpiredTransferOffers(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[QueryResult[
    Seq[JavaContract[
      transferOffersCodegen.TransferOffer.ContractId,
      transferOffersCodegen.TransferOffer,
    ]]
  ]] =
    acs
      .listContracts(transferOffersCodegen.TransferOffer.COMPANION)
      .map(
        _.map(cos =>
          cos.iterator
            .filter(co => now.toInstant.isAfter(co.payload.expiresAt))
            .take(limit)
            .toSeq
        )
      )

  def listExpiredAcceptedTransferOffers(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[QueryResult[
    Seq[JavaContract[
      transferOffersCodegen.AcceptedTransferOffer.ContractId,
      transferOffersCodegen.AcceptedTransferOffer,
    ]]
  ]] =
    acs
      .listContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION)
      .map(
        _.map(cos =>
          cos.iterator
            .filter(co => now.toInstant.isAfter(co.payload.expiresAt))
            .take(limit)
            .toSeq
        )
      )

  def listExpiredAppPaymentRequests(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[QueryResult[
    Seq[JavaContract[
      walletCodegen.AppPaymentRequest.ContractId,
      walletCodegen.AppPaymentRequest,
    ]]
  ]] =
    acs
      .listContracts(walletCodegen.AppPaymentRequest.COMPANION)
      .map(
        _.map(cos =>
          cos.iterator
            .filter(co => now.toInstant.isAfter(co.payload.expiresAt))
            .take(limit)
            .toSeq
        )
      )

  def listSubscriptionStatesReadyForPayment(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[QueryResult[
    Seq[JavaContract[
      subsCodegen.SubscriptionIdleState.ContractId,
      subsCodegen.SubscriptionIdleState,
    ]]
  ]] = {
    def isReady(state: subsCodegen.SubscriptionIdleState) = now.toInstant.isAfter(
      state.nextPaymentDueAt.minus(CoinUtil.relTimeToDuration(state.payData.paymentDuration))
    )
    acs
      .listContracts(subsCodegen.SubscriptionIdleState.COMPANION)
      .map(_.map(cos => cos.iterator.filter(co => isReady(co.payload)).take(limit).toSeq))
  }
}

object UserWalletStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
  )(implicit
      ec: ExecutionContext
  ): UserWalletStore =
    storage match {
      case _: MemoryStorage => new InMemoryUserWalletStore(key, loggerFactory, timeouts)
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
    import AcsStore.mkFilter
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
        mkFilter(coinCodegen.AppReward.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.provider == endUser
        ),
        mkFilter(coinCodegen.ValidatorReward.COMPANION)(co =>
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
      ),
      Map(
        mkFilter(subsCodegen.SubscriptionContext.INTERFACE)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
        mkFilter(walletCodegen.DeliveryOffer.INTERFACE)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
      ),
    )
  }
}
