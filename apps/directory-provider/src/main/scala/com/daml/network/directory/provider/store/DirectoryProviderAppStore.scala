package com.daml.network.directory.provider.store

import com.daml.network.directory.provider.store.memory.InMemoryDirectoryProviderAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.ExecutionContext

trait DirectoryProviderAppStore extends AutoCloseable {}

object DirectoryProviderAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): DirectoryProviderAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryDirectoryProviderAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
