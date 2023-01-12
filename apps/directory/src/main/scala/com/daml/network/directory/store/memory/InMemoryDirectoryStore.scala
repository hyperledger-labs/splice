package com.daml.network.directory.store.memory

import com.daml.network.directory.store.DirectoryStore
import com.daml.network.store.{AcsStore, DomainStore, InMemoryAcsStore, InMemoryDomainStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent._

class InMemoryDirectoryStore(
    override val providerParty: PartyId,
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit override protected val ec: ExecutionContext)
    extends DirectoryStore
    with NamedLogging {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(loggerFactory, DirectoryStore.contractFilter(providerParty))

  override val acs: AcsStore = inMemoryAcsStore

  override val domains: InMemoryDomainStore =
    new InMemoryDomainStore(loggerFactory)

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def close(): Unit = ()

}
