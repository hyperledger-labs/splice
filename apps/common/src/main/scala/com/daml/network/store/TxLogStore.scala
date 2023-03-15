package com.daml.network.store

import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Stores historical information that can be used to construct application-specific historical events,
  * such as a user notification or an item from a bank statement.
  *
  * Principles:
  * - The store for tx log entries and the store for the ACS are updated from the same daml transaction stream.
  *   The updates are atomical, i.e., both stores always reflect the state of the ledger at the same offset.
  *   To simplify keeping the audit log and the ACS store in sync, the tx log store is integrated into the ACS store.
  * - If an application needs to display multiple kinds of historical events, then the set of tx log entries
  *   should represent the union of all historical events.
  * - Each transaction tree event can produce zero or one tx log entries
  * - Each tx log entry is associated with one transaction tree event
  * - An tx log entry should only store minimal information, just enough to support all query operations
  *   (e.g., filter/sort/aggregate) required by the application. Additional information should be loaded by
  *   the tx log store on demand from the associated transaction tree event.
  */
trait TxLogStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]] {
  import TxLogStore.*

  def txLogParser: Parser[TXI, TXE]

  def getTxLogIndicesByOffset(offset: Int, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]]

  /** Gets an entry that satisfies a given query.
    * Throws [[Status.NOT_FOUND]] if no such entry exists.
    */
  def getTxLogIndex(query: (TXI) => Boolean = (_: TXI) => true)(implicit
      ec: ExecutionContext
  ): Future[TXI]

  /** List all events that come after the given event id up to the set limit.
    * Excludes the event with the given id.
    */
  def getTxLogIndicesAfterEventId(beginAfterEventId: String, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]]
}

object TxLogStore {

  /** Stores all information about a historical event that is needed to display the event in a frontend.
    * Instances of Entry are not persisted.
    */
  trait Entry[+ALI] extends Product with Serializable {
    def indexRecord: ALI
  }

  /** Stores information needed to efficiently query for historical events.
    * Instances of EntryIndexRecord are persisted, make sure they do not contain unnecessary data.
    */
  trait IndexRecord extends EntryEvent with Product with Serializable

  /** Identifies the transaction tree event that an tx log entry is associated with */
  trait EntryEvent {
    def offset: String
    def eventId: String
  }

  /** Extracts tx log entries from transaction tree events */
  trait Parser[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]] {
    def parse(tx: TransactionTree)(implicit tc: TraceContext): Seq[TXE]
  }

  object Parser {
    final case class Empty[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]]()
        extends Parser[TXI, TXE] {
      override def parse(tx: TransactionTree)(implicit tc: TraceContext): Seq[TXE] = Seq.empty
    }
  }

  // To reconstruct tx log entries, we need to load transaction trees from the ledger
  trait TransactionTreeSource {
    def getTransactionTreeByEventId(eventId: String): Future[TransactionTree]
  }

  object TransactionTreeSource {
    case class LedgerConnection(party: PartyId, connection: CoinLedgerConnection)
        extends TransactionTreeSource {
      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTree] =
        connection.tryGetTransactionTreeByEventId(parties = Seq(party), id = eventId)
    }
    case class StaticForTesting(allTransactionTrees: Seq[TransactionTree])
        extends TransactionTreeSource {
      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTree] =
        allTransactionTrees
          .find(_.getEventsById.containsKey(eventId))
          .fold(
            Future.failed[TransactionTree](
              new RuntimeException(s"No transaction with event id $eventId")
            )
          )(tx => Future.successful(tx))
    }
  }

  /** Reads full TxLog entries from a TxLogStore.
    * Since the TxLogStore only stores an index of entries, reading full entries
    * involves loading transactions from the ledger and reconstructing entries using the parser.
    */
  class Reader[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      txLogStore: TxLogStore[TXI, TXE],
      transactionTreeSource: TransactionTreeSource,
  ) {

    def getTxLogByOffset(offset: Int, limit: Int)(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[Seq[TXE]] = for {
      indices <- txLogStore.getTxLogIndicesByOffset(offset, limit)
      entries <- Future.traverse(indices)(i => loadTxLogEntry(i))
    } yield entries

    def getTxLogEntry(
        query: (TXI) => Boolean = (_: TXI) => true
    )(implicit ec: ExecutionContext, tc: TraceContext): Future[TXE] = for {
      index <- txLogStore.getTxLogIndex(query)
      entry <- loadTxLogEntry(index)
    } yield entry

    def getTxLogAfterEventId(beginAfterEventId: String, limit: Int)(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[Seq[TXE]] = for {
      indices <- txLogStore.getTxLogIndicesAfterEventId(beginAfterEventId, limit)
      entries <- Future.traverse(indices)(i => loadTxLogEntry(i))
    } yield entries

    private def loadTxLogEntry(entryIndex: TXI)(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[TXE] = for {
      // Load original transaction tree from the ledger
      // TODO(#2455) handle the case when the transaction has been pruned from the ledger
      tx <- transactionTreeSource.getTransactionTreeByEventId(entryIndex.eventId)

      // Extract application-specific data from the tree
      entries = txLogStore.txLogParser.parse(tx)

      // Find original TxLog entry
      entry = entries
        .find(_.indexRecord.eventId == entryIndex.eventId)
        .getOrElse(
          sys.error(
            s"Parser did not return any entry for event ${entryIndex.eventId}. "
              + "The parser did return an entry for the same event previously. "
              + "Either the parser doesn't always return the same entry for a given input tree event, "
              + "or the transaction tree was loaded using different reader parties than those used during ingestion."
          )
        )
    } yield entry
  }
}
