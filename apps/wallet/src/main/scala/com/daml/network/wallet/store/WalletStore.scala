package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.store.AcsStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty._
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries used by the wallet backend's gRPC request handlers and automation.
  *
  * Most methods have default implementations in terms of an [[com.daml.network.store.AcsStore]]
  * to simplify implementing the store. They are all made overridable so that a DB backed store can use
  * custom indices to ensure the scalability of these queries.
  *
  * TODO(#929): extend this store's functionality to cover 'WalletAppRequestStore' as well
  */
trait WalletStore extends AutoCloseable {

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  /** The key identifying the parties considered by this store. */
  def key: WalletStore.Key

  /** The list of all known end-user parties. */
  def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]]

  /** A stream of end-user parties that have been onboarded. */
  def getPartiesStream(implicit tc: TraceContext): Source[Seq[PartyId], NotUsed]
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
        // TODO(#929): do not use 'user' as the name when a party is meant
        mkFilter(walletCodegen.WalletAppInstall)(co =>
          co.payload.serviceUser == walletService &&
            co.payload.validatorUser == validator &&
            co.payload.svcUser == svc
        )
      ),
    )
  }

}
