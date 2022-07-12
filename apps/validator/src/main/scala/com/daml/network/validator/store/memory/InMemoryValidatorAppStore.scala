package com.daml.network.validator.store.memory

import java.util.concurrent.atomic.AtomicInteger

import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Example for in-memory store in the store pattern. */
class InMemoryValidatorAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends ValidatorAppStore
    with NamedLogging {
  private val current = new AtomicInteger(0)
  override def increment(int: Int)(implicit tc: TraceContext): Future[Int] = {
    Future { current.addAndGet(int) }
  }

  override def close(): Unit = ()
}
