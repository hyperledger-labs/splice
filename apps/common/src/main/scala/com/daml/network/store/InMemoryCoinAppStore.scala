package com.daml.network.store

import com.digitalasset.canton.concurrent.FutureSupervisor

import scala.concurrent.ExecutionContext

/** In memory store setup shared by all of our apps
  */
abstract class InMemoryCoinAppStore(implicit protected val ec: ExecutionContext)
    extends CoinAppStore {
  protected def acsContractFilter: AcsStore.ContractFilter

  protected def futureSupervisor: FutureSupervisor

  override lazy val acs: InMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      acsContractFilter,
      futureSupervisor,
      logAllStateUpdates = false,
    )
  override lazy val domains: InMemoryDomainStore = new InMemoryDomainStore(loggerFactory)

  override lazy val acsIngestionSink: AcsStore.IngestionSink = acs.ingestionSink
  override lazy val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def close(): Unit = ()
}
