package com.daml.network.splitwise.store.memory

import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.ExecutionContext

class InMemorySplitwiseStore(
    override val providerParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCoinAppStoreWithoutHistory
    with SplitwiseStore {

  override lazy val acsContractFilter = SplitwiseStore.contractFilter(providerParty)
}
