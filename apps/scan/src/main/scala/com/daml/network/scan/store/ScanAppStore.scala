package com.daml.network.scan.store

import com.daml.network.scan.store.memory.InMemoryScanAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait ScanAppStore extends AutoCloseable {}

object ScanAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): ScanAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryScanAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
