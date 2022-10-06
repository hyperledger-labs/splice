package com.daml.network.wallet.store

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent._

class InMemoryEndUserWalletStore(
    override val key: EndUserWalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends EndUserWalletStore
    with NamedLogging {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      EndUserWalletStore.contractFilter(key),
      logAllStateUpdates = true,
    )

  noTracingLogger.debug(s"Created InMemoryEndUserWalletStore for $key")

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def close(): Unit = ()
}
