package com.daml.network.store

import com.daml.ledger.javaapi.data.TransactionTreeV2
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext

import scala.jdk.CollectionConverters.*
import scala.util.Try
import com.daml.network.environment.ledger.api.{ActiveContract, IncompleteReassignmentEvent}
import com.daml.network.store.db.TxLogRowData
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.DomainId

import scala.collection.SeqView
import scala.reflect.ClassTag

/** Stores historical information that can be used to construct application-specific historical events,
  * such as a user notification or an item from a bank statement.
  *
  * Principles:
  * - The store for tx log entries and the store for the ACS are updated from the same daml transaction stream.
  *   The updates are atomical, i.e., both stores always reflect the state of the ledger at the same offset.
  *   To simplify keeping the audit log and the ACS store in sync, the tx log store is integrated into the ACS store.
  * - If an application needs to display multiple kinds of historical events, then the set of tx log entries
  *   should represent the union of all historical events.
  * - Each transaction tree can produce any number of tx log entries.
  */
object TxLogStoreNew {
  def firstPage[TXE, TXER <: TXE](log: SeqView[TXE], limit: PageLimit)(implicit
      tag: ClassTag[TXER]
  ): Seq[TXER] =
    log
      .collect { case txi: TXER =>
        txi
      }
      .take(limit.limit)
      .toSeq

  def nextPage[TXE, TXER <: TXE](
      log: SeqView[TXE],
      pageEnd: String,
      limit: PageLimit,
  )(
      project: TXER => String
  )(implicit tag: ClassTag[TXER]): Seq[TXER] =
    log
      .collect { case txi: TXER =>
        txi
      }
      .dropWhile(e => project(e) != pageEnd)
      .slice(1, 1 + limit.limit)
      .toSeq

  // TODO(#8943): Remove this type, it's not needed once we remove the inheritance from Product
  /** Stores all information about a historical event */
  trait Entry extends Product with Serializable {}

  object Entry {
    import com.digitalasset.canton.logging.pretty.Pretty

    implicit val txLogPretty: Pretty[Entry] =
      Pretty.adHocPrettyInstance
  }

  /** Extracts tx log entries from transaction tree events */
  trait Parser[+TXE] {

    /** Extract application-specific TxLog entries from the acs, before starting to ingest transactions */
    def parseAcs(
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
    )(implicit
        tc: TraceContext
    ): Seq[(DomainId, Option[ContractId[?]], TXE)]

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
    lazy val empty: Parser[Nothing] = new Parser[Nothing] {
      override def parseAcs(
          acs: Seq[ActiveContract],
          incompleteUnassign: Seq[IncompleteReassignmentEvent.Unassign],
          incompleteAssign: Seq[IncompleteReassignmentEvent.Assign],
      )(implicit
          tc: TraceContext
      ) = Seq.empty
      override def tryParse(tx: TransactionTreeV2, domain: DomainId)(implicit
          tc: TraceContext
      ) = Seq.empty
      override def error(offset: String, eventId: String, domainId: DomainId) = None
    }
  }

  trait Config[TXE] {

    /** Extracts entries from transaction trees */
    def parser: Parser[TXE]

    /** Defines index columns */
    def entryToRow: TXE => TxLogRowData

    /** Encodes the entry payload to a JSON object */
    def encodeEntry: TXE => (String3, spray.json.JsValue)

    /** Decodes the entry payload from a JSON object */
    def decodeEntry: (String3, spray.json.JsValue) => TXE
  }

  object Config {
    def empty[TXE]: Config[TXE] = new Config[TXE] {
      override def parser = Parser.empty
      override def entryToRow = throw new RuntimeException(
        "This app does not serialize any TxLog entries"
      )
      override def encodeEntry = throw new RuntimeException(
        "This app does not serialize any TxLog entries"
      )
      override def decodeEntry = throw new RuntimeException(
        "This app does not serialize any TxLog entries"
      )
    }
  }
}
