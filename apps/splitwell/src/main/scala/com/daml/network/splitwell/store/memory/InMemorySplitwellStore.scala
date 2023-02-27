package com.daml.network.splitwell.store.memory

import com.daml.network.environment.CoinRetries
import com.daml.network.splitwell.config.SplitwellDomainConfig
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.ExecutionContext

class InMemorySplitwellStore(
    override val providerParty: PartyId,
    override protected[this] val domainConfig: SplitwellDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: CoinRetries,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCoinAppStoreWithoutHistory
    with SplitwellStore {

  override lazy val acsContractFilter = SplitwellStore.contractFilter(providerParty)
}
