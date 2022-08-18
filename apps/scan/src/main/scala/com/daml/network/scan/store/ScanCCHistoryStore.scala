package com.daml.network.scan.store

import com.daml.network.history.CoinTransaction
import com.daml.network.scan.store.memory.InMemoryScanCCHistoryStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.{ExecutionContext, Future}

trait ScanCCHistoryStore extends AutoCloseable {
  // TODO(i300): eventually this probably needs a start & end offset - related to #300
  def getCCHistory: Future[Seq[CoinTransaction]]
  def addTransaction(transaction: CoinTransaction): Future[Unit]
}

object ScanCCHistoryStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): ScanCCHistoryStore =
    storage match {
      case _: MemoryStorage => new InMemoryScanCCHistoryStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
