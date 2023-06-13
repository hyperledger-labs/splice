package com.daml.network.wallet.store.memory

import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.store.InMemoryCNNodeAppStore
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.*

class InMemoryUserWalletStore(
    override val key: UserWalletStore.Key,
    override val defaultAcsDomain: DomainAlias,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val connection: CNLedgerConnection,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends InMemoryCNNodeAppStore[
      UserWalletStore.TxLogIndexRecord,
      UserWalletStore.TxLogEntry,
    ]
    with UserWalletStore {

  override def toString: String = show"InMemoryUserWalletStore(endUserParty=${key.endUserParty})"

  override protected def acsContractFilter = UserWalletStore.contractFilter(key)
}
