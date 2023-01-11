package com.daml.network.splitwise.store.memory

import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.{AcsStore, DomainStore, InMemoryAcsStore, InMemoryDomainStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.ExecutionContext

class InMemorySplitwiseStore(
    override val providerParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit override protected val ec: ExecutionContext)
    extends SplitwiseStore
    with NamedLogging {

  override val acs: InMemoryAcsStore =
    new InMemoryAcsStore(loggerFactory, SplitwiseStore.contractFilter(providerParty))

  override val domains: InMemoryDomainStore =
    new InMemoryDomainStore(loggerFactory)

  override val acsIngestionSink: AcsStore.IngestionSink = acs.ingestionSink

  override val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def close(): Unit = ()
}
