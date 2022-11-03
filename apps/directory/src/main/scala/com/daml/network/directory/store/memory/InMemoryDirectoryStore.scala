package com.daml.network.directory.store.memory

import com.daml.network.directory.store.DirectoryStore
import com.daml.network.store.{JavaAcsStore, JavaInMemoryAcsStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent._

class InMemoryDirectoryStore(
    override val providerParty: PartyId,
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends DirectoryStore
    with NamedLogging {

  private val inMemoryAcsStore =
    new JavaInMemoryAcsStore(loggerFactory, DirectoryStore.contractFilter(providerParty))

  override val acsStore: JavaAcsStore = inMemoryAcsStore

  override val acsIngestionSink: JavaAcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def close(): Unit = ()

}
