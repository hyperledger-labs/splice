package com.daml.network.validator.store

import com.daml.network.validator.store.db.DbDummyStore
import com.daml.network.validator.store.memory.InMemoryDummyStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Example for "Store" pattern. */
trait DummyStore extends AutoCloseable {
  def increment(int: Int)(implicit tc: TraceContext): Future[Int]
}

object DummyStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): DummyStore =
    storage match {
      case _: MemoryStorage => new InMemoryDummyStore(loggerFactory)
      case _: DbStorage => new DbDummyStore()
    }
}
