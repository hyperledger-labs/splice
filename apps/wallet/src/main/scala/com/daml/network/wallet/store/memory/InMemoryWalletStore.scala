package com.daml.network.wallet.store.memory

import com.daml.network.store.InMemoryCoinAppStore
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemoryWalletStore(
    override val key: WalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    val timeouts: ProcessingTimeout,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCoinAppStore
    with WalletStore {

  override lazy val acsContractFilter = WalletStore.contractFilter(key)
}
