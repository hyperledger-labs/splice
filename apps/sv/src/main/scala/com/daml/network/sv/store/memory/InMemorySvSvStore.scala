package com.daml.network.sv.store.memory

import com.daml.network.environment.RetryProvider
import com.daml.network.store.InMemoryCNNodeAppStoreWithoutHistory
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemorySvSvStore(
    override val key: SvStore.Key,
    override protected[this] val domainConfig: SvDomainConfig,
    outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCNNodeAppStoreWithoutHistory
    with SvSvStore {

  override protected lazy val loggerFactory: NamedLoggerFactory =
    outerLoggerFactory.append("store", "svParty")

  override lazy val acsContractFilter = SvSvStore.contractFilter(key)
}
