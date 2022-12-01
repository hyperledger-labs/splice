package com.daml.network.wallet.store.memory

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.*

class InMemoryUserWalletStore(
    override val key: UserWalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext
) extends UserWalletStore
    with NamedLogging {

  override def toString: String = show"InMemoryUserWalletStore(endUserParty=${key.endUserParty})"

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      UserWalletStore.contractFilter(key),
      logAllStateUpdates = false,
    )

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink
}
