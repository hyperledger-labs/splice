package com.daml.network.directory.store.memory
import com.daml.network.directory.store.DirectoryAppStore
import com.daml.network.store.{AcsStore, InMemoryAcsStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent._

/** Example for in-memory store in the store pattern. */
class InMemoryDirectoryAppStore(
    override protected val loggerFactory: NamedLoggerFactory,
    override val providerParty: PartyId,
)(implicit
    ec: ExecutionContext
) extends DirectoryAppStore
    with NamedLogging {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(loggerFactory, DirectoryAppStore.scope(providerParty))

  override val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def close(): Unit = ()

}
