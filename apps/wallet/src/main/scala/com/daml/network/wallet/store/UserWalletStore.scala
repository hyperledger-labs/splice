package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  paymentchannel as channelCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.store.AcsStore
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries for a specific wallet end-user. */
trait UserWalletStore extends FlagCloseable with NamedLogging {
  import AcsStore.QueryResult

  /** The sink to use for ingesting data from the ledger into this store. */
  def acsIngestionSink: AcsStore.IngestionSink

  protected def acsStore: AcsStore

  /** A direct way to access the generic methods for querying the underlying ACS store. */
  // TODO(#1855): change our code to use this direct acs in all stores where it makes sense
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

  def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit] =
    acsStore.signalWhenIngested(offset)

  def lookupOnChannelPaymentRequestById(
      cid: ContractId[channelCodegen.OnChannelPaymentRequest]
  ): Future[QueryResult[Option[JavaContract[
    channelCodegen.OnChannelPaymentRequest.ContractId,
    channelCodegen.OnChannelPaymentRequest,
  ]]]] =
    acsStore.lookupContractById(channelCodegen.OnChannelPaymentRequest.COMPANION)(cid)

  def lookupAcceptedTransferOfferById(
      cid: ContractId[transferOffersCodegen.AcceptedTransferOffer]
  ): Future[QueryResult[Option[JavaContract[
    transferOffersCodegen.AcceptedTransferOffer.ContractId,
    transferOffersCodegen.AcceptedTransferOffer,
  ]]]] =
    acsStore.lookupContractById(transferOffersCodegen.AcceptedTransferOffer.COMPANION)(cid)

  def streamAcceptedTransferOffers: Source[
    JavaContract[
      transferOffersCodegen.AcceptedTransferOffer.ContractId,
      transferOffersCodegen.AcceptedTransferOffer,
    ],
    NotUsed,
  ] =
    acsStore.streamContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION)

  def lookupAppPaymentRequestById(
      cid: ContractId[walletCodegen.AppPaymentRequest]
  ): Future[QueryResult[
    Option[
      JavaContract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
    ]
  ]] =
    acsStore.lookupContractById(walletCodegen.AppPaymentRequest.COMPANION)(cid)

  def lookupDeliveryOfferById(
      cid: walletCodegen.DeliveryOffer.ContractId
  ): Future[QueryResult[
    Option[JavaContract[walletCodegen.DeliveryOffer.ContractId, walletCodegen.DeliveryOfferView]]
  ]] =
    acsStore.lookupContractById(walletCodegen.DeliveryOffer.INTERFACE)(cid)

  def lookupSubscriptionRequestById(
      cid: ContractId[subsCodegen.SubscriptionRequest]
  ): Future[QueryResult[
    Option[
      JavaContract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]
    ]
  ]] =
    acsStore.lookupContractById(subsCodegen.SubscriptionRequest.COMPANION)(cid)

  def lookupSubscriptionIdleStateById(
      cid: ContractId[subsCodegen.SubscriptionIdleState]
  ): Future[QueryResult[Option[
    JavaContract[subsCodegen.SubscriptionIdleState.ContractId, subsCodegen.SubscriptionIdleState]
  ]]] =
    acsStore.lookupContractById(subsCodegen.SubscriptionIdleState.COMPANION)(cid)

  def lookupSubscriptionContextById(
      cid: subsCodegen.SubscriptionContext.ContractId
  ): Future[QueryResult[
    Option[
      JavaContract[subsCodegen.SubscriptionContext.ContractId, subsCodegen.SubscriptionContextView]
    ]
  ]] =
    acsStore.lookupContractById(subsCodegen.SubscriptionContext.INTERFACE)(cid)

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

    def channelFilter(co: channelCodegen.PaymentChannel): Boolean =
      co.svc == svc && (co.sender == endUser || co.receiver == endUser)

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
        // Payment channels
        mkFilter(channelCodegen.PaymentChannelProposal.COMPANION)(co =>
          channelFilter(co.payload.channel)
        ),
        mkFilter(channelCodegen.PaymentChannel.COMPANION)(co => channelFilter(co.payload)),
        mkFilter(channelCodegen.OnChannelPaymentRequest.COMPANION)(co =>
          // We track requests for both sender and receiver, as both have to be displayed in the UI
          co.payload.svc == svc &&
            (co.payload.sender == endUser ||
              co.payload.receiver == endUser)
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
