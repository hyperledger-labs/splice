package com.daml.network.store

import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.util.Try

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

  /** Gets the latest entry that satisfies a given query.
    * Throws [[Status.NOT_FOUND]] if no such entry exists.
    */
  def getLatestTxLogIndex(query: (TXI) => Boolean = (_: TXI) => true)(implicit
      ec: ExecutionContext
  ): Future[TXI]

  /** List all events that satisfy a given filter. Currently assumes the filter is selective enough for
    * the returned list to not be too long, i.e. does not support limiting the size of the response or pagination.
    * Note: This is a placeholder for fast iteration, that will probably need to be replaced with a more scalable API.
    */
  def getTxLogIndicesByFilter(filter: TXI => Boolean)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]]

  def findLatestTxLogIndex[A, Z](init: Z)(p: (Z, TXI) => Either[A, Z])(implicit
      ec: ExecutionContext
  ): Future[A]
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

    /** Extract application-specific TxLog entries from the given daml transaction */
    def tryParse(tx: TransactionTree)(implicit tc: TraceContext): Seq[TXE]

    /** Returns a TxLog entry to be stored in case this parser failed to parse the given daml transaction.
      * Must not throw an error.
      */
    def error(offset: String, eventId: String): Option[TXE]

    final def parse(tx: TransactionTree, logger: TracedLogger)(implicit
        tc: TraceContext
    ): Seq[TXE] =
      Try(tryParse(tx))
        .recoverWith { case e: Throwable =>
          logger.error(s"Failed to parse transaction: ${e.getMessage}", e)
          val firstRootEventId = tx.getRootEventIds.asScala.headOption.getOrElse("")
          Try(error(tx.getOffset, firstRootEventId).toList)
        }
        .fold(
          e => {
            logger.error(s"Failed to handle parsing error: ${e.getMessage}", e)
            Seq.empty
          },
          entries => entries,
        )
  }

  object Parser {
    final case class Empty[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]]()
        extends Parser[TXI, TXE] {
      override def tryParse(tx: TransactionTree)(implicit tc: TraceContext): Seq[TXE] = Seq.empty
      override def error(offset: String, eventId: String): Option[TXE] = None
    }
  }

  // To reconstruct tx log entries, we need to load transaction trees from the ledger
  trait TransactionTreeSource {
    def getTransactionTreeByEventId(eventId: String): Future[TransactionTree]
  }

  object TransactionTreeSource {
    case class LedgerConnection(party: PartyId, connection: CNLedgerConnection)
        extends TransactionTreeSource {
      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTree] =
        connection.tryGetTransactionTreeByEventId(parties = Seq(party), id = eventId)
    }

    case class ForTesting(initialTransactionTrees: Seq[TransactionTree] = Seq.empty)
        extends TransactionTreeSource {
      @volatile
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      private var transactionTrees: Seq[TransactionTree] = initialTransactionTrees

      def addTree(tree: TransactionTree): Unit = blocking {
        synchronized {
          transactionTrees = tree +: transactionTrees
        }
      }

      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTree] =
        transactionTrees
          .find(_.getEventsById.containsKey(eventId))
          .fold(
            Future.failed[TransactionTree](
              new RuntimeException(s"No transaction with event id $eventId")
            )
          )(tx => Future.successful(tx))
    }

    case object Unused extends TransactionTreeSource {
      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTree] =
        Future.failed(
          new RuntimeException(
            "This class should only be used for tests where you never read TxLog entries"
          )
        )
    }
  }

  /** Reads full TxLog entries from a TxLogStore.
    * Since the TxLogStore only stores an index of entries, reading full entries
    * involves loading transactions from the ledger and reconstructing entries using the parser.
    */
  class Reader[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      txLogStore: TxLogStore[TXI, TXE],
      transactionTreeSource: TransactionTreeSource,
      override val loggerFactory: NamedLoggerFactory,
  ) extends NamedLogging {

    def getLatestTxLogEntry(
        query: (TXI) => Boolean = (_: TXI) => true
    )(implicit ec: ExecutionContext, tc: TraceContext): Future[TXE] = for {
      index <- txLogStore.getLatestTxLogIndex(query)
      entry <- loadTxLogEntry(index.eventId)
    } yield entry

    def loadTxLogEntry(eventId: String)(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[TXE] = for {
      // Load original transaction tree from the ledger
      // TODO(#2455) handle the case when the transaction has been pruned from the ledger
      tx <- transactionTreeSource.getTransactionTreeByEventId(eventId)

      // Extract application-specific data from the tree
      entries = txLogStore.txLogParser.parse(tx, logger)

      // Find original TxLog entry
      entry = entries
        .find(_.indexRecord.eventId == eventId)
        .getOrElse(
          sys.error(
            s"Parser did not return any entry for event ${eventId}. "
              + "The parser did return an entry for the same event previously. "
              + "Either the parser doesn't always return the same entry for a given input tree event, "
              + "or the transaction tree was loaded using different reader parties than those used during ingestion."
          )
        )
    } yield entry
  }
}
