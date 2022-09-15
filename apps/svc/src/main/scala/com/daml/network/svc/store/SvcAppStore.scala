package com.daml.network.svc.store

import com.daml.network.codegen.CC.CoinRules.TransferResult
import com.daml.network.svc.store.memory.InMemorySvcAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Example for "Store" pattern. */
trait SvcAppStore extends AutoCloseable {
  def increment(int: Int)(implicit tc: TraceContext): Future[Int]

  def addTransfers(transfers: Seq[TransferResult]): Future[Unit]
}

object SvcAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): SvcAppStore =
    storage match {
      case _: MemoryStorage => new InMemorySvcAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
