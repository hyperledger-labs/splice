package com.daml.network.svc.store

import com.daml.network.codegen.java.cc.api.v1.coin.{TransferResult, TransferSummary}
import com.daml.network.svc.store.memory.InMemorySvcEventsStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.{ExecutionContext, Future}

trait SvcEventsStore extends AutoCloseable {
  def addTransfers(transfers: Seq[TransferResult]): Future[Unit]
  def getTransferSummariesPerRound(round: Long): Seq[TransferSummary]
}

object SvcEventsStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): SvcEventsStore =
    storage match {
      case _: MemoryStorage => new InMemorySvcEventsStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
