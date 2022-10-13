package com.daml.network.wallet.store

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent._

class InMemoryWalletStore(
    override val key: WalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    protected val ec: ExecutionContext
) extends WalletStore {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      WalletStore.contractFilter(key),
      logAllStateUpdates = true,
    )

  // TODO(#790): review tracing strategy for setup steps
  noTracingLogger.debug(s"Created InMemoryWalletStore for $key")

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  def createEndUserStore(endUserName: String, endUserParty: PartyId): EndUserWalletStore =
    new InMemoryEndUserWalletStore(
      EndUserWalletStore.Key(
        svcParty = key.svcParty,
        endUserParty = endUserParty,
        endUserName = endUserName,
      ),
      loggerFactory.append("user", endUserName),
    )
}
