package com.daml.network.svc.store.memory

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.daml.network.svc.store.{SvcEventsStore, SvcStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent._

class InMemorySvcStore(
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends SvcStore
    with NamedLogging {

  override val events: SvcEventsStore = new InMemorySvcEventsStore(loggerFactory)

  private val inMemoryAcsStore =
    new InMemoryAcsStore(loggerFactory, SvcStore.contractFilter(svcParty))

  override val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def close(): Unit = {
    events.close()
  }

}
