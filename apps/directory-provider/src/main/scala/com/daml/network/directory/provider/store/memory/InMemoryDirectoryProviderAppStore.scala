package com.daml.network.directory.provider.store.memory

import java.util.concurrent.atomic.AtomicInteger

import com.daml.network.directory.provider.store.DirectoryProviderAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

/** Example for in-memory store in the store pattern. */
class InMemoryDirectoryProviderAppStore(override protected val loggerFactory: NamedLoggerFactory)(
    implicit
    @nowarn("cat=unused")
    ec: ExecutionContext
) extends DirectoryProviderAppStore
    with NamedLogging {
  override def close(): Unit = ()
}
