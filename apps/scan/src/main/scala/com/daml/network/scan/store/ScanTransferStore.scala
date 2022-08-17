package com.daml.network.scan.store

import com.daml.network.history.CCTransaction
import com.daml.network.scan.store.memory.InMemoryScanTransferStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.{ExecutionContext, Future}

trait ScanTransferStore extends AutoCloseable {
  // TODO(i300): eventually this probably needs a start & end offset - related to #300
  def getTransferHistory: Future[Seq[CCTransaction]]
  def addTransaction(transaction: CCTransaction): Future[Unit]
}

object ScanTransferStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): ScanTransferStore =
    storage match {
      case _: MemoryStorage => new InMemoryScanTransferStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
