package com.daml.network.wallet.store

import cats.Monoid
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.*
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.CoinCreateSummary
import com.daml.network.codegen.java.cn.wallet.install.CoinOperationOutcome
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.{
  COO_Error,
  COO_MergeTransferInputs,
}
import com.daml.network.history.{
  CoinArchive,
  CoinCreate,
  CoinExpire,
  CoinRules_BuyExtraTraffic,
  LockedCoinExpireCoin,
  LockedCoinOwnerExpireLock,
  LockedCoinUnlock,
  Mint,
  Tap,
  Transfer,
}
import com.daml.network.http.v0.definitions as httpDef
import com.daml.network.store.TxLogStore
import com.daml.network.util.{Codec, Contract, ExerciseNode, ExerciseNodeCompanion}
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry.BalanceChange
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.time.{Instant, ZoneOffset}
import scala.collection.immutable
import scala.collection.immutable.Queue
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.{javaBigDecimal2bigDecimal, RoundingMode}

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
          // Treasury service
          // ------------------------------------------------------------------

          // We are inspecting the WalletAppInstall_ExecuteBatch event in order to distinguish the wallet automation
          // merging coins and collecting rewards from manually triggered transfers where the user sends coin to themselves.
          case WalletAppInstall_ExecuteBatch(node) =>
            assert(
              node.argument.value.operations.size() == node.result.value.size(),
              "WalletAppInstall_ExecuteBatch should return exactly one CoinOperationOutcome for each CoinOperation",
            )

            // Unfortunately there is not a 1:1 correspondence between CoinOperationOutcome and child events in the
            // transaction tree:
            // - COO_Error does not produce any child event
            // - all other outcomes produce exactly one child exercise event
            val outputsWithChildEvent =
              node.result.value.asScala.foldLeft(
                Queue.empty[(CoinOperationOutcome, ExercisedEvent)]
              )((state, r) => {
                r match {
                  case _: COO_Error => state
                  case outcome =>
                    val nextChildEventId = state.length
                    val childEvent =
                      tree.getEventsById.get(exercised.getChildEventIds.get(nextChildEventId))
                    childEvent match {
                      case e: ExercisedEvent => state.appended(outcome -> e)
                      case _ =>
                        throw new RuntimeException(
                          "All child events of WalletAppInstall_ExecuteBatch should be exercise events"
                        )
                    }
                }
              })

            val result = outputsWithChildEvent.foldMap {
              case (_: COO_MergeTransferInputs, childEvent) =>
                parseTree(tree, childEvent)
                  .setTransferSubtype(TxLogEntry.Transfer.WalletAutomation)
              case (_, childEvent) => parseTree(tree, childEvent)
            }

            result

          // ------------------------------------------------------------------
          // P2P transfers
          // ------------------------------------------------------------------

          case AcceptedTransferOffer_Complete(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).setTransferSubtype(TxLogEntry.Transfer.P2PPaymentCompleted)

          // ------------------------------------------------------------------
          // App payments
          // ------------------------------------------------------------------

          // Accepting app payment = locking a coin for the provider
          case AppPaymentRequest_Accept(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).setTransferSubtype(TxLogEntry.Transfer.AppPaymentAccepted)

          // Collecting app payments = unlocking a locked coin + transferring the coin to the provider
          case AcceptedAppPayment_Collect(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).mergeBalanceChangesIntoTransfer(TxLogEntry.Transfer.AppPaymentCollected)

          case AcceptedAppPayment_Reject(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value,
              BalanceChange.AppPaymentRejected,
            )

          case AcceptedAppPayment_Expire(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value,
              BalanceChange.AppPaymentExpired,
            )

          // ------------------------------------------------------------------
          // Subscriptions
          // ------------------------------------------------------------------

          // Accepting subscription = locking a coin for the provider
          case SubscriptionRequest_AcceptAndMakePayment(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).setTransferSubtype(TxLogEntry.Transfer.SubscriptionInitialPaymentAccepted)

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionInitialPayment_Collect(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).mergeBalanceChangesIntoTransfer(
              TxLogEntry.Transfer.SubscriptionInitialPaymentCollected
            )

          case SubscriptionInitialPayment_Reject(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value,
              BalanceChange.SubscriptionInitialPaymentRejected,
            )

          case SubscriptionInitialPayment_Expire(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value,
              BalanceChange.SubscriptionInitialPaymentExpired,
            )

          case SubscriptionIdleState_MakePayment(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).setTransferSubtype(TxLogEntry.Transfer.SubscriptionPaymentAccepted)

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionPayment_Collect(_) =>
            parseTrees(
              tree,
              exercised.getChildEventIds.asScala.toList,
            ).mergeBalanceChangesIntoTransfer(TxLogEntry.Transfer.SubscriptionPaymentCollected)

          case SubscriptionPayment_Reject(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value._2,
              BalanceChange.SubscriptionPaymentRejected,
            )

          case SubscriptionPayment_Expire(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value._2,
              BalanceChange.SubscriptionPaymentExpired,
            )

          // ------------------------------------------------------------------
          // Other transfers
          // ------------------------------------------------------------------

          case Transfer(node) =>
            // Note: we do not parse the child events, as we can extract all information about the transfer from this node
            State.fromTransfer(tree, root, node, TxLogEntry.Transfer.Transfer)

          // ------------------------------------------------------------------
          // Minting new coins
          // ------------------------------------------------------------------

          case Tap(node) =>
            State.fromCoinCreateSummary(tree, root, node.result.value, BalanceChange.Tap)

          case SvcRules_CollectSvReward(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value,
              BalanceChange.SvRewardCollected,
            )

          case Mint(node) =>
            State.fromCoinCreateSummary(tree, root, node.result.value, BalanceChange.Mint)

          // ------------------------------------------------------------------
          // Unlocking locked coins
          // ------------------------------------------------------------------

          case LockedCoinUnlock(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value,
              BalanceChange.LockedCoinUnlocked,
            )

          case LockedCoinOwnerExpireLock(node) =>
            State.fromCoinCreateSummary(
              tree,
              root,
              node.result.value,
              BalanceChange.LockedCoinExpired,
            )

          // ------------------------------------------------------------------
          // Removing coins with zero value
          // ------------------------------------------------------------------

          case CoinExpire(node) =>
            State.fromCoinExpire(tree, exercised, node.result.value, BalanceChange.CoinExpired)

          case LockedCoinExpireCoin(node) =>
            State.fromCoinExpire(
              tree,
              exercised,
              node.result.value,
              BalanceChange.LockedCoinExpired,
            )

          // ------------------------------------------------------------------
          // Buying extra traffic
          // ------------------------------------------------------------------

          case CoinRules_BuyExtraTraffic(_) =>
            State.fromBuyExtraTraffic(tree, exercised)

          // ------------------------------------------------------------------
          // Other
          // ------------------------------------------------------------------

          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that archive coins.
          case CoinArchive(_) =>
            // Using a warning to force our tests to fail should we ever introduce a new coin workflow that is not handled above.
            logger.warn(
              s"Unexpected coin archive event for coin ${exercised.getContractId} in transaction ${tree.getTransactionId}"
            )
            State.empty

          case _ =>
            parseTrees(tree, exercised.getChildEventIds.asScala.toList)
        }

      case created: CreatedEvent =>
        created match {
          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that produce new coins.
          case CoinCreate(coin) =>
            // Using a warning to force our tests to fail should we ever introduce a new coin workflow that is not handled above.
            logger.warn(
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
    def toResponseItem: httpDef.ListTransactionsResponseItem
    def setEventId(eventId: String): TxLogEntry
  }

  object TxLogEntry {

    /** Balance change due to a transfer */
    // TODO(M3-04) This needs to include more info, e.g., amount of collected rewards
    // and exchange rate at the time.
    final case class Transfer(
        indexRecord: TxLogIndexRecord,
        transactionSubtype: String,
        date: Instant,
        provider: String,
        sender: (String, BigDecimal),
        receivers: Seq[(String, BigDecimal)],
        senderHoldingFees: BigDecimal,
        coinPrice: BigDecimal,
        appRewardsUsed: BigDecimal,
        validatorRewardsUsed: BigDecimal,
    ) extends TxLogEntry {
      override def toResponseItem: httpDef.ListTransactionsResponseItem =
        httpDef.ListTransactionsResponseItem(
          transactionType = Transfer.TransactionType,
          transactionSubtype = transactionSubtype,
          eventId = indexRecord.eventId,
          offset = indexRecord.offset,
          date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
          provider = Some(provider),
          sender = Some(httpDef.PartyAndAmount(sender._1, Codec.encode(sender._2))),
          receivers =
            Some(receivers.map(r => httpDef.PartyAndAmount(r._1, Codec.encode(r._2))).toVector),
          holdingFees = Some(Codec.encode(senderHoldingFees)),
          coinPrice = Some(Codec.encode(coinPrice)),
          appRewardsUsed = Some(Codec.encode(appRewardsUsed)),
          validatorRewardsUsed = Some(Codec.encode(validatorRewardsUsed)),
        )

      override def setEventId(eventId: String): TxLogEntry = {
        copy(indexRecord = indexRecord.copy(eventId = eventId))
      }
    }
    object Transfer {
      val TransactionType = "transfer"
      val P2PPaymentCompleted = "p2p_payment_completed"
      val AppPaymentAccepted = "app_payment_accepted"
      val AppPaymentCollected = "app_payment_collected"
      val SubscriptionInitialPaymentAccepted = "subscription_initial_payment_accepted"
      val SubscriptionInitialPaymentCollected = "subscription_initial_payment_collected"
      val SubscriptionPaymentAccepted = "subscription_payment_accepted"
      val SubscriptionPaymentCollected = "subscription_payment_collected"
      val WalletAutomation = "wallet_automation"
      val ExtraTrafficPurchase = "extra_traffic_purchase"
      val Transfer = "unknown_transfer"
    }

    /** Balance change not due to a transfer, for example a tap or returning a locked coin to the owner. */
    // TODO(M3-04) Include more context info here to distinguish different balance changes.
    final case class BalanceChange(
        indexRecord: TxLogIndexRecord,
        transactionSubtype: String,
        date: Instant,
        receiver: String,
        amount: BigDecimal,
        coinPrice: BigDecimal,
    ) extends TxLogEntry {
      override def toResponseItem: httpDef.ListTransactionsResponseItem =
        httpDef.ListTransactionsResponseItem(
          transactionType = BalanceChange.TransactionType,
          transactionSubtype = transactionSubtype,
          eventId = indexRecord.eventId,
          offset = indexRecord.offset,
          date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
          provider = None,
          sender = None,
          receivers = Some(Vector(httpDef.PartyAndAmount(receiver, Codec.encode(amount)))),
          holdingFees = None,
          coinPrice = Some(Codec.encode(coinPrice)),
        )

      override def setEventId(eventId: String): TxLogEntry = {
        copy(indexRecord = indexRecord.copy(eventId = eventId))
      }
    }

    object BalanceChange {
      val TransactionType = "balance_change"
      val Tap = "tap"
      val Mint = "mint"
      val SvRewardCollected = "sv_reward_collected"
      val AppPaymentRejected = "app_payment_rejected"
      val AppPaymentExpired = "app_payment_expired"
      val SubscriptionInitialPaymentRejected = "subscription_initial_payment_rejected"
      val SubscriptionInitialPaymentExpired = "subscription_initial_payment_expired"
      val SubscriptionPaymentRejected = "subscription_payment_rejected"
      val SubscriptionPaymentExpired = "subscription_payment_expired"
      val LockedCoinUnlocked = "locked_coin_unlock"
      val LockedCoinExpired = "locked_coin_expired"
      val CoinExpired = "coin_expired"
    }

    private def transferFromResponseItem(
        item: httpDef.ListTransactionsResponseItem
    ): Either[String, TxLogEntry.Transfer] = {
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
        appRewardsUsedStr <- item.appRewardsUsed.toRight("App rewards missing")
        appRewardsUsed <- Codec.decode(Codec.BigDecimal)(appRewardsUsedStr)
        validatorRewardsUsedStr <- item.validatorRewardsUsed.toRight("Validator rewards missing")
        validatorRewardsUsed <- Codec.decode(Codec.BigDecimal)(validatorRewardsUsedStr)
      } yield TxLogEntry.Transfer(
        indexRecord = indexRecord,
        transactionSubtype = item.transactionSubtype,
        date = item.date.toInstant,
        provider = provider,
        sender = sender.party -> senderAmount,
        receivers = receivers,
        senderHoldingFees = senderHoldingFees,
        coinPrice = coinPrice,
        appRewardsUsed = appRewardsUsed,
        validatorRewardsUsed = validatorRewardsUsed,
      )
    }

    private def balanceChangeFromResponseItem(
        item: httpDef.ListTransactionsResponseItem
    ): Either[String, TxLogEntry.BalanceChange] = {
      val indexRecord = TxLogIndexRecord(
        offset = item.offset,
        eventId = item.eventId,
      )
      for {
        receiverAndAmount <- item.receivers.flatMap(_.headOption).toRight("No receivers")
        coinPriceStr <- item.coinPrice.toRight("Coin price missing")
        coinPrice <- Codec.decode(Codec.BigDecimal)(coinPriceStr)
      } yield TxLogEntry.BalanceChange(
        transactionSubtype = item.transactionSubtype,
        indexRecord = indexRecord,
        date = item.date.toInstant,
        receiver = receiverAndAmount.party,
        amount = Codec.tryDecode(Codec.BigDecimal)(receiverAndAmount.amount),
        coinPrice = coinPrice,
      )
    }

    // Note: deserialization is only needed for the Canton console
    def fromResponseItem(item: httpDef.ListTransactionsResponseItem): Either[String, TxLogEntry] = {
      item.transactionType match {
        case TxLogEntry.Transfer.TransactionType => transferFromResponseItem(item)
        case TxLogEntry.BalanceChange.TransactionType => balanceChangeFromResponseItem(item)
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

    /** Sets the transaction type of all transfer events to the given type */
    def setTransferSubtype(transactionSubtype: String): State = {
      State(
        entries = entries.map {
          case b: TxLogEntry.Transfer =>
            b.copy(transactionSubtype = transactionSubtype)
          case other => other
        }
      )
    }

    /** Sets the index record eventId for all entries to the given eventId.
      *
      * This is useful when you want to re-use the parsing logic of existing methods
      * like State.fromTransfer for some new event but want the index record to reflect
      * the eventId of the new event.
      */
    def setEventId(eventId: String): State = {
      State(
        entries = entries.map(_.setEventId(eventId))
      )
    }

    /** Given a parsing state where the parser has encountered exactly one transfer and zero or more balance changes,
      * returns a parsing state where all the balance changes have been merged into the transfer event.
      *
      * This is useful for app payments where the payment collection first unlocks locked coins and immediately uses
      * them for a transfer. In this case, we only want to display one balance change for the user.
      */
    def mergeBalanceChangesIntoTransfer(transactionSubtype: String): State = {
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
              transactionSubtype = transactionSubtype,
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
    def fromCoinExpire(
        tx: TransactionTree,
        event: TreeEvent,
        owner: String,
        transactionSubtype: String,
    ): State = {
      val newEntry = TxLogEntry.BalanceChange(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        transactionSubtype = transactionSubtype,
        date = tx.getEffectiveAt,
        amount = BigDecimal(0),
        receiver = owner,
        coinPrice = BigDecimal(0),
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }
    def fromTransfer(
        tx: TransactionTree,
        event: TreeEvent,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
        transactionSubtype: String,
    ): State = {
      val newEntry = TxLogEntry.Transfer(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        transactionSubtype = transactionSubtype,
        date = tx.getEffectiveAt,
        provider = node.argument.value.transfer.provider,
        sender = parseSender(node.argument.value, node.result.value),
        receivers = parseReceivers(node.argument.value, node.result.value),
        senderHoldingFees = node.result.value.summary.holdingFees,
        coinPrice = node.result.value.summary.coinPrice,
        appRewardsUsed = BigDecimal(node.result.value.summary.inputAppRewardAmount),
        validatorRewardsUsed = BigDecimal(node.result.value.summary.inputValidatorRewardAmount),
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromBuyExtraTraffic(
        tx: TransactionTree,
        event: ExercisedEvent,
    )(implicit lc: ErrorLoggingContext): State = {
      // first child event is the transfer of CC from validator to SVC to buy extra traffic
      val transferEvent = tx.getEventsById.get(event.getChildEventIds.get(0))
      // Calculate tx log entries from transfer of CC from validator to SVC
      val transferNode = (transferEvent match {
        case e: ExercisedEvent => Transfer.unapply(e)
        case _ => None
      }).getOrElse(
        throw new RuntimeException(s"Unable to parse event ${transferEvent.getEventId} as Transfer")
      )
      val stateFromTransfer =
        State.fromTransfer(tx, transferEvent, transferNode, TxLogEntry.Transfer.Transfer)

      // second child event is burning of transferred coin by SVC
      val coinArchiveEvent = tx.getEventsById.get(event.getChildEventIds.get(1))
      // Adjust tx log entries for SVC since the coin it receives is immediately burnt
      val burntCoin = tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == coinArchiveEvent.getContractId =>
            CoinCreate.unapply(c).map(_.payload)
        }
        .flatten
        .getOrElse(
          throw new RuntimeException(
            s"The coin contract ${coinArchiveEvent.getContractId} " +
              s"referenced by the coin archive event ${coinArchiveEvent.getEventId} " +
              s"was not found in transaction ${tx.getTransactionId}"
          )
        )
      val stateFromBurntCoin = State(entries =
        immutable.Queue(
          TxLogEntry.BalanceChange(
            indexRecord = TxLogIndexRecord(
              offset = tx.getOffset,
              eventId = transferEvent.getEventId,
            ),
            transactionSubtype = BalanceChange.TransactionType,
            date = tx.getEffectiveAt,
            receiver = burntCoin.owner,
            amount = -burntCoin.amount.initialAmount,
            coinPrice = transferNode.result.value.summary.coinPrice,
          )
        )
      )

      stateFromTransfer
        .appended(stateFromBurntCoin)
        .mergeBalanceChangesIntoTransfer(TxLogEntry.Transfer.ExtraTrafficPurchase)
        .setEventId(event.getEventId)

    }

    /** State from a choice that returns a `MintSummary`.
      * These are choices that create exactly one new coin in their transaction subtree.
      */
    def fromCoinCreateSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTree,
        event: TreeEvent,
        ccsum: CoinCreateSummary[T],
        transactionSubtype: String,
    ): State = {
      // Note: CoinCreateSummary only contains the contract id of the new coin, but not the coin payload.
      // However, the new coin is always created in the same transaction.
      // Instead of including the coin price and owner in CoinCreateSummary,
      // we locate the corresponding coin create event in the transaction tree.
      val coinCid = ccsum.coin.contractId
      val coin = tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == coinCid =>
            CoinCreate.unapply(c).map(_.payload)
        }
        .flatten
        .getOrElse(
          throw new RuntimeException(
            s"The coin contract $coinCid referenced by CoinCreateSummary was not found in transaction ${tx.getTransactionId}"
          )
        )
      val newEntry = TxLogEntry.BalanceChange(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        transactionSubtype = transactionSubtype,
        date = tx.getEffectiveAt,
        amount = coin.amount.initialAmount,
        receiver = coin.owner,
        coinPrice = ccsum.coinPrice,
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

    // Input coins, excluding holding fees
    val netInput = res.summary.inputCoinAmount - res.summary.holdingFees

    // Output coins going back to the sender, after deducting transfer fees
    val netOutput = parseOutputAmounts(arg, res)
      .filter(o => o.output.receiver == sender && o.output.lock.isEmpty)
      .map(o => o.output.amount - o.senderFee)
      .sum

    // Leftover change
    val netChange = BigDecimal(res.summary.senderChangeAmount)

    // Net change in the balance of the senders
    sender -> (netOutput + netChange - netInput)
  }

  /** Returns a list of receivers and their net balance changes */
  private def parseReceivers(
      arg: v1.coin.CoinRules_Transfer,
      res: v1.coin.TransferResult,
  ): Seq[(String, BigDecimal)] = {
    def netBalanceChange(o: OutputWithFees) =
      if (o.output.lock.isEmpty) {
        o.output.amount - o.receiverFee
      } else {
        -o.receiverFee
      }

    // Note: the same receiver party can appear multiple times in the transfer result
    // The code below merges balance changes for the same receiver, while preserving
    // the order of receivers.
    parseOutputAmounts(arg, res)
      .filter(_.output.receiver != arg.transfer.sender)
      .map(o => o.output.receiver -> netBalanceChange(o))
      .foldLeft(immutable.ListMap.empty[String, BigDecimal])((acc, receiver) =>
        acc.updatedWith(receiver._1)(prev => Some(prev.fold(receiver._2)(_ + receiver._2)))
      )
      .toList
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
          senderFee = setDamlDecimalScale(BigDecimal(fee) * (BigDecimal(1) - out.receiverFeeRatio)),
          receiverFee = setDamlDecimalScale(BigDecimal(fee) * out.receiverFeeRatio),
        )
      }
  }

  /** Returns the input number modified such that it has the same number of decimal places as a daml decimal */
  private def setDamlDecimalScale(x: BigDecimal): BigDecimal =
    x.setScale(10, RoundingMode.HALF_EVEN)

}
