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
import com.daml.network.util.ExerciseNode
import com.digitalasset.canton.topology.PartyId
import com.daml.network.util.Codec
import io.grpc.Status

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
    // TODO(#2930) add more checks on the nodes, at least that the svc party is correct
    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case Transfer(node) =>
            State.fromTransfer(tree, root, node)
          case _ => parseTrees(tree, exercised.getChildEventIds.asScala.toList)
        }

      case created: CreatedEvent =>
        created match {
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(tree, root, round)
          case ClosedMiningRoundCreate(round) => {
            State.fromClosedMiningRoundCreate(tree, root, round)
          }
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

  trait TxLogIndexRecord extends TxLogStore.IndexRecord { def round: Long }

  object TxLogIndexRecord {
    final case class OpenMiningRoundIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
    ) extends TxLogIndexRecord

    final case class ClosedMiningRoundIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
    ) extends TxLogIndexRecord

    trait RewardIndexRecord extends TxLogIndexRecord {
      def party: PartyId
      def amount: BigDecimal
    }

    final case class AppRewardIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
        party: PartyId,
        amount: BigDecimal,
    ) extends RewardIndexRecord

    final case class ValidatorRewardIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
        party: PartyId,
        amount: BigDecimal,
    ) extends RewardIndexRecord
  }

  sealed trait TxLogEntry extends TxLogStore.Entry[TxLogIndexRecord] {}

  object TxLogEntry {

    final case class EmptyTxLogEntry(indexRecord: TxLogIndexRecord) extends TxLogEntry {}

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

    def fromTransfer(
        tx: TransactionTree,
        event: TreeEvent,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
    ): State = {
      val appRewards = node.result.value.summary.inputAppRewardAmount
      val validatorRewards = node.result.value.summary.inputValidatorRewardAmount
      val party = Codec
        .decode(Codec.Party)(node.argument.value.transfer.sender)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"Cannot decode party ID ${node.argument.value.transfer.sender}")
            .asRuntimeException()
        )
      val round = node.result.value.round

      val appRewardEntry =
        if (appRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            TxLogEntry.EmptyTxLogEntry(
              indexRecord = TxLogIndexRecord.AppRewardIndexRecord(
                offset = tx.getOffset(),
                eventId = event.getEventId(),
                round = round.number,
                party = party,
                amount = appRewards,
              )
            )
          State(immutable.Queue(entry))
        } else {
          State.empty
        }

      val validatorRewardEntry =
        if (validatorRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            TxLogEntry.EmptyTxLogEntry(
              indexRecord = TxLogIndexRecord.ValidatorRewardIndexRecord(
                offset = tx.getOffset(),
                eventId = event.getEventId(),
                round = round.number,
                party = party,
                amount = validatorRewards,
              )
            )
          State(immutable.Queue(entry))
        } else {
          State.empty
        }

      State.empty.appended(appRewardEntry).appended(validatorRewardEntry)
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

    def fromClosedMiningRoundCreate(
        tx: TransactionTree,
        event: TreeEvent,
        round: ClosedMiningRoundCreate.ContractType,
    ): State = {
      val newEntry = TxLogEntry.EmptyTxLogEntry(
        indexRecord = TxLogIndexRecord.ClosedMiningRoundIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId(),
          round = round.payload.round.number,
        )
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }
  }
}
