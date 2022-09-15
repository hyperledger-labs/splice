package com.daml.network.wallet.store

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.network.wallet.store.memory.InMemoryWalletAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.{Coin => coinCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait WalletAppCoinStore {
  def addCoin(coin: Contract[coinCodegen.Coin])(implicit tc: TraceContext): Future[Unit]
  def archiveCoin(cid: Primitive.ContractId[coinCodegen.Coin])(implicit
      tc: TraceContext
  ): Future[Unit]
  def listCoins(party: PartyId)(implicit tc: TraceContext): Future[Seq[Contract[coinCodegen.Coin]]]
}

trait WalletAppPartyStore {
  def addParty(party: PartyId)(implicit tc: TraceContext): Future[Unit]
  def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]]
  def getPartiesStream(implicit tc: TraceContext): Source[Seq[PartyId], NotUsed]
}

trait WalletAppRequestStore {
  def addAppPaymentRequest(req: Contract[walletCodegen.AppPaymentRequest])(implicit
      tc: TraceContext
  ): Future[Unit]
  def removeAppPaymentRequest(req: Primitive.ContractId[walletCodegen.AppPaymentRequest])(implicit
      tc: TraceContext
  ): Future[Unit]
  def listAppPaymentRequests(party: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[walletCodegen.AppPaymentRequest]]]
  def findAppPaymentRequest(
      party: PartyId,
      cid: String,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[Contract[walletCodegen.AppPaymentRequest]]] = {
    listAppPaymentRequests(party).map(_.find(_.contractId == cid))
  }

  def addOnChannelPaymentRequest(req: Contract[walletCodegen.OnChannelPaymentRequest])(implicit
      tc: TraceContext
  ): Future[Unit]
  def removeOnChannelPaymentRequest(
      req: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  )(implicit
      tc: TraceContext
  ): Future[Unit]
  def listOnChannelPaymentRequests(party: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[walletCodegen.OnChannelPaymentRequest]]]
  def findOnChannelPaymentRequest(
      party: PartyId,
      cid: String,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[Contract[walletCodegen.OnChannelPaymentRequest]]] = {
    listOnChannelPaymentRequests(party).map(_.find(_.contractId == cid))
  }
}

trait WalletAppStore
    extends AutoCloseable
    with WalletAppCoinStore
    with WalletAppPartyStore
    with WalletAppRequestStore

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
