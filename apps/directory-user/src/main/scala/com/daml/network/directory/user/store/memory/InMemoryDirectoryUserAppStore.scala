package com.daml.network.directory.user.store.memory
import com.daml.network.directory.user.store.DirectoryUserAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

/** Example for in-memory store in the store pattern. */
class InMemoryDirectoryUserAppStore(override protected val loggerFactory: NamedLoggerFactory)(
    implicit
    @nowarn("cat=unused")
    ec: ExecutionContext
) extends DirectoryUserAppStore
    with NamedLogging {
  override def close(): Unit = ()
}
