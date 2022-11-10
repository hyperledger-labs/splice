package com.daml.network.wallet.store.memory

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

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

  // TODO(#790): review tracing strategy for setup steps
  noTracingLogger.debug(s"Created InMemoryWalletStore for $key")

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  def createEndUserStore(
      endUserName: String,
      endUserParty: PartyId,
      timeouts: ProcessingTimeout,
  ): EndUserWalletStore =
    new InMemoryEndUserWalletStore(
      EndUserWalletStore.Key(
        svcParty = key.svcParty,
        endUserParty = endUserParty,
        endUserName = endUserName,
      ),
      loggerFactory.append("user", endUserName),
      timeouts = timeouts,
    )
}
