package com.daml.network.store

import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.concurrent.FutureSupervisor

import scala.concurrent.ExecutionContext

/** In memory store setup shared by all of our apps
  */
abstract class InMemoryCNNodeAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
](implicit protected val ec: ExecutionContext)
    extends CNNodeAppStore[TXI, TXE] {

  protected def retryProvider: RetryProvider
  final protected def futureSupervisor: FutureSupervisor = retryProvider.futureSupervisor

  override val multiDomainAcsStore: InMemoryMultiDomainAcsStore[TXI, TXE] =
    new InMemoryMultiDomainAcsStore(
      loggerFactory,
      acsContractFilter,
      txLogParser,
      retryProvider,
    )

  override def txLog = multiDomainAcsStore

  override lazy val domains: DomainStore =
    new InMemoryDomainStore(
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      retryProvider,
    )

  override def close(): Unit = ()
}

abstract class InMemoryCNNodeAppStoreWithoutHistory(implicit
    override protected val ec: ExecutionContext
) extends InMemoryCNNodeAppStore[
      TxLogStore.IndexRecord,
      TxLogStore.Entry[TxLogStore.IndexRecord],
    ] {}
