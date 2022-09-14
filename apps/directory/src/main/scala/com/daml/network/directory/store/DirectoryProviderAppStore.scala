package com.daml.network.directory.store

import com.daml.network.directory.store.memory.InMemoryDirectoryAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.ExecutionContext

trait DirectoryAppStore extends AutoCloseable {}

object DirectoryAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): DirectoryAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryDirectoryAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
