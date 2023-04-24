package com.daml.network.sv.store.memory

import com.daml.network.environment.RetryProvider
import com.daml.network.store.InMemoryCNNodeAppStoreWithoutHistory
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.{SvStore, SvSvcStore}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemorySvSvcStore(
    override val key: SvStore.Key,
    override protected[this] val appConfig: LocalSvAppConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCNNodeAppStoreWithoutHistory
    with SvSvcStore {

  override lazy val acsContractFilter =
    SvSvcStore.contractFilter(key.svcParty, key.svParty, appConfig)
}
