package com.daml.network.sv.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.RetryProvider
import com.daml.network.store.InMemoryCNNodeAppStoreWithoutHistory
import com.daml.network.store.MultiDomainAcsStore.{ContractCompanion, ReadyContract}
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvStore, SvSvcStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.*

class InMemorySvSvcStore(
    override val key: SvStore.Key,
    override protected[this] val appConfig: SvAppBackendConfig,
    outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCNNodeAppStoreWithoutHistory
    with SvSvcStore {

  override protected lazy val loggerFactory: NamedLoggerFactory =
    outerLoggerFactory.append("store", "svcParty")

  override lazy val acsContractFilter =
    SvSvcStore.contractFilter(key.svcParty, key.svParty, appConfig)

  protected[this] override def listReadyContractsNotOnDomain[C, I <: ContractId[?], P](
      excludedDomain: DomainId,
      c: C,
  )(implicit
      tc: TraceContext,
      companion: ContractCompanion[C, I, P],
  ): Future[Seq[ReadyContract[I, P]]] =
    multiDomainAcsStore
      .listReadyContracts(c)
      .map(_.filterNot(_.domain == excludedDomain))
}
