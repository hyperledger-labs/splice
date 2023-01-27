package com.daml.network.store

import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.logging.NamedLogging

import scala.concurrent.ExecutionContext

/** Store setup shared by all of our apps
  */
trait CoinAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
] extends NamedLogging
    with AutoCloseable {
  implicit protected def ec: ExecutionContext

  def acs: AcsStore
  def txLog: TxLogStore[TXI, TXE]
  def domains: DomainStore

  def acsIngestionSink: AcsStore.IngestionSink
  def domainIngestionSink: DomainStore.IngestionSink

  protected def txLogParser: TxLogStore.Parser[TXI, TXE]
}

/** A coin app store whose TxLog is always empty.
  */
trait CoinAppStoreWithoutHistory
    extends CoinAppStore[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]] {
  override protected def txLogParser = TxLogStore.Parser.Empty()
}

/** A coin app store that supports storing and retrieving historical data.
  * Note that retrieving historical data requires a connection to the ledger.
  */
trait CoinAppStoreWithHistory[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
] extends CoinAppStore[TXI, TXE] {
  protected def connection: CoinLedgerConnection

  protected lazy val txLogReader: TxLogStore.Reader[TXI, TXE] =
    new TxLogStore.Reader[TXI, TXE](
      txLog,
      transactionTreeSource = TxLogStore.TransactionTreeSource
        .LedgerConnection(acs.contractFilter.ingestionFilter.primaryParty, connection),
    )
}
