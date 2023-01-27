package com.daml.network.wallet.store.memory

import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.store.InMemoryCoinAppStore
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.*

class InMemoryUserWalletStore(
    override val key: UserWalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    timeouts: ProcessingTimeout,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext
) extends InMemoryCoinAppStore[
      UserWalletStore.TxLogIndexRecord,
      UserWalletStore.TxLogEntry,
    ]
    with UserWalletStore {

  override def toString: String = show"InMemoryUserWalletStore(endUserParty=${key.endUserParty})"

  override protected def acsContractFilter = UserWalletStore.contractFilter(key)
}
