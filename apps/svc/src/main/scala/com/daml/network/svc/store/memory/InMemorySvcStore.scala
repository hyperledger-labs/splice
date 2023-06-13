package com.daml.network.svc.store.memory

import com.daml.network.environment.RetryProvider
import com.daml.network.store.{InMemoryCNNodeAppStore, TxLogStore}
import com.daml.network.svc.config.SvcDomainConfig
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*

class InMemorySvcStore(
    override val svcParty: PartyId,
    override protected[this] val domainConfig: SvcDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends InMemoryCNNodeAppStore[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]]
    with SvcStore {

  override lazy val acsContractFilter = SvcStore.contractFilter(svcParty)
}
