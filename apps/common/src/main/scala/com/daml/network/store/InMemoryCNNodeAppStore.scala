package com.daml.network.store

import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId

import scala.concurrent.ExecutionContext

/** In memory store setup shared by all of our apps
  */
abstract class InMemoryCNNodeAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
](implicit protected val ec: ExecutionContext)
    extends CNNodeAppStore[TXI, TXE]
    with CNNodeAppStore.InMemoryMutableStoreMap[TXI, TXE] {
  protected def futureSupervisor: FutureSupervisor

  protected def retryProvider: RetryProvider

  private[network] override type PerDomainStore = InMemoryCNNodeAppStore.PerDomainStore[TXI, TXE]

  val multiDomainAcsStore: InMemoryMultiDomainAcsStore =
    new InMemoryMultiDomainAcsStore(loggerFactory, acsContractFilter)

  protected[this] override def newPerDomainStore(
      domain: DomainId,
      perDomainLoggerFactory: NamedLoggerFactory,
  ) =
    new InMemoryCNNodeAppStore.PerDomainStore(
      new InMemoryAcsWithTxLogStore(
        perDomainLoggerFactory,
        contractFilter = acsContractFilter,
        txLogParser = txLogParser,
        domainId = domain,
        futureSupervisor = futureSupervisor,
        retryProvider = retryProvider,
        logAllStateUpdates = false,
      )
    )

  private[network] override def storesIngestionSink(stores: PerDomainStore) =
    stores.acsWithTxLog.ingestionSink

  override protected[this] def storeAcs(store: PerDomainStore) = store.acsWithTxLog

  override protected[this] def storeTxLog(store: PerDomainStore) = store.acsWithTxLog

  override lazy val domains: InMemoryDomainStore = new InMemoryDomainStore(loggerFactory)

  override lazy val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def close(): Unit = ()
}

object InMemoryCNNodeAppStore {
  class PerDomainStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      val acsWithTxLog: InMemoryAcsWithTxLogStore[TXI, TXE]
  )
}

abstract class InMemoryCNNodeAppStoreWithoutHistory(implicit
    override protected val ec: ExecutionContext
) extends InMemoryCNNodeAppStore[
      TxLogStore.IndexRecord,
      TxLogStore.Entry[TxLogStore.IndexRecord],
    ] {}
