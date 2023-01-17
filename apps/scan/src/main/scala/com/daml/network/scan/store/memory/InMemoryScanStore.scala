package com.daml.network.scan.store.memory

import com.daml.network.scan.store.ScanStore
import com.daml.network.store.InMemoryCoinAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*

class InMemoryScanStore(
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends InMemoryCoinAppStore
    with ScanStore {

  override lazy val acsContractFilter = ScanStore.contractFilter(svcParty)

  override def close(): Unit = {
    super.close()
  }
}
