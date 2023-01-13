package com.daml.network.svc.store.memory

import com.daml.network.store.InMemoryCoinAppStore
import com.daml.network.svc.store.{SvcEventsStore, SvcStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent._

class InMemorySvcStore(
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends InMemoryCoinAppStore
    with SvcStore {

  override lazy val acsContractFilter = SvcStore.contractFilter(svcParty)

  override val events: SvcEventsStore = new InMemorySvcEventsStore(loggerFactory)
}
