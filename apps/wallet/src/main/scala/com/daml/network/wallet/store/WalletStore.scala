package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.store.AcsStore
import com.daml.network.util.Contract
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.pretty._
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext

/** A store for serving all queries used by the wallet backend's gRPC request handlers and automation. */
trait WalletStore extends AutoCloseable with NamedLogging {

  protected implicit val ec: ExecutionContext

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  val acsStore: AcsStore

  /** The key identifying the parties considered by this store. */
  def key: WalletStore.Key

  def streamInstalls: Source[Contract[walletCodegen.WalletAppInstall], NotUsed] =
    acsStore.streamContracts(walletCodegen.WalletAppInstall)

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
  ): EndUserWalletStore = {
    val store = createEndUserStore(endUserName, endUserParty: PartyId)
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
  protected def createEndUserStore(endUserName: String, endUserParty: PartyId): EndUserWalletStore

  override def close(): Unit =
    Lifecycle.close(endUserStores.values.toSeq: _*)(logger)
}

object WalletStore {
  def apply(key: Key, storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): WalletStore =
    storage match {
      case _: MemoryStorage => new InMemoryWalletStore(key, loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  case class Key(
      /** The party used by the wallet service user to act on behalf of it's users. */
      walletServiceParty: PartyId,
      /** The validator party. */
      validatorParty: PartyId,
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
    val walletService = key.walletServiceParty.toPrim
    val validator = key.validatorParty.toPrim
    val svc = key.svcParty.toPrim

    AcsStore.SimpleContractFilter(
      key.walletServiceParty,
      Map(
        mkFilter(walletCodegen.WalletAppInstall)(co =>
          co.payload.walletServiceParty == walletService &&
            co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        )
      ),
    )
  }

}
