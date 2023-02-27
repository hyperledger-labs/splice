package com.daml.network.sv.store.memory

import com.daml.network.environment.CoinRetries
import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.{SvStore, SvSvcStore}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemorySvSvcStore(
    override val key: SvStore.Key,
    override protected[this] val domainConfig: SvDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: CoinRetries,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCoinAppStoreWithoutHistory
    with SvSvcStore {

  override lazy val acsContractFilter = SvSvcStore.contractFilter(key.svcParty, key.svParty)
}
