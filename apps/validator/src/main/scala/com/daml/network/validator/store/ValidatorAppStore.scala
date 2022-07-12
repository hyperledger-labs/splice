package com.daml.network.validator.store

import com.daml.network.validator.store.memory.InMemoryValidatorAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Example for "Store" pattern. */
trait ValidatorAppStore extends AutoCloseable {
  def increment(int: Int)(implicit tc: TraceContext): Future[Int]
}

object ValidatorAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): ValidatorAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryValidatorAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
