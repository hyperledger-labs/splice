package com.daml.network.wallet.store.memory

import com.daml.network.wallet.store.WalletAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

/** Example for in-memory store in the store pattern. */
class InMemoryWalletAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends WalletAppStore
    with NamedLogging {
  private val current = new AtomicInteger(0)
  override def increment(int: Int)(implicit tc: TraceContext): Future[Int] = {
    Future { current.addAndGet(int) }
  }

  override def close(): Unit = ()
}
