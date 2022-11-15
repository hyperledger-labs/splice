package com.daml.network.scan.store

import com.daml.network.scan.store.memory.InMemoryScanCCHistoryStore
import com.daml.network.store.CCHistoryStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.{ExecutionContext, Future}

trait ScanCCHistoryStore extends CCHistoryStore {
  def getCurrentRound: Future[Long]
  def setCurrentRound(round: Long): Future[Unit]
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
