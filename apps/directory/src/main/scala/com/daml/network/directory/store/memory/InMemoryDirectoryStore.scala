package com.daml.network.directory.store.memory

import com.daml.network.directory.store.DirectoryStore
import com.daml.network.store.InMemoryCoinAppStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent._

class InMemoryDirectoryStore(
    override val providerParty: PartyId,
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCoinAppStore
    with DirectoryStore {

  override lazy val acsContractFilter = DirectoryStore.contractFilter(providerParty)
}
