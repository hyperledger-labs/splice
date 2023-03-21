package com.daml.network.store

import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId

import scala.concurrent.{Future, ExecutionContext}

/** Store setup shared by all of our apps
  */
trait CoinAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
] extends NamedLogging
    with AutoCloseable {

  implicit protected def ec: ExecutionContext

  /** Defines which create events are to be ingested into the store. */
  protected def acsContractFilter: AcsStore.ContractFilter

  /** A value containing an [[AcsStore]] and [[TxLogStore]], created by
    * [[#freshStoresByDomain]].
    */
  private[network] type PerDomainStore

  def defaultAcsDomain: DomainAlias

  lazy val defaultAcs: Future[AcsStore] =
    domains.signalWhenConnected(defaultAcsDomain).flatMap(acs(_))

  def acs(domain: DomainId): Future[AcsStore]

  def txLog(domain: DomainId): Future[TxLogStore[TXI, TXE]]

  def transferStore: TransferStore

  def domains: DomainStore

  def multiDomainAcsStore: MultiDomainAcsStore

  /** Orchestrate store and an ingestion sink for a newly-discovered domain. */
  private[network] def installNewPerDomainStore(
      domain: DomainId,
      perDomainLoggerFactory: NamedLoggerFactory,
  ): PerDomainStore

  /** Undo [[#installNewStoreByDomain]]. */
  private[network] def uninstallPerDomainStore(domain: DomainId): Unit

  protected[this] def storeAcs(store: PerDomainStore): AcsStore
  protected[this] def storeTxLog(store: PerDomainStore): TxLogStore[TXI, TXE]

  /** Fetch the ingestion sink that feeds into the given stores. */
  private[network] def storesIngestionSink(store: PerDomainStore): AcsStore.IngestionSink

  def domainIngestionSink: DomainStore.IngestionSink

  protected def txLogParser: TxLogStore.Parser[TXI, TXE]
}

object CoinAppStore {
  import scala.concurrent.Promise

  /** Stores [[CoinAppStore#PerDomainStore]] in an in-memory mutable map. */
  trait InMemoryMutableStoreMap[
      TXI <: TxLogStore.IndexRecord,
      TXE <: TxLogStore.Entry[TXI],
  ] extends CoinAppStore[TXI, TXE] {
    import java.util.concurrent as juc

    private[this] val state: juc.ConcurrentMap[DomainId, Promise[PerDomainStore]] =
      new juc.ConcurrentHashMap

    private[this] def fetchState(domain: DomainId) = state.computeIfAbsent(domain, _ => Promise())

    private[network] override final def installNewPerDomainStore(
        domain: DomainId,
        perDomainLoggerFactory: NamedLoggerFactory,
    ): PerDomainStore = {
      val store = newPerDomainStore(domain, perDomainLoggerFactory)
      fetchState(domain).success(store): Unit
      store
    }

    private[network] override final def uninstallPerDomainStore(domain: DomainId) =
      state.remove(domain): Unit

    /** Reentrantly create stores and an ingestion sink for a newly-discovered domain. */
    protected[this] def newPerDomainStore(
        domain: DomainId,
        perDomainLoggerFactory: NamedLoggerFactory,
    ): PerDomainStore

    override final def acs(domain: DomainId) = fetchState(domain).future map storeAcs

    override final def txLog(domain: DomainId) = fetchState(domain).future map storeTxLog
  }
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

  protected final def txLogReader(domain: DomainId): Future[TxLogStore.Reader[TXI, TXE]] = for {
    txLog <- txLog(domain)
  } yield new TxLogStore.Reader[TXI, TXE](
    txLog,
    transactionTreeSource = TxLogStore.TransactionTreeSource
      .LedgerConnection(acsContractFilter.ingestionFilter.primaryParty, connection),
  )

  protected final lazy val defaultTxLogReader: Future[TxLogStore.Reader[TXI, TXE]] =
    domains.signalWhenConnected(defaultAcsDomain).flatMap(txLogReader(_))

  /** Provides access to the tx log directly, not through the reader.
    *  This should be used when all necessary data is readily available in the
    *  tx Index Records, thus no re-reading from the ledger is required.
    */
  protected final lazy val defaultTxLog: Future[TxLogStore[TXI, TXE]] =
    domains.signalWhenConnected(defaultAcsDomain).flatMap(txLog(_))
}
