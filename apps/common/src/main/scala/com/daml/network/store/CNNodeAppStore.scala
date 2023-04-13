package com.daml.network.store

import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLogging

import scala.concurrent.ExecutionContext

/** Store setup shared by all of our apps
  */
trait CNNodeAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
] extends NamedLogging
    with AutoCloseable {

  implicit protected def ec: ExecutionContext

  /** Defines which create events are to be ingested into the store. */
  protected def acsContractFilter: MultiDomainAcsStore.ContractFilter

  def defaultAcsDomain: DomainAlias

  def domains: DomainStore

  def multiDomainAcsStore: MultiDomainAcsStore
  def txLog: TxLogStore[TXI, TXE]

  def offset: OffsetStore

  protected def txLogParser: TxLogStore.Parser[TXI, TXE]
}

/** A coin app store whose TxLog is always empty.
  */
trait CNNodeAppStoreWithoutHistory
    extends CNNodeAppStore[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]] {
  override protected def txLogParser = TxLogStore.Parser.Empty()
}

/** A coin app store that supports storing and retrieving historical data.
  * Note that retrieving historical data requires a connection to the ledger.
  */
trait CNNodeAppStoreWithHistory[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
] extends CNNodeAppStore[TXI, TXE] {
  protected def connection: CNLedgerConnection

  protected final val txLogReader: TxLogStore.Reader[TXI, TXE] =
    new TxLogStore.Reader[TXI, TXE](
      txLog,
      transactionTreeSource = TxLogStore.TransactionTreeSource
        .LedgerConnection(acsContractFilter.ingestionFilter.primaryParty, connection),
    )
}
