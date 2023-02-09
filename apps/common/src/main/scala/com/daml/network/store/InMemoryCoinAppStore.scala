package com.daml.network.store

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

  private[network] override type PerDomainStore = InMemoryCoinAppStore.PerDomainStore[TXI, TXE]

  // TODO (#2620) remove
  private lazy val acsWithTxLog: InMemoryAcsWithTxLogStore[TXI, TXE] =
    new InMemoryAcsWithTxLogStore(
      loggerFactory,
      contractFilter = acsContractFilter,
      txLogParser = txLogParser,
      futureSupervisor = futureSupervisor,
      logAllStateUpdates = false,
    )

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
        logAllStateUpdates = false,
      ),
      new InMemoryTransferStore(loggerFactory, acsContractFilter.ingestionFilter.primaryParty),
    )

  private[network] override def storesIngestionSink(stores: PerDomainStore) =
    stores.acsWithTxLog.ingestionSink

  override def txLog: TxLogStore[TXI, TXE] = acsWithTxLog

  override protected[this] def storeAcs(store: PerDomainStore) = store.acsWithTxLog

  override protected[this] def storeTxLog(store: PerDomainStore) = store.acsWithTxLog

  override protected[this] def storeTransfer(store: PerDomainStore) = store.transfers

  override lazy val domains: InMemoryDomainStore = new InMemoryDomainStore(loggerFactory)

  override lazy val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def close(): Unit = ()
}

object InMemoryCoinAppStore {
  class PerDomainStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      val acsWithTxLog: InMemoryAcsWithTxLogStore[TXI, TXE],
      val transfers: InMemoryTransferStore,
  )
}

abstract class InMemoryCoinAppStoreWithoutHistory(implicit
    override protected val ec: ExecutionContext
) extends InMemoryCoinAppStore[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]] {}
