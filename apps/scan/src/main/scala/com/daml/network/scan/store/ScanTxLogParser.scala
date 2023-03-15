package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.*
import com.daml.network.store.TxLogStore
import com.daml.network.history.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.util.CoinUtil.dollarsToCC

import cats.Monoid
import cats.syntax.foldable.*
import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import com.daml.ledger.javaapi.data.TreeEvent

class ScanTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      ScanTxLogParser.TxLogIndexRecord,
      ScanTxLogParser.TxLogEntry,
    ]
    with NamedLogging {

  import ScanTxLogParser.*

  private def parseTree(tree: TransactionTree, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {
    root match {
      case exercised: ExercisedEvent =>
        parseTrees(tree, exercised.getChildEventIds.asScala.toList)

      case created: CreatedEvent =>
        created match {
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(tree, root, round)
          case _ => State.empty
        }

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }

  private def parseTrees(tree: TransactionTree, rootsEventIds: List[String])(implicit
      tc: TraceContext
  ): State = {
    val roots = rootsEventIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, _))
  }

  override def parse(tx: TransactionTree)(implicit
      tc: TraceContext
  ): Seq[ScanTxLogParser.TxLogEntry] = {
    val ret = parseTrees(tx, tx.getRootEventIds.asScala.toList).entries
    logger.debug(s"Extracted log entries: ${ret}")
    ret
  }

}

object ScanTxLogParser {

  abstract class TxLogIndexRecord(
      // TODO(#2930) Will be used fairly soon, but for now these throw an unused warning so commented out:

      // offset: String,
      // eventId: String,
      // round: Long,
  ) extends TxLogStore.IndexRecord {}

  object TxLogIndexRecord {
    final case class OpenMiningRoundIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
    ) extends TxLogIndexRecord( /*offset, eventId, round*/ ) {}
    object OpenMiningRound {
      val transaction_type = "open_mining_round"
    }
  }

  sealed trait TxLogEntry extends TxLogStore.Entry[TxLogIndexRecord] {}

  object TxLogEntry {

    final case class EmptyTxLogEntry(indexRecord: TxLogIndexRecord)
        extends TxLogStore.Entry[TxLogIndexRecord] {}

    final case class OpenMiningRoundLogEntry(
        indexRecord: TxLogIndexRecord,
        coinCreateFee: BigDecimal,
        holdingFee: BigDecimal,
        lockHolderFee: BigDecimal,
        initialTransferFee: BigDecimal,
        transferFeeSteps: Seq[(BigDecimal, BigDecimal)],
    ) extends TxLogEntry {}

    object OpenMiningRoundLogEntry {
      val transaction_type = "open_mining_round"
    }
  }

  case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )
  }

  object State {
    def empty: State = State(
      entries = immutable.Queue.empty
    )

    implicit val stateMonoid: Monoid[State] = new Monoid[State] {
      override val empty = State(immutable.Queue.empty)
      override def combine(a: State, b: State) =
        a.appended(b)
    }

    def fromOpenMiningRoundCreate(
        tx: TransactionTree,
        event: TreeEvent,
        round: OpenMiningRoundCreate.ContractType,
    ): State = {
      val config = round.payload.transferConfigUsd
      val coinPrice = round.payload.coinPrice
      val newEntry = TxLogEntry.OpenMiningRoundLogEntry(
        indexRecord = TxLogIndexRecord.OpenMiningRoundIndexRecord(
          offset = tx.getOffset(),
          eventId = event.getEventId(),
          round = round.payload.round.number,
        ),
        coinCreateFee = dollarsToCC(config.createFee.fee, coinPrice),
        holdingFee = dollarsToCC(config.holdingFee.rate, coinPrice),
        lockHolderFee = dollarsToCC(config.lockHolderFee.fee, coinPrice),
        initialTransferFee = config.transferFee.initialRate,
        transferFeeSteps = config.transferFee.steps.asScala.toSeq.map(step =>
          (dollarsToCC(step._1, coinPrice), step._2)
        ),
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }
  }
}
