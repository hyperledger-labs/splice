package com.daml.network.wallet.store

import cats.Monoid
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.ledger.javaapi.data.*
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.history.{CoinArchive, CoinCreate, Tap, Transfer}
import com.daml.network.http.v0.definitions as httpDef
import com.daml.network.store.TxLogStore
import com.daml.network.util.{Contract, ExerciseNode, ExerciseNodeCompanion, Codec}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.time.{Instant, ZoneOffset}
import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal

class UserWalletTxLogParser(
    override val loggerFactory: NamedLoggerFactory,
    endUserParty: String,
) extends TxLogStore.Parser[
      UserWalletTxLogParser.TxLogIndexRecord,
      UserWalletTxLogParser.TxLogEntry,
    ]
    with NamedLogging {
  import UserWalletTxLogParser.*

  // TODO(#2844) Make this stack safe
  private def parseTree(tree: TransactionTree, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {
    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case Transfer(node) =>
            // Note: we do not parse the child events, as we can extract all information about the transfer from this node
            State.fromTransfer(tree, root, node)

          case Tap(node) =>
            // Note: we do not parse the child events, as we can extract all information about the balance change from this node
            State.fromTap(tree, root, node)

          case CoinArchive(_) =>
            // TODO (#2845) Fix all uses of this.
            logger.info(
              "Coin archive events are not included in the transaction history."
                + " Make sure to handle the parent event to avoid having to fetch the coin."
            )
            State.empty
          case _ =>
            parseTrees(tree, exercised.getChildEventIds.asScala.toList)
        }

      case created: CreatedEvent =>
        created match {
          // A coin create that is not a child of the above exercise events.
          // This can be for example a coin being unlocked when collecting an app payment.
          case CoinCreate(coin) =>
            State.fromCoinCreate(tree, root, coin)

          case _ =>
            State.empty
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
  ): Seq[TxLogEntry] = {
    parseTrees(tx, tx.getRootEventIds.asScala.toList)
      .filterByParty(endUserParty)
      .entries
  }
}

object UserWalletTxLogParser {
  // Note: the UI currently doesn't support any kind of filtering,
  // there's no need to add any data to the index.
  final case class TxLogIndexRecord(
      offset: String,
      eventId: String,
  ) extends TxLogStore.IndexRecord

  sealed trait TxLogEntry extends TxLogStore.Entry[TxLogIndexRecord] {
    def toJson: httpDef.ListTransactionsResponseItem
  }

  object TxLogEntry {

    /** Balance change due to a transfer */
    // TODO(M3-04) This needs to include more info, e.g., amount of collected rewards
    // and exchange rate at the time.
    final case class Transfer(
        indexRecord: TxLogIndexRecord,
        date: Instant,
        provider: String,
        sender: (String, BigDecimal),
        receivers: Seq[(String, BigDecimal)],
        senderHoldingFees: BigDecimal,
    ) extends TxLogEntry {
      override def toJson: httpDef.ListTransactionsResponseItem =
        httpDef.ListTransactionsResponseItem(
          transactionType = Transfer.transaction_type,
          eventId = indexRecord.eventId,
          offset = indexRecord.offset,
          date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
          provider = Some(provider),
          sender = Some(httpDef.PartyAndAmount(sender._1, Codec.encode(sender._2))),
          receivers =
            Some(receivers.map(r => httpDef.PartyAndAmount(r._1, Codec.encode(r._2))).toVector),
          holdingFees = Some(Codec.encode(senderHoldingFees)),
        )
    }
    object Transfer {
      val transaction_type = "transfer"
    }

    /** Balance change not due to a transfer, for example a tap or returning a locked coin to the owner. */
    // TODO(M3-04) Include more context info here to distinguish different balance changes.
    final case class BalanceChange(
        indexRecord: TxLogIndexRecord,
        date: Instant,
        receiver: String,
        amount: BigDecimal,
    ) extends TxLogEntry {
      override def toJson: httpDef.ListTransactionsResponseItem =
        httpDef.ListTransactionsResponseItem(
          transactionType = BalanceChange.transaction_type,
          eventId = indexRecord.eventId,
          offset = indexRecord.offset,
          date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
          provider = None,
          sender = None,
          receivers = Some(Vector(httpDef.PartyAndAmount(receiver, Codec.encode(amount)))),
          holdingFees = None,
        )
    }
    object BalanceChange {
      val transaction_type = "balance_change"
    }

    // Note: deserialization is only needed for the Canton console,
    // that's why this function doesn't do any proper error handling.
    def fromJson(item: httpDef.ListTransactionsResponseItem): Either[String, TxLogEntry] = {
      item.transactionType match {
        case BalanceChange.transaction_type =>
          val indexRecord = TxLogIndexRecord(
            offset = item.offset,
            eventId = item.eventId,
          )
          for {
            receiverAndAmount <- item.receivers.flatMap(_.headOption).toRight("No receivers")
          } yield TxLogEntry.BalanceChange(
            indexRecord = indexRecord,
            date = item.date.toInstant,
            receiver = receiverAndAmount.party,
            amount = Codec.tryDecode(Codec.BigDecimal)(receiverAndAmount.amount),
          )
        case Transfer.transaction_type =>
          val indexRecord = TxLogIndexRecord(
            offset = item.offset,
            eventId = item.eventId,
          )
          for {
            provider <- item.provider.toRight("Provider missing")
            sender <- item.sender.toRight("Sender missing")
            senderAmount <- Codec.decode(Codec.BigDecimal)(sender.amount)
            receivers <- item.receivers.toRight("Receivers missing")
            receivers <- receivers.traverse(r =>
              Codec.decode(Codec.BigDecimal)(r.amount).map(amount => r.party -> amount)
            )
            holdingFees <- item.holdingFees.toRight("Holding fees missing")
            senderHoldingFees <- Codec.decode(Codec.BigDecimal)(holdingFees)
          } yield TxLogEntry.Transfer(
            indexRecord = indexRecord,
            date = item.date.toInstant,
            provider = provider,
            sender = sender.party -> senderAmount,
            receivers = receivers,
            senderHoldingFees = senderHoldingFees,
          )
        case _ => Left(s"Unknown item $item")
      }
    }
  }

  /** Intermediate state for parsing TransactionTrees into TxLogStore entries.
    *
    * @param entries TxLogStore entries generated so far
    */
  case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )

    /** Removes all entries that are not relevant to the given user. */
    def filterByParty(party: String): State = State(
      entries = entries.filter {
        case t: TxLogEntry.Transfer => t.sender._1 == party || t.receivers.exists(_._1 == party)
        case b: TxLogEntry.BalanceChange => b.receiver == party
      }
    )
  }

  object State {
    def empty: State = State(
      entries = immutable.Queue.empty
    )
    implicit val stateMonoid: Monoid[State] = new Monoid[State] {
      override val empty = State.empty
      override def combine(a: State, b: State) =
        a.appended(b)
    }
    def fromTransfer(
        tx: TransactionTree,
        event: TreeEvent,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
    ): State = {
      val newEntry = TxLogEntry.Transfer(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        date = tx.getEffectiveAt,
        provider = node.argument.value.transfer.provider,
        sender = parseSender(node.argument.value, node.result.value),
        receivers = parseReceivers(node.argument.value, node.result.value),
        senderHoldingFees = node.result.value.summary.holdingFees,
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }
    def fromTap(
        tx: TransactionTree,
        event: TreeEvent,
        node: ExerciseNode[Tap.Arg, Tap.Res],
    ): State = {
      val newEntry = TxLogEntry.BalanceChange(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        date = tx.getEffectiveAt,
        amount = node.argument.value.amount,
        receiver = node.argument.value.receiver,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }
    def fromCoinCreate(
        tx: TransactionTree,
        event: TreeEvent,
        coin: CoinCreate.ContractType,
    ): State = {
      val newEntry = TxLogEntry.BalanceChange(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        date = tx.getEffectiveAt,
        amount = coin.payload.amount.initialAmount,
        receiver = coin.payload.owner,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }
  }

  // Helper for parsing exercise nodes into TxLogEntries
  def mkExerciseParse(companion: ExerciseNodeCompanion)(
      parse: (
          TransactionTree,
          ExercisedEvent,
          companion.Arg,
          companion.Res,
          State,
          Seq[TreeEvent],
          TraceContext,
      ) => State
  )(
      tree: TransactionTree,
      event: ExercisedEvent,
      parseState: State,
      path: Seq[TreeEvent],
      tc: TraceContext,
      ec: ErrorLoggingContext,
  ): State = {
    ExerciseNode
      .decodeExerciseEvent(companion)(event)(ec)
      .fold(parseState)(node =>
        parse(tree, event, node.argument.value, node.result.value, parseState, path, tc)
      )
  }

  // Helper for parsing create nodes into TxLogEntries
  def mkCreateParse[TCid <: ContractId[T], T <: Template](
      companion: Contract.Companion.Template[TCid, T]
  )(
      parse: (
          TransactionTree,
          CreatedEvent,
          T with DamlRecord[_],
          State,
          Seq[TreeEvent],
          TraceContext,
      ) => State
  )(
      tree: TransactionTree,
      event: CreatedEvent,
      parseState: State,
      path: Seq[TreeEvent],
      tc: TraceContext,
  ): State = {
    Contract
      .fromCreatedEvent(companion)(event)
      .fold(parseState)(contract => parse(tree, event, contract.payload, parseState, path, tc))
  }

  private def parseSender(
      arg: v1.coin.CoinRules_Transfer,
      res: v1.coin.TransferResult,
  ): (String, BigDecimal) = {
    val sender = arg.transfer.sender

    // Input coins and rewards, excluding holding fees
    val netInput = BigDecimal(res.summary.inputAppRewardAmount) + BigDecimal(
      res.summary.inputValidatorRewardAmount
    )
      + res.summary.inputCoinAmount - res.summary.holdingFees

    // Output coins going back to the sender, after deducting transfer fees
    // TODO(#2843) Add detailed tests for the correctness of these fee computations.
    // In particular, the subtraction of the sender fee does not seem to be right.
    val netOutput = parseOutputAmounts(arg, res)
      .filter(o => o.output.receiver == sender && o.output.lock.isEmpty)
      .map(o => o.output.amount - o.receiverFee - o.senderFee)
      .sum

    // Leftover change
    val netChange = BigDecimal(res.summary.senderChangeAmount)

    sender -> (netInput - netOutput - netChange)
  }

  /** Returns a list of receivers */
  private def parseReceivers(
      arg: v1.coin.CoinRules_Transfer,
      res: v1.coin.TransferResult,
  ): Seq[(String, BigDecimal)] = {
    parseOutputAmounts(arg, res)
      .filter(_.output.receiver != arg.transfer.sender)
      .map(o => o.output.receiver -> (o.output.amount - o.receiverFee))
  }

  /** A requested output of a transfer, together with the actual fees paid for the transfer.
    *
    * @param output Contains the receiver and the gross amount received (before deducting fees).
    * @param senderFee Actual amount of fees paid by the sender.
    * @param receiverFee Actual amount of fees paid by the receiver.
    */
  private final case class OutputWithFees(
      output: v1.coin.TransferOutput,
      senderFee: BigDecimal,
      receiverFee: BigDecimal,
  )

  private def parseOutputAmounts(
      arg: v1.coin.CoinRules_Transfer,
      res: v1.coin.TransferResult,
  ): Seq[OutputWithFees] = {
    assert(
      arg.transfer.outputs.size() == res.summary.outputFees.size(),
      "Each output should have a corresponding fee",
    )
    val outputsWithFees = arg.transfer.outputs.asScala.toSeq.zip(res.summary.outputFees.asScala)

    outputsWithFees
      .map { case (out, fee) =>
        OutputWithFees(
          output = out,
          // TODO(#2843) Add detailed tests for the correctness of these fee computations, in particular, that we don't
          // need to force a scale here.
          senderFee = BigDecimal(fee) * out.receiverFeeRatio,
          receiverFee = BigDecimal(fee) * (BigDecimal(1) - out.receiverFeeRatio),
        )
      }
  }

}
