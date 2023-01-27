package com.daml.network.svc.store.memory

import com.daml.network.store.InMemoryCoinAppStore
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*

class InMemorySvcStore(
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit
    ec: ExecutionContext
) extends InMemoryCoinAppStore
    with SvcStore {

  override lazy val acsContractFilter = SvcStore.contractFilter(svcParty)
}
