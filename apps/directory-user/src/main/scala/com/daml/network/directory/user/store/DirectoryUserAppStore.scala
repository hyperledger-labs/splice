package com.daml.network.directory.user.store

import com.daml.network.directory.user.store.memory.InMemoryDirectoryUserAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.ExecutionContext

trait DirectoryUserAppStore extends AutoCloseable {}

object DirectoryUserAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): DirectoryUserAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryDirectoryUserAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
