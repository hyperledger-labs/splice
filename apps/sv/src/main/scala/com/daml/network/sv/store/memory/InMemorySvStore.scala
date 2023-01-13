package com.daml.network.sv.store.memory

import com.daml.network.store.InMemoryCoinAppStore
import com.daml.network.sv.store.SvStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*

class InMemorySvStore(
    override val svParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCoinAppStore
    with SvStore {

  override lazy val acsContractFilter = SvStore.contractFilter(svParty)
}
