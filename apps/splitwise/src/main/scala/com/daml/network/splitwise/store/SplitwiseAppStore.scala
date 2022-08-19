package com.daml.network.splitwise.store

import com.daml.network.splitwise.store.memory.InMemorySplitwiseAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.ExecutionContext

trait SplitwiseAppStore extends AutoCloseable {}

object SplitwiseAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): SplitwiseAppStore =
    storage match {
      case _: MemoryStorage => new InMemorySplitwiseAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
