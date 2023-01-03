package com.daml.network.sv.store.memory

import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.daml.network.sv.store.SvStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*

class InMemorySvStore(
    override val svParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends SvStore
    with NamedLogging {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(loggerFactory, SvStore.contractFilter(svParty))

  override val acs: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def close(): Unit = {}
}
