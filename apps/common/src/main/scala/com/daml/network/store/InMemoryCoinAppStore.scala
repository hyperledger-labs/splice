package com.daml.network.store

import com.daml.network.environment.CoinRetries
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId

import scala.concurrent.ExecutionContext

/** In memory store setup shared by all of our apps
  */
abstract class InMemoryCoinAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
](implicit protected val ec: ExecutionContext)
    extends CoinAppStore[TXI, TXE]
    with CoinAppStore.InMemoryMutableStoreMap[TXI, TXE] {
  protected def futureSupervisor: FutureSupervisor

  protected def retryProvider: CoinRetries

  private[network] override type PerDomainStore = InMemoryCoinAppStore.PerDomainStore[TXI, TXE]

  val multiDomainAcsStore: InMemoryMultiDomainAcsStore =
    new InMemoryMultiDomainAcsStore(loggerFactory, acsContractFilter)

  protected[this] override def newPerDomainStore(
      domain: DomainId,
      perDomainLoggerFactory: NamedLoggerFactory,
  ) =
    new InMemoryCoinAppStore.PerDomainStore(
      new InMemoryAcsWithTxLogStore(
        perDomainLoggerFactory,
        contractFilter = acsContractFilter,
        txLogParser = txLogParser,
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

object InMemoryCoinAppStore {
  class PerDomainStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      val acsWithTxLog: InMemoryAcsWithTxLogStore[TXI, TXE]
  )
}

abstract class InMemoryCoinAppStoreWithoutHistory(implicit
    override protected val ec: ExecutionContext
) extends InMemoryCoinAppStore[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]] {}
