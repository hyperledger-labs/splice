package com.daml.network.wallet.admin

import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.client.binding.Primitive
import com.daml.network.admin.LedgerAutomationService
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.codegen.{CC => coinCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.util.Contract
import com.daml.network.wallet.store.WalletAppStore

import scala.concurrent.Future

class CoinIngestionService(
    store: WalletAppStore,
    protected val loggerFactory: NamedLoggerFactory,
) extends LedgerAutomationService
    with NamedLogging {

  override def templateIds: Seq[Primitive.TemplateId[_]] = Seq(
    coinCodegen.Coin.Coin.id,
    walletCodegen.AppPaymentRequest.id,
    walletCodegen.OnChannelPaymentRequest.id,
  )

  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] = Future.successful {
    DecodeUtil
      .decodeAllCreated(coinCodegen.Coin.Coin)(tx)
      .foreach(c => store.addCoin(Contract.fromCodegenContract(c)))
    DecodeUtil
      .decodeAllArchived(coinCodegen.Coin.Coin)(tx)
      .foreach(cid => store.archiveCoin(cid))

    DecodeUtil
      .decodeAllCreated(walletCodegen.AppPaymentRequest)(tx)
      .foreach(c => store.addAppPaymentRequest(Contract.fromCodegenContract(c)))
    DecodeUtil
      .decodeAllArchived(walletCodegen.AppPaymentRequest)(tx)
      .foreach(cid => store.removeAppPaymentRequest(cid))

    DecodeUtil
      .decodeAllCreated(walletCodegen.OnChannelPaymentRequest)(tx)
      .foreach(c => store.addOnChannelPaymentRequest(Contract.fromCodegenContract(c)))
    DecodeUtil
      .decodeAllArchived(walletCodegen.OnChannelPaymentRequest)(tx)
      .foreach(cid => store.removeOnChannelPaymentRequest(cid))
  }

  override def close(): Unit = ()

}
