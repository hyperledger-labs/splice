package com.daml.network.wallet.store

import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.codegen.CC.{
  Coin as coinCodegen,
  CoinRules as coinRulesCodegen,
  Round as roundCodegen,
}
import com.daml.network.codegen.CN.Wallet.Subscriptions as subsCodegen
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.store.AcsStore
import com.daml.network.util.Contract
import com.daml.network.wallet.store.memory.InMemoryEndUserWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.NoTracing
import com.digitalasset.canton.util.retry
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries for a specific wallet end-user. */
trait EndUserWalletStore extends FlagCloseableAsync with NoTracing with NamedLogging {
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

  def signalWhenIngested(offset: String): Future[Unit] =
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
      cid: Primitive.ContractId[subsCodegen.SubscriptionRequest]
  ): Future[QueryResult[Option[Contract[subsCodegen.SubscriptionRequest]]]] =
    acsStore.lookupContractById(subsCodegen.SubscriptionRequest)(cid)

  def lookupSubscriptionIdleStateById(
      cid: Primitive.ContractId[subsCodegen.SubscriptionIdleState]
  ): Future[QueryResult[Option[Contract[subsCodegen.SubscriptionIdleState]]]] =
    acsStore.lookupContractById(subsCodegen.SubscriptionIdleState)(cid)

  def lookupLatestOpenMiningRound(
  ): Future[QueryResult[Option[Contract[roundCodegen.OpenMiningRound]]]]

  implicit val success: retry.Success[Any] = retry.Success.always
  val policy = (msg: String) =>
    retry.Backoff(
      logger,
      this,
      retry.Forever,
      1.seconds,
      10.seconds,
      msg,
    )

  /** Wrapper around lookupLatestOpenMiningRound that retries if no open round is found,
    * which may happen if the wallet is used before its automation starts ingesting the round contracts.
    * TODO(M1-52): once round automation is implemented, we may want to consider replacing this with
    *              the wallet initialization synchronizing on the first round being ingested instead.
    */
  def getLatestOpenMiningRound()(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[roundCodegen.OpenMiningRound]]] = {
    policy("Waiting for open mining round to be ingested")(
      lookupLatestOpenMiningRound().map { result =>
        result.map(
          _.getOrElse(
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription("No active OpenMiningRound contract")
            )
          )
        )
      },
      AllExnRetryable,
    )
  }

  def getCoinRules()(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[coinRulesCodegen.CoinRules]]] = {

    findContract(coinRulesCodegen.CoinRules).map(
      _.map(
        _.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active CoinRules contract")
          )
        )
      )
    )
  }

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
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
  )(implicit
      ec: ExecutionContext
  ): EndUserWalletStore =
    storage match {
      case _: MemoryStorage => new InMemoryEndUserWalletStore(key, loggerFactory, timeouts)
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
        mkFilter(subsCodegen.Subscription)(co =>
          co.payload.svc == svc &&
            co.payload.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionRequest)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionIdleState)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionInitialPayment)(co =>
          co.payload.subscriptionData.svc == svc &&
            co.payload.subscriptionData.sender == endUser
        ),
        mkFilter(subsCodegen.SubscriptionPayment)(co =>
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
