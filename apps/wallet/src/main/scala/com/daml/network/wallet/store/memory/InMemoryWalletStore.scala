package com.daml.network.wallet.store.memory

import com.daml.network.store.{AcsStore, DomainStore, InMemoryAcsStore, InMemoryDomainStore}
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

  override val domains: InMemoryDomainStore =
    new InMemoryDomainStore(loggerFactory)

  val acs: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def onClosed(): Unit = {}
}
