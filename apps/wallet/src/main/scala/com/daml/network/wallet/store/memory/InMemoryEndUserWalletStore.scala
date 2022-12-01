package com.daml.network.wallet.store.memory

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.daml.network.wallet.store.EndUserWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.*

class InMemoryEndUserWalletStore(
    override val key: EndUserWalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext
) extends EndUserWalletStore
    with NamedLogging {

  override def toString: String = show"InMemoryEndUserWalletStore(endUserParty=${key.endUserParty})"

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      EndUserWalletStore.contractFilter(key),
      logAllStateUpdates = false,
    )

  // TODO(#1747): review tracing strategy for setup steps
  noTracingLogger.debug(s"Created InMemoryEndUserWalletStore for $key")

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink
}
