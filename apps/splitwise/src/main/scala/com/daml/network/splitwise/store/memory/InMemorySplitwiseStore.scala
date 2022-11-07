package com.daml.network.splitwise.store.memory

import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.{JavaInMemoryAcsStore => InMemoryAcsStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.ExecutionContext

class InMemorySplitwiseStore(
    override val providerParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends SplitwiseStore
    with NamedLogging {

  override val acsStore: InMemoryAcsStore =
    new InMemoryAcsStore(loggerFactory, SplitwiseStore.contractFilter(providerParty))

  override val acsIngestionSink = acsStore.ingestionSink

  override def close(): Unit = ()
}
