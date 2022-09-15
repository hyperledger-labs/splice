package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.network.wallet.store.memory.InMemoryWalletAppPartyStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait WalletAppPartyStore extends AutoCloseable {
  def addParty(party: PartyId)(implicit tc: TraceContext): Future[Unit]
  def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]]
  def getPartiesStream(implicit tc: TraceContext): Source[Seq[PartyId], NotUsed]
}

object WalletAppPartyStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): WalletAppPartyStore =
    storage match {
      case _: MemoryStorage => new InMemoryWalletAppPartyStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
