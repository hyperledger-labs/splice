package com.daml.network.sv.store.memory

import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.daml.network.sv.store.{SvStore, SvSvcStore}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemorySvSvcStore(
    override val key: SvStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCoinAppStoreWithoutHistory
    with SvSvcStore {

  override lazy val acsContractFilter = SvSvcStore.contractFilter(key.svcParty)
}
