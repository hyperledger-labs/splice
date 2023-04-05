package com.daml.network.store

import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId

import scala.concurrent.{Future, ExecutionContext}

/** Store setup shared by all of our apps
  */
trait CNNodeAppStore[
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

  // TODO (#3899) remove
  lazy val defaultAcs: Future[AcsStore] =
    domains.signalWhenConnected(defaultAcsDomain).flatMap(acs(_))

  // TODO (#3899) remove
  def acs(domain: DomainId): Future[AcsStore]

  def domains: DomainStore

  def multiDomainAcsStore: MultiDomainAcsStore
  def txLog: TxLogStore[TXI, TXE]

  /** Orchestrate store and an ingestion sink for a newly-discovered domain. */
  private[network] def installNewPerDomainStore(
      domain: DomainId,
      perDomainLoggerFactory: NamedLoggerFactory,
  ): PerDomainStore

  /** Undo [[#installNewStoreByDomain]]. */
  private[network] def uninstallPerDomainStore(domain: DomainId): Unit

  protected[this] def storeAcs(store: PerDomainStore): AcsStore

  /** Fetch the ingestion sink that feeds into the given stores. */
  private[network] def storesIngestionSink(store: PerDomainStore): AcsStore.IngestionSink

  def domainIngestionSink: DomainStore.IngestionSink

  protected def txLogParser: TxLogStore.Parser[TXI, TXE]
}

object CNNodeAppStore {
  import scala.concurrent.Promise

  /** Stores [[CNNodeAppStore#PerDomainStore]] in an in-memory mutable map. */
  trait InMemoryMutableStoreMap[
      TXI <: TxLogStore.IndexRecord,
      TXE <: TxLogStore.Entry[TXI],
  ] extends CNNodeAppStore[TXI, TXE] {
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

    override def acs(domain: DomainId) = fetchState(domain).future map storeAcs
  }

  trait RemovedAcs[
      TXI <: TxLogStore.IndexRecord,
      TXE <: TxLogStore.Entry[TXI],
  ] extends CNNodeAppStore[TXI, TXE] {
    protected[this] def removedAcsAppName: String

    @deprecated("acs will always fail; use `multiDomainAcsStore` instead", since = "2023-03-31")
    override final def acs(domain: DomainId): Future[AcsStore] =
      Future.failed(
        new RuntimeException(
          s"$removedAcsAppName has been migrated to new ACS store, use `multiDomainAcsStore` instead"
        )
      )

    @deprecated(
      "defaultAcs will always fail; use `multiDomainAcsStore` instead",
      since = "2023-03-31",
    )
    override final lazy val defaultAcs =
      domains.signalWhenConnected(defaultAcsDomain).flatMap(acs(_))
  }

  type RemovedAcsWithoutHistory =
    RemovedAcs[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]]
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
