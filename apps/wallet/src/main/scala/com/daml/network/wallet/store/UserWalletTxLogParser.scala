package com.daml.network.wallet.store

import cats.Monoid
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.ledger.javaapi.data.*
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.MintSummary
import com.daml.network.history.{
  CoinArchive,
  CoinCreate,
  LockedCoinOwnerExpireLock,
  LockedCoinUnlock,
  Mint,
  Tap,
  Transfer,
}
import com.daml.network.http.v0.definitions as httpDef
import com.daml.network.store.TxLogStore
import com.daml.network.util.{Codec, Contract, ExerciseNode, ExerciseNodeCompanion}
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
          // ------------------------------------------------------------------
          // Transferring coins
          // ------------------------------------------------------------------

          // Collecting app payments = unlocking a locked coin + transferring the coin to the provider
          case AcceptedAppPayment_Collect(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).mergeBalanceChangesIntoTransfer()

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionInitialPayment_Collect(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).mergeBalanceChangesIntoTransfer()

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionPayment_Collect(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).mergeBalanceChangesIntoTransfer()

          case Transfer(node) =>
            // Note: we do not parse the child events, as we can extract all information about the transfer from this node
            State.fromTransfer(tree, root, node)

          // ------------------------------------------------------------------
          // Minting new coins
          // ------------------------------------------------------------------

          case Tap(node) =>
            State.fromMintSummary(tree, root, node.result.value)

          case SvcRules_CollectSvReward(node) =>
            State.fromMintSummary(tree, root, node.result.value)

          case Mint(node) =>
            State.fromMintSummary(tree, root, node.result.value)

          // ------------------------------------------------------------------
          // Unlocking locked coins
          // ------------------------------------------------------------------

          case LockedCoinUnlock(node) =>
            State.fromMintSummary(tree, root, node.result.value)

          case LockedCoinOwnerExpireLock(node) =>
            State.fromMintSummary(tree, root, node.result.value)

          // ------------------------------------------------------------------
          // Other
          // ------------------------------------------------------------------

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
          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that produce new coins.
          case CoinCreate(coin) =>
            // Using an error to force our tests to fail should we ever introduce a new coin workflow that is not handled above.
            logger.error(
              s"Unexpected coin create event for coin ${coin.contractId.contractId} in transaction ${tree.getTransactionId}"
            )
            State.empty

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
        coinPrice: BigDecimal,
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
          coinPrice = Some(Codec.encode(coinPrice)),
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
        coinPrice: BigDecimal,
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
          coinPrice = Some(Codec.encode(coinPrice)),
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
            coinPriceStr <- item.coinPrice.toRight("Coin price missing")
            coinPrice <- Codec.decode(Codec.BigDecimal)(coinPriceStr)
          } yield TxLogEntry.BalanceChange(
            indexRecord = indexRecord,
            date = item.date.toInstant,
            receiver = receiverAndAmount.party,
            amount = Codec.tryDecode(Codec.BigDecimal)(receiverAndAmount.amount),
            coinPrice = coinPrice,
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
            coinPriceStr <- item.coinPrice.toRight("Coin price missing")
            coinPrice <- Codec.decode(Codec.BigDecimal)(coinPriceStr)
          } yield TxLogEntry.Transfer(
            indexRecord = indexRecord,
            date = item.date.toInstant,
            provider = provider,
            sender = sender.party -> senderAmount,
            receivers = receivers,
            senderHoldingFees = senderHoldingFees,
            coinPrice = coinPrice,
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

    /** Given a parsing state where the parser has encountered exactly one transfer and zero or more balance changes,
      * returns a parsing state where all the balance changes have been merged into the transfer event.
      *
      * This is useful for app payments where the payment collection first unlocks locked coins and immediately uses
      * them for a transfer. In this case, we only want to display one balance change for the user.
      */
    def mergeBalanceChangesIntoTransfer(): State = {
      val balanceChanges = entries.foldLeft(Map[String, BigDecimal]())((changes, entry) =>
        entry match {
          case b: TxLogEntry.BalanceChange =>
            changes.updatedWith(b.receiver)(amount => Some(amount.fold(b.amount)(_ + b.amount)))
          case _ => changes
        }
      )
      def netAmount(party: String, amount: BigDecimal) =
        party -> (amount + balanceChanges
          .getOrElse(party, BigDecimal(0)))

      // The code below works only if there is exactly one transfer.
      // Otherwise the balance changes are lost or duplicated by adding them to multiple transfers.
      assert(entries.collect { case t: TxLogEntry.Transfer => t }.length == 1)

      val newEntries = entries.flatMap {
        case t: TxLogEntry.Transfer =>
          Some(
            t.copy(
              sender = netAmount(t.sender._1, t.sender._2),
              receivers = t.receivers.map { case (receiver, amount) =>
                netAmount(receiver, amount)
              },
            )
          )
        case _: TxLogEntry.BalanceChange => None
      }

      State(
        entries = newEntries
      )
    }
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
        coinPrice = node.result.value.summary.coinPrice,
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }

    /** State from a choice that returns a `MintSummary`.
      * These are choices that create exactly one new coin in their transaction subtree.
      */
    def fromMintSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTree,
        event: TreeEvent,
        msum: MintSummary[T],
    ): State = {
      // Note: MintSummary only contains the contract id of the new coin, but not the coin payload.
      // However, the new coin is always created in the same transaction.
      // Instead of including the coin price and owner in MintSummary,
      // we locate the corresponding coin create event in the transaction tree.
      val coinCid = msum.coin.contractId
      val coin = tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == coinCid =>
            CoinCreate.unapply(c).map(_.payload)
        }
        .flatten
        .getOrElse(
          throw new RuntimeException(
            s"The coin contract $coinCid referenced by MintSummary was not found in transaction ${tx.getTransactionId}"
          )
        )
      val newEntry = TxLogEntry.BalanceChange(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        date = tx.getEffectiveAt,
        amount = coin.amount.initialAmount,
        receiver = coin.owner,
        coinPrice = msum.coinPrice,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }
    // TODO(#2845) Remove this once we stop matching on create/archive events
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
        coinPrice = BigDecimal(0),
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

    // Net change in the balance of the senders
    sender -> (netOutput + netChange - netInput)
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
          senderFee = BigDecimal(fee) * (BigDecimal(1) - out.receiverFeeRatio),
          receiverFee = BigDecimal(fee) * out.receiverFeeRatio,
        )
      }
  }

}
