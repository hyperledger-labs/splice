package com.daml.network.splitwise.store.memory
import com.daml.network.splitwise.store.SplitwiseAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

/** Example for in-memory store in the store pattern. */
class InMemorySplitwiseAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext
) extends SplitwiseAppStore
    with NamedLogging {
  override def close(): Unit = ()
}
