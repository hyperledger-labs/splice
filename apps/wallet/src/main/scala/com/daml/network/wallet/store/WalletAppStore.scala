package com.daml.network.wallet.store

import com.daml.network.wallet.store.memory.InMemoryWalletAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Example for "Store" pattern. */
trait WalletAppStore extends AutoCloseable {
  def increment(int: Int)(implicit tc: TraceContext): Future[Int]
}

object WalletAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): WalletAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryWalletAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
