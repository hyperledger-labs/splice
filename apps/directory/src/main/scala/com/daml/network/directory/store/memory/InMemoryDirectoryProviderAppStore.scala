package com.daml.network.directory.store.memory
import com.daml.network.directory.store.DirectoryAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

/** Example for in-memory store in the store pattern. */
class InMemoryDirectoryAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext
) extends DirectoryAppStore
    with NamedLogging {
  override def close(): Unit = ()
}
