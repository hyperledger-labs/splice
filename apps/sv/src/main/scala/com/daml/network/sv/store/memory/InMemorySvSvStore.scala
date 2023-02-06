package com.daml.network.sv.store.memory

import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemorySvSvStore(
    override val key: SvStore.Key,
    override protected[this] val domainConfig: SvDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCoinAppStoreWithoutHistory
    with SvSvStore {

  override lazy val acsContractFilter = SvSvStore.contractFilter(key)
}
