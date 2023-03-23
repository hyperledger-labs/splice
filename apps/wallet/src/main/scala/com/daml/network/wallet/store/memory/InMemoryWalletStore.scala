package com.daml.network.wallet.store.memory

import com.daml.network.environment.RetryProvider
import com.daml.network.store.InMemoryCNNodeAppStoreWithoutHistory
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemoryWalletStore(
    override val key: WalletStore.Key,
    override val defaultAcsDomain: DomainAlias,
    override protected val loggerFactory: NamedLoggerFactory,
    val timeouts: ProcessingTimeout,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: RetryProvider,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCNNodeAppStoreWithoutHistory
    with WalletStore {

  override lazy val acsContractFilter = WalletStore.contractFilter(key)
}
