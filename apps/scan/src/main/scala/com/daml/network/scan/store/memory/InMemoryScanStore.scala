package com.daml.network.scan.store.memory

import com.daml.network.scan.store.ScanStore
import com.daml.network.store.{AcsStore, CCHistoryStore, InMemoryAcsStore, InMemoryCCHistoryStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*

class InMemoryScanStore(
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends ScanStore
    with NamedLogging {

  override val history: CCHistoryStore = new InMemoryCCHistoryStore(loggerFactory)

  private val inMemoryAcsStore =
    new InMemoryAcsStore(loggerFactory, ScanStore.contractFilter(svcParty))

  override val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def close(): Unit = history.close()
}
