package com.daml.network.scan.store.memory

import java.util.concurrent.atomic.AtomicInteger

import com.daml.network.scan.store.ScanAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

/** Example for in-memory store in the store pattern. */
class InMemoryScanAppStore(override protected val loggerFactory: NamedLoggerFactory)(
    implicit
    @nowarn("cat=unused")
    ec: ExecutionContext
) extends ScanAppStore
    with NamedLogging {
  override def close(): Unit = ()
}
