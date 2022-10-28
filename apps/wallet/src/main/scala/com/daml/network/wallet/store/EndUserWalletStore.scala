package com.daml.network.wallet.store

import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.codegen.CC.{
  Coin => coinCodegen,
  CoinRules => coinRulesCodegen,
  Round => roundCodegen,
}
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.store.AcsStore
import com.daml.network.util.Contract
import com.daml.network.wallet.store.memory.InMemoryEndUserWalletStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries for a specific wallet end-user. */
trait EndUserWalletStore extends AutoCloseable {
  import AcsStore.QueryResult

  /** The sink to use for ingesting data from the ledger into this store. */
  def acsIngestionSink: AcsStore.IngestionSink

  protected def acsStore: AcsStore

  /** The key identifying the parties considered by this store. */
  def key: EndUserWalletStore.Key

  /** Lookup the end-users install contract.
    *
    * Returns an Option, as there can be races where this fails, and the caller has better context on
    * how to deal with this error.
    */
  def lookupInstall(): Future[QueryResult[Option[Contract[walletCodegen.WalletAppInstall]]]] =
    acsStore.findContract(walletCodegen.WalletAppInstall)(_ => true)

  def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit] =
    acsStore.signalWhenIngested(offset)

  def findContract[T](
      templateCompanion: TemplateCompanion[T],
      filter: Contract[T] => Boolean = (_: Contract[T]) => true,
  ): Future[QueryResult[Option[Contract[T]]]] = acsStore.findContract(templateCompanion)(filter)

  def listContracts[T](
      templateCompanion: TemplateCompanion[T],
      filter: Contract[T] => Boolean = (_: Contract[T]) => true,
  ): Future[QueryResult[Seq[Contract[T]]]] = acsStore.listContracts(templateCompanion, filter)

  def lookupOnChannelPaymentRequestById(
      cid: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ): Future[QueryResult[Option[Contract[walletCodegen.OnChannelPaymentRequest]]]] =
    acsStore.lookupContractById(walletCodegen.OnChannelPaymentRequest)(cid)

  def lookupAppMultiPaymentRequestById(
      cid: Primitive.ContractId[walletCodegen.AppMultiPaymentRequest]
  ): Future[QueryResult[Option[Contract[walletCodegen.AppMultiPaymentRequest]]]] =
    acsStore.lookupContractById(walletCodegen.AppMultiPaymentRequest)(cid)

  def lookupSubscriptionRequestById(
      cid: Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionRequest]
  ): Future[QueryResult[Option[Contract[walletCodegen.Subscriptions.SubscriptionRequest]]]] =
    acsStore.lookupContractById(walletCodegen.Subscriptions.SubscriptionRequest)(cid)

  def lookupSubscriptionIdleStateById(
      cid: Primitive.ContractId[walletCodegen.Subscriptions.SubscriptionIdleState]
  ): Future[QueryResult[Option[Contract[walletCodegen.Subscriptions.SubscriptionIdleState]]]] =
    acsStore.lookupContractById(walletCodegen.Subscriptions.SubscriptionIdleState)(cid)

  def lookupLatestOpenMiningRound(
  ): Future[QueryResult[Option[Contract[roundCodegen.OpenMiningRound]]]]

  /** Wrapper around lookupLatestOpenMiningRound that fails with a grpc exception status
    * FAILED_PRECONDITION for the common case where we just want to fail and retry
    * if there is no active round.
    */
  def getLatestOpenMiningRound()(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[roundCodegen.OpenMiningRound]]] =
    lookupLatestOpenMiningRound().map { result =>
      result.map(
        _.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active OpenMiningRound contract")
          )
        )
      )
    }

  def getCoinRules()(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[coinRulesCodegen.CoinRules]]] =
    findContract(coinRulesCodegen.CoinRules).map(
      _.map(
        _.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active CoinRules contract")
          )
        )
      )
    )

  /** Get the context required for executing a transfer. This must be run
    * on the store of the validator user since the primary party of end users
    * is not a stakeholder on the contracts used here.
    */
  def getPaymentTransferContext()(implicit
      ec: ExecutionContext
  ): Future[coinRulesCodegen.PaymentTransferContext] =
    for {
      coinRules <- getCoinRules()
      openRound <- getLatestOpenMiningRound()
      issuingMiningRounds <- listContracts(roundCodegen.IssuingMiningRound)
      validatorRights <- listContracts(coinCodegen.ValidatorRight)
    } yield {
      val transferContext = coinRulesCodegen.TransferContext(
        openRound.value.contractId,
        validatorRights = validatorRights.value
          .map(r => (r.payload.user, r.contractId))
          .toMap[Primitive.Party, Primitive.ContractId[coinCodegen.ValidatorRight]],
        issuingMiningRounds = issuingMiningRounds.value
          .map(r => (r.payload.round, r.contractId))
          .toMap[roundCodegen.Round, Primitive.ContractId[roundCodegen.IssuingMiningRound]],
      )
      coinRulesCodegen.PaymentTransferContext(
        coinRules.value.contractId,
        transferContext,
      )
    }

}

object EndUserWalletStore {
  def apply(key: Key, storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): EndUserWalletStore =
    storage match {
      case _: MemoryStorage => new InMemoryEndUserWalletStore(key, loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  case class Key(
      /** The party-id of the SVC issuing CC managed by this end-user wallet. */
      svcParty: PartyId,
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
    val endUser = key.endUserParty.toPrim
    val svc = key.svcParty.toPrim

    def channelFilter(co: walletCodegen.PaymentChannel): Boolean =
      co.svc == svc && (co.sender == endUser || co.receiver == endUser)

    AcsStore.SimpleContractFilter(
      key.endUserParty,
      Map(
        // Install
        mkFilter(walletCodegen.WalletAppInstall)(co =>
          co.payload.svcParty == svc &&
            co.payload.endUserParty == endUser
        ),
        // Coins
        mkFilter(coinCodegen.Coin)(co =>
          co.payload.svc == svc &&
            co.payload.owner == endUser
        ),
        mkFilter(coinCodegen.LockedCoin)(co =>
          co.payload.coin.svc == svc &&
            co.payload.coin.owner == endUser
        ),
        // Rewards
        mkFilter(coinCodegen.AppReward)(co =>
          co.payload.svc == svc &&
            co.payload.provider == endUser
        ),
        mkFilter(coinCodegen.ValidatorReward)(co =>
          co.payload.svc == svc &&
            co.payload.user == endUser
        ),
        mkFilter(coinCodegen.ValidatorRight)(co =>
          // All validator rights that entitle the endUser to collect rewards as a validator operator
          co.payload.svc == svc &&
            co.payload.validator == endUser
        ),
        // Payment channels
        mkFilter(walletCodegen.PaymentChannelProposal)(co => channelFilter(co.payload.channel)),
        mkFilter(walletCodegen.PaymentChannel)(co => channelFilter(co.payload)),
        mkFilter(walletCodegen.OnChannelPaymentRequest)(co =>
          // We track requests for both sender and receiver, as both have to be displayed in the UI
          co.payload.svc == svc &&
            (co.payload.sender == endUser ||
              co.payload.receiver == endUser)
        ),
        // We only ingest app (multi) payment contracts where the user is the sender,
        // as app (multi) payments the user is a receiver or a provider are handled by
        // the provider's app
        mkFilter(walletCodegen.AppMultiPaymentRequest)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
        mkFilter(walletCodegen.AcceptedAppMultiPayment)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
        // Subscriptions
        mkFilter(walletCodegen.Subscriptions.SubscriptionRequest)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(walletCodegen.Subscriptions.SubscriptionIdleState)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(walletCodegen.Subscriptions.SubscriptionInitialPayment)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(walletCodegen.Subscriptions.SubscriptionPayment)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(roundCodegen.OpenMiningRound)(co => co.payload.svc == svc),
        mkFilter(roundCodegen.IssuingMiningRound)(co => co.payload.svc == svc),
        mkFilter(coinRulesCodegen.CoinRules)(co => co.payload.svc == svc),
      ),
    )
  }

}
