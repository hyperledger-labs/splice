package com.daml.network.store

import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLogging
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

  // TODO (#2619) remove if not used anymore
  def defaultAcsDomain: DomainAlias

  // TODO (#2619) remove if unused after fixing `acs` users
  final lazy val defaultAcs: Future[AcsStore] =
    domains.signalWhenConnected(defaultAcsDomain).flatMap(acs(_))

  // TODO (#2619) remove, and remove futureStore and FutureAcsStore
  final val acs: AcsStore = AcsStore futureStore defaultAcs
  // TODO (#2620) remove in favor of the multi-domain-compatible overload
  def txLog: TxLogStore[TXI, TXE]

  def acs(domain: DomainId): Future[AcsStore]

  def txLog(domain: DomainId): Future[TxLogStore[TXI, TXE]]

  def domains: DomainStore

  /** Orchestrate store and an ingestion sink for a newly-discovered domain. */
  private[network] def installNewPerDomainStore(domain: DomainId): PerDomainStore

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
        domain: DomainId
    ): PerDomainStore = {
      val store = newPerDomainStore(domain)
      fetchState(domain).success(store): Unit
      store
    }

    private[network] override final def uninstallPerDomainStore(domain: DomainId) =
      state.remove(domain): Unit

    /** Reentrantly create stores and an ingestion sink for a newly-discovered domain. */
    protected[this] def newPerDomainStore(domain: DomainId): PerDomainStore

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

  protected lazy val txLogReader: TxLogStore.Reader[TXI, TXE] =
    new TxLogStore.Reader[TXI, TXE](
      txLog,
      transactionTreeSource = TxLogStore.TransactionTreeSource
        .LedgerConnection(acsContractFilter.ingestionFilter.primaryParty, connection),
    )
}
