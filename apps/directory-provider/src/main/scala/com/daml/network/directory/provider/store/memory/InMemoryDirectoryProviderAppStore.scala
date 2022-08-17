package com.daml.network.directory.provider.store.memory
import com.daml.network.directory.provider.store.DirectoryProviderAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

/** Example for in-memory store in the store pattern. */
class InMemoryDirectoryProviderAppStore(override protected val loggerFactory: NamedLoggerFactory)(
    implicit
    @nowarn("cat=unused")
    ec: ExecutionContext
) extends DirectoryProviderAppStore
    with NamedLogging {
  override def close(): Unit = ()
}
