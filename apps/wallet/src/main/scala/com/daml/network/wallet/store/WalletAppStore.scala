package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.network.wallet.store.memory.InMemoryWalletAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.Coin.Coin
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait WalletAppCoinStore {
  def addCoin(coin: Contract[Coin])(implicit tc: TraceContext): Future[Unit]
  def archiveCoin(cid: Primitive.ContractId[Coin])(implicit tc: TraceContext): Future[Unit]
  def listCoins(party: PartyId)(implicit tc: TraceContext): Future[Seq[Contract[Coin]]]
}

trait WalletAppPartyStore {
  def addParty(party: PartyId)(implicit tc: TraceContext): Future[Unit]
  def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]]
  def getPartiesStream(implicit tc: TraceContext): Source[Seq[PartyId], NotUsed]
}

/** Example for "Store" pattern. */
trait WalletAppStore extends AutoCloseable with WalletAppCoinStore with WalletAppPartyStore

object WalletAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): WalletAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryWalletAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
