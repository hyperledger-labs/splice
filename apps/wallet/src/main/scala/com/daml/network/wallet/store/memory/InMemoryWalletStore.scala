package com.daml.network.wallet.store.memory

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemoryWalletStore(
    override val key: WalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    val timeouts: ProcessingTimeout,
)(implicit
    protected val ec: ExecutionContext
) extends WalletStore {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      WalletStore.contractFilter(key),
      logAllStateUpdates = false,
    )

  val acs: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def onClosed(): Unit = {}
}
