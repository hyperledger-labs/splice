package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.store.AcsStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract as Contract
import com.daml.network.wallet.store.memory.InMemoryWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

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
    Option[Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]]
  ]] =
    acsStore.findContract(installCodegen.WalletAppInstall.COMPANION)(co =>
      co.payload.endUserParty == endUserParty.toProtoPrimitive
    )

  def lookupInstallByName(
      endUserName: String
  ): Future[QueryResult[
    Option[Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]]
  ]] =
    acsStore.findContract(installCodegen.WalletAppInstall.COMPANION)(co =>
      co.payload.endUserName == endUserName
    )

  def streamInstalls: Source[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall],
    NotUsed,
  ] =
    acsStore.streamContracts(installCodegen.WalletAppInstall.COMPANION)
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
        mkFilter(installCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.walletServiceParty == walletService &&
            co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        )
      ),
    )
  }

}
