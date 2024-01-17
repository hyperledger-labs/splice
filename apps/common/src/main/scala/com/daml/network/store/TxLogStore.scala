package com.daml.network.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{CreatedEvent, TransactionTreeV2}
import com.daml.network.environment.BaseLedgerConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import com.daml.network.environment.ledger.api.{ActiveContract, IncompleteReassignmentEvent}
import com.digitalasset.canton.topology.DomainId
import scala.collection.SeqView

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
}

object TxLogStore {
  def firstPage[TXI <: TxLogStore.IndexRecord](log: SeqView[TXI], limit: PageLimit)(
      filter: TXI => Boolean
  ) =
    log
      .filter(filter)
      .take(limit.limit)
      .toSeq

  def nextPage[TXI <: TxLogStore.IndexRecord](
      log: SeqView[TXI],
      pageEndEventId: String,
      limit: PageLimit,
  )(
      filter: TXI => Boolean
  ) =
    log
      .filter(txi => filter(txi))
      .dropWhile(_.eventId != pageEndEventId)
      .slice(1, 1 + limit.limit)
      .toSeq

  /** Stores all information about a historical event that is needed to display the event in a frontend.
    * Instances of Entry are not persisted.
    */
  trait Entry[+ALI] extends Product with Serializable {
    def indexRecord: ALI
  }

  object Entry {

    import com.digitalasset.canton.logging.pretty.Pretty

    implicit val entryPretty: Pretty[Entry[?]] =
      Pretty.adHocPrettyInstance
  }

  /** Stores information needed to efficiently query for historical events.
    * Instances of EntryIndexRecord are persisted, make sure they do not contain unnecessary data.
    */
  trait IndexRecord extends EntryEvent with Product with Serializable

  /** Identifies the transaction tree event that an tx log entry is associated with */
  trait EntryEvent {
    def optOffset: Option[String]

    /** Iff an entry comes from the ACS, it should have a contract id.
      * TODO (#6882): this should not be needed.
      */
    def acsContractId: Option[ContractId[?]]
    def eventId: String
    def domainId: DomainId
  }

  /** Extracts tx log entries from transaction tree events */
  trait Parser[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]] {

    /** Extract application-specific TxLog entries from the acs, before starting to ingest transactions */
    def parseAcs(
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
    )(implicit
        tc: TraceContext
    ): Seq[(DomainId, TXE)]

    /** Extract application-specific TxLog entries from the given daml transaction */
    def tryParse(tx: TransactionTreeV2, domain: DomainId)(implicit tc: TraceContext): Seq[TXE]

    /** Returns a TxLog entry to be stored in case this parser failed to parse the given daml transaction.
      * Must not throw an error.
      */
    def error(offset: String, eventId: String, domainId: DomainId): Option[TXE]

    final def parse(tx: TransactionTreeV2, domain: DomainId, logger: TracedLogger)(implicit
        tc: TraceContext
    ): Seq[TXE] =
      Try(tryParse(tx, domain))
        .recoverWith { case e: Throwable =>
          logger.error(s"Failed to parse transaction: ${e.getMessage}", e)
          val firstRootEventId = tx.getRootEventIds.asScala.headOption.getOrElse("")
          Try(error(tx.getOffset, firstRootEventId, domain).toList)
        }
        .fold(
          e => {
            logger.error(s"Failed to handle parsing error: ${e.getMessage}", e)
            Seq.empty
          },
          identity,
        )
  }

  object Parser {
    final case class Empty[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]]()
        extends Parser[TXI, TXE] {
      override def parseAcs(
          acs: Seq[ActiveContract],
          incompleteUnassign: Seq[IncompleteReassignmentEvent.Unassign],
          incompleteAssign: Seq[IncompleteReassignmentEvent.Assign],
      )(implicit
          tc: TraceContext
      ): Seq[(DomainId, TXE)] = Seq.empty
      override def tryParse(tx: TransactionTreeV2, domain: DomainId)(implicit
          tc: TraceContext
      ): Seq[TXE] = Seq.empty
      override def error(offset: String, eventId: String, domainId: DomainId): Option[TXE] = None
    }
  }

  // To reconstruct tx log entries, we need to load transaction trees from the ledger
  trait TransactionTreeSource {
    def getTransactionTreeByEventId(eventId: String): Future[TransactionTreeV2]
  }

  object TransactionTreeSource {
    case class LedgerConnection(party: PartyId, connection: BaseLedgerConnection)
        extends TransactionTreeSource {
      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTreeV2] =
        connection.tryGetTransactionTreeByEventId(parties = Seq(party), id = eventId)
    }

    case class ForTesting(initialTransactionTrees: Seq[TransactionTreeV2] = Seq.empty)
        extends TransactionTreeSource {
      @volatile
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      private var transactionTrees: Seq[TransactionTreeV2] = initialTransactionTrees

      def addTree(tree: TransactionTreeV2): Unit = blocking {
        synchronized {
          transactionTrees = tree +: transactionTrees
        }
      }

      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTreeV2] =
        transactionTrees
          .find(_.getEventsById.containsKey(eventId))
          .fold(
            Future.failed[TransactionTreeV2](
              new RuntimeException(s"No transaction with event id $eventId")
            )
          )(tx => Future.successful(tx))
    }

    case object Unused extends TransactionTreeSource {
      override def getTransactionTreeByEventId(eventId: String): Future[TransactionTreeV2] =
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

    def loadTxLogEntry(
        eventId: String,
        domainId: DomainId,
        acsContractId: Option[ContractId[?]],
        filterUnique: (Seq[TXE], String) => TXE = defaultFilterUnique,
    )(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[TXE] =
      // Load original transaction tree from the ledger
      // TODO(#2455) handle the case when the transaction has been pruned from the ledger
      transactionTreeSource
        .getTransactionTreeByEventId(eventId)
        .map(tx =>
          acsContractId match {
            case Some(contractId) =>
              loadTxLogEntryFromAcs(contractId, tx, domainId)
            case None =>
              loadTxLogEntryFromTree(eventId, tx, domainId, filterUnique)
          }
        )

    private def defaultFilterUnique(entries: Seq[TXE], eventId: String): TXE =
      entries.filter(_.indexRecord.eventId == eventId) match {
        case entry +: Seq() =>
          entry
        case Seq() =>
          throw new IllegalStateException(
            s"defaultFilterUnique did not return any entry for event $eventId. "
          )
        case x =>
          throw new IllegalStateException(
            s"defaultFilterUnique returned ${x.size} entries for event $eventId. "
          )
      }

    private def loadTxLogEntryFromTree(
        eventId: String,
        tx: TransactionTreeV2,
        domainId: DomainId,
        filterUnique: (Seq[TXE], String) => TXE,
    )(implicit
        tc: TraceContext
    ): TXE = {
      // Extract application-specific data from the tree
      val entries = txLogStore.txLogParser.parse(tx, domainId, logger)

      // Find original TxLog entry
      filterUnique(entries, eventId)
    }

    private def loadTxLogEntryFromAcs(
        contractId: ContractId[?],
        tx: TransactionTreeV2,
        domainId: DomainId,
    )(implicit
        tc: TraceContext
    ): TXE = {
      // Find original create event
      // TODO (#6882): remove this workaround once the ledger API returns correct EventIDs
      val createdEvent = tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == contractId.contractId =>
            c
        }
        .getOrElse(
          throw new IllegalStateException(
            s"Could not find contract ${contractId.contractId} in transaction from ACS."
          )
        )

      val activeContract = ActiveContract(
        domainId = domainId,
        createdEvent = createdEvent,
        reassignmentCounter = 0L,
      )

      // Extract application-specific data from a simulated ACS containing the above contract
      txLogStore.txLogParser
        .parseAcs(Seq(activeContract), Seq.empty, Seq.empty)
        .headOption
        .getOrElse(
          sys.error(
            s"Parser did not return any entry for active contract ${createdEvent.getContractId}."
          )
        )
        ._2
    }
  }

}
