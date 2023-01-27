package com.daml.network.store

import com.digitalasset.canton.concurrent.FutureSupervisor

import scala.concurrent.ExecutionContext

/** In memory store setup shared by all of our apps
  */
abstract class InMemoryCoinAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
](implicit protected val ec: ExecutionContext)
    extends CoinAppStore[TXI, TXE] {
  protected def acsContractFilter: AcsStore.ContractFilter

  protected def futureSupervisor: FutureSupervisor

  private lazy val acsWithTxLog: InMemoryAcsWithTxLogStore[TXI, TXE] =
    new InMemoryAcsWithTxLogStore(
      loggerFactory,
      contractFilter = acsContractFilter,
      txLogParser = txLogParser,
      futureSupervisor = futureSupervisor,
      logAllStateUpdates = false,
    )

  override def acs: AcsStore = acsWithTxLog
  override def txLog: TxLogStore[TXI, TXE] = acsWithTxLog

  override lazy val domains: InMemoryDomainStore = new InMemoryDomainStore(loggerFactory)

  override lazy val acsIngestionSink: AcsStore.IngestionSink = acsWithTxLog.ingestionSink
  override lazy val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def close(): Unit = ()
}

abstract class InMemoryCoinAppStoreWithoutHistory(implicit
    override protected val ec: ExecutionContext
) extends InMemoryCoinAppStore[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]] {}
