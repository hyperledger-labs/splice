package com.daml.network.sv.store.memory

import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.daml.network.sv.store.SvStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.*

class InMemorySvStore(
    override val key: SvStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCoinAppStoreWithoutHistory
    with SvStore {

  override lazy val acsContractFilter = SvStore.contractFilter(key.svcParty)
}
