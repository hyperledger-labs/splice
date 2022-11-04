package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.codegen.java.cc.{coin as coinCodegen}
import com.daml.network.codegen.java.cn.wallet as walletCodegen
import com.daml.network.store.JavaAcsStore.QueryResult
import com.daml.network.store.{JavaAcsStore as AcsStore}
import com.daml.network.util.{JavaContract as Contract}
import com.daml.network.wallet.store.memory.InMemoryWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries used by the wallet backend's gRPC request handlers and automation. */
trait WalletStore extends AutoCloseable with NamedLogging {

  protected implicit val ec: ExecutionContext

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  protected val acsStore: AcsStore

  /** The key identifying the parties considered by this store. */
  def key: WalletStore.Key

  def lookupInstallByParty(
      endUserParty: PartyId
  ): Future[QueryResult[
    Option[Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]]
  ]] =
    acsStore.findContract(walletCodegen.WalletAppInstall.COMPANION)(co =>
      co.payload.endUserParty == endUserParty.toProtoPrimitive
    )

  def lookupInstallByName(
      endUserName: String
  ): Future[QueryResult[
    Option[Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]]
  ]] =
    acsStore.findContract(walletCodegen.WalletAppInstall.COMPANION)(co =>
      co.payload.endUserName == endUserName
    )

  def streamInstalls: Source[
    Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall],
    NotUsed,
  ] =
    acsStore.streamContracts(walletCodegen.WalletAppInstall.COMPANION)

  // Methods to implement per-end-user stores
  private[this] val endUserStores: TrieMap[String, EndUserWalletStore] = TrieMap.empty

  /** Lookup an end-user's store.
    * Succeeds if the user has been onboarded and its store has been initialized.
    */
  final def lookupEndUserStore(endUserName: String): Option[EndUserWalletStore] =
    endUserStores.get(endUserName)

  /** Get or create the store for an end-user. Intended to be called when a user is onboarded.
    *
    * Do not use this in request handlers to avoid leaking resources.
    */
  final def getOrCreateEndUserStore(
      endUserName: String,
      endUserParty: PartyId,
      timeouts: ProcessingTimeout,
  ): EndUserWalletStore = {
    val store = createEndUserStore(endUserName, endUserParty: PartyId, timeouts)
    endUserStores
      .putIfAbsent(endUserName, store)
      .fold(store)(existingStore => {
        store.close()
        existingStore
      })
  }

  /** Remove an end-user store. Intended to be called when a user is off-boarded. */
  final def removeEndUserStore(endUserName: String): Unit =
    endUserStores.remove(endUserName).fold(())(store => store.close())

  /** Abstract method to create an end-users store. */
  protected def createEndUserStore(
      endUserName: String,
      endUserParty: PartyId,
      timeouts: ProcessingTimeout,
  ): EndUserWalletStore

  // TODO(M1-52): this function probably needs restructuring to integrate it with automation rewards collection; e.g., make it streaming
  def listValidatorRewardsCollectableBy(
      validatorUserStore: EndUserWalletStore
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[coinCodegen.ValidatorReward.ContractId, coinCodegen.ValidatorReward]]] =
    for {
      QueryResult(_, validatorRights) <- validatorUserStore.listContracts(
        coinCodegen.ValidatorRight.COMPANION
      )
      users = validatorRights.map(c => PartyId.tryFromProtoPrimitive(c.payload.user)).toSet
      validatorRewardsFs: Seq[
        Future[Seq[Contract[coinCodegen.ValidatorReward.ContractId, coinCodegen.ValidatorReward]]]
      ] = users.toSeq
        .map(u =>
          this.lookupInstallByParty(u).flatMap {
            case QueryResult(_, None) =>
              logger.warn(
                s"ValidatorRight of ${validatorUserStore.key.endUserParty} for end-user party $u has no associated WalletAppInstall contract."
              )
              Future.successful(Seq.empty)
            case QueryResult(_, Some(install)) =>
              this.lookupEndUserStore(install.payload.endUserName) match {
                case None =>
                  logger.warn(
                    s"Might miss validator rewards as the EndUserWalletStore for end-user name ${install.payload.endUserName} is not (yet) setup."
                  )
                  Future.successful(Seq.empty)
                case Some(userStore) =>
                  userStore.listContracts(coinCodegen.ValidatorReward.COMPANION).map(_.value)
              }
          }
        )
      validatorRewards <- Future.sequence(validatorRewardsFs)
    } yield validatorRewards.flatten

  override def close(): Unit =
    Lifecycle.close(endUserStores.values.toSeq: _*)(logger)
}

object WalletStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
  )(implicit
      ec: ExecutionContext
  ): WalletStore =
    storage match {
      case _: MemoryStorage => new InMemoryWalletStore(key, loggerFactory, timeouts)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  case class Key(
      /** The party used by the wallet service user to act on behalf of it's users. */
      walletServiceParty: PartyId,
      /** The validator party. */
      validatorParty: PartyId,
      /** The validator user name. */
      validatorUserName: String,
      /** The party-id of the SVC issuing CC managed by this wallet. */
      svcParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("walletServiceParty", _.walletServiceParty),
      param("validatorParty", _.validatorParty),
      param("svcParty", _.svcParty),
    )
  }

  /** Contract of a wallet store for a specific wallet-service party. */
  def contractFilter(key: Key): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val walletService = key.walletServiceParty.toProtoPrimitive
    val validator = key.validatorParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      key.walletServiceParty,
      Map(
        mkFilter(walletCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.walletServiceParty == walletService &&
            co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        )
      ),
    )
  }

}
