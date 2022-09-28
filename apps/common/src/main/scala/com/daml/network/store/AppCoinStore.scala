package com.daml.network.store

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.{Coin => coinCodegen}
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait AppCoinStore {
  def addCoin(coin: Contract[coinCodegen.Coin])(implicit tc: TraceContext): Future[Unit]
  def addLockedCoin(lockedCoin: Contract[coinCodegen.LockedCoin])(implicit
      tc: TraceContext
  ): Future[Unit]
  def archiveCoin(cid: Primitive.ContractId[coinCodegen.Coin])(implicit
      tc: TraceContext
  ): Future[Unit]
  def archiveLockedCoin(cid: Primitive.ContractId[coinCodegen.LockedCoin])(implicit
      tc: TraceContext
  ): Future[Unit]
  def listCoins(party: PartyId)(implicit tc: TraceContext): Future[Seq[Contract[coinCodegen.Coin]]]
  def listLockedCoins(party: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[coinCodegen.LockedCoin]]]
}

object AppCoinStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): AppCoinStore =
    storage match {
      case _: MemoryStorage => new InMemoryAppCoinStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
