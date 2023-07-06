package com.daml.network.wallet.store

import cats.{Eval, Monoid}
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.*
import com.daml.ledger.javaapi.data.codegen.Choice
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.CoinCreateSummary
import com.daml.network.codegen.java.cc.coin.InvalidTransferReason
import com.daml.network.codegen.java.cc.coin.invalidtransferreason.{
  ITR_InsufficientFunds,
  ITR_Other,
}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.{
  CO_AppPayment,
  CO_BuyExtraTraffic,
  CO_CompleteAcceptedTransfer,
  CO_MergeTransferInputs,
  CO_SubscriptionAcceptAndMakeInitialPayment,
  CO_SubscriptionMakePayment,
  CO_Tap,
}
import com.daml.network.codegen.java.cn.wallet.install.{CoinOperation, CoinOperationOutcome}
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.{
  COO_Error,
  COO_MergeTransferInputs,
}
import com.daml.network.history.{
  CoinArchive,
  CoinCreate,
  CoinExpire,
  CoinRules_BuyExtraTraffic,
  ImportCrate_Receive,
  LockedCoinExpireCoin,
  LockedCoinOwnerExpireLock,
  LockedCoinUnlock,
  Mint,
  Tap,
  Transfer,
}
import com.daml.network.http.v0.definitions as httpDef
import com.daml.network.store.TxLogStore
import com.daml.network.util.{Codec, ExerciseNode, ExerciseNodeCompanion}
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry.BalanceChange.BalanceChangeTransactionSubtype
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry.Notification.NotificationTransactionSubtype
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry.Transfer.TransferTransactionSubtype
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.time.{Instant, ZoneOffset}
import scala.collection.immutable
import scala.collection.immutable.Queue
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.{RoundingMode, javaBigDecimal2bigDecimal}

class UserWalletTxLogParser(
    override val loggerFactory: NamedLoggerFactory,
    endUserParty: String,
    endUserName: String,
) extends TxLogStore.Parser[
      UserWalletTxLogParser.TxLogIndexRecord,
      UserWalletTxLogParser.TxLogEntry,
    ]
    with NamedLogging {
  import UserWalletTxLogParser.*

  private def parseTree(tree: TransactionTree, root: TreeEvent)(implicit
      tc: TraceContext
  ): Eval[State] = {
    import Eval.{now, defer}
    root match {
      case exercised: ExercisedEvent =>
        exercised match {

          // ------------------------------------------------------------------
          // Treasury service
          // ------------------------------------------------------------------

          // We are inspecting the WalletAppInstall_ExecuteBatch event in order to distinguish the wallet automation
          // merging coins and collecting rewards from manually triggered transfers where the user sends coin to themselves.
          case WalletAppInstall_ExecuteBatch(node) =>
            val operations = node.argument.value.operations.asScala
            val outcomes = node.result.value.outcomes.asScala
            assert(
              operations.size == outcomes.size,
              "WalletAppInstall_ExecuteBatch should return exactly one CoinOperationOutcome for each CoinOperation",
            )

            // Unfortunately there is not a 1:1 correspondence between CoinOperationOutcome and child events in the
            // transaction tree:
            // - COO_Error does not produce any child event
            // - COO_BuyExtraTraffic can produce up to 2 child events of interest
            //   - tapping of coins to pay for extra traffic (only on DevNet)
            //   - the actual purchase of extra traffic
            // - all other outcomes produce exactly one child exercise event
            val outputsWithChildEvent =
              operations
                .zip(outcomes)
                .foldLeft(
                  (
                    Queue.empty[
                      (
                          CoinOperation,
                          CoinOperationOutcome,
                          Either[InvalidTransferReason, Seq[ExercisedEvent]],
                      )
                    ],
                    0,
                  )
                )({
                  case ((result, nextChildEventId), r) => {
                    r match {
                      case (op, outcome: COO_Error) =>
                        (
                          result.appended(
                            (op, outcome, Left(outcome.invalidTransferReasonValue))
                          ),
                          nextChildEventId,
                        )
                      case (op: CO_BuyExtraTraffic, outcome) =>
                        // Special handling for CO_BuyExtraTraffic to associate multiple events with it.
                        // Since on DevNet, we auto-tap coins, there will be five associated events.
                        // - CoinRules_Fetch
                        // - OpenMiningRound_Fetch
                        // - CoinRules_ComputeFees
                        // - CoinRules_DevNet_Tap
                        // - CoinRules_BuyExtraTraffic
                        // The first 4 of these are related to identifying the amount of CC to tap to cover
                        // the purchase and then actually tapping it.
                        // On non-DevNet, there will be only 2 associated events: the CoinRules_Fetch and
                        // the CoinRules_BuyExtraTraffic exercises.
                        val (childEventCount, expectedChildEvents) = tree.getEventsById
                          .get(exercised.getChildEventIds.get(nextChildEventId + 1)) match {
                          // assume non-DevNet if the second event is the BuyExtraTraffic choice
                          case e: ExercisedEvent if e.getChoice == "CoinRules_BuyExtraTraffic" =>
                            (2, Seq("CoinRules_Fetch", "CoinRules_BuyExtraTraffic"))
                          case _ =>
                            (
                              5,
                              Seq(
                                "CoinRules_Fetch",
                                "OpenMiningRound_Fetch",
                                "CoinRules_ComputeFees",
                                "CoinRules_DevNet_Tap",
                                "CoinRules_BuyExtraTraffic",
                              ),
                            )
                        }
                        val childEvents = exercised.getChildEventIds.asScala.toSeq
                          .slice(nextChildEventId, nextChildEventId + childEventCount)
                          .map(tree.getEventsById.get)
                          .flatMap {
                            case e: ExercisedEvent =>
                              Seq(e)
                            case e =>
                              logger.warn(s"Unexpected event $e.")
                              Seq()
                          }
                        if (childEvents.map(_.getChoice) != expectedChildEvents)
                          logger.warn(
                            s"Expected events $expectedChildEvents. Got ${childEvents.map(_.getChoice)}"
                          )
                        val eventsOfInterest = childEvents.filter(e =>
                          Seq("CoinRules_BuyExtraTraffic", "CoinRules_DevNet_Tap")
                            .contains(e.getChoice)
                        )
                        (
                          result.appended((op, outcome, Right(eventsOfInterest))),
                          nextChildEventId + childEventCount,
                        )
                      case (op, outcome) =>
                        val childEvent =
                          tree.getEventsById.get(exercised.getChildEventIds.get(nextChildEventId))
                        childEvent match {
                          case e: ExercisedEvent =>
                            (result.appended((op, outcome, Right(Seq(e)))), nextChildEventId + 1)
                          case _ =>
                            throw new RuntimeException(
                              "All child events of WalletAppInstall_ExecuteBatch should be exercise events"
                            )
                        }
                    }
                  }
                })
                ._1

            outputsWithChildEvent.foldMap {
              // All errors are handled by producing a notification (if applicable)
              case (op, _: COO_Error, Left(reason)) =>
                // Only show notifications if the batch was submitted by the end user associated with this TxLog.
                // We do not want the validator user (who is also signatory on the WalletAppInstall contract)
                // to see the notifications of all other end-users hosted on the same participant.
                now(if (node.result.value.endUserName == endUserName) {
                  val details = reason match {
                    case r: ITR_InsufficientFunds =>
                      s"ITR_InsufficientFunds: missing ${r.missingAmount} CC"
                    case r: ITR_Other => s"ITR_Other: ${r.description}"
                    case _ => throw new RuntimeException(s"Invalid reason $reason")
                  }
                  op match {
                    case _: CO_CompleteAcceptedTransfer =>
                      State.fromNotification(
                        tree,
                        root,
                        TxLogEntry.Notification.DirectTransferFailed,
                        details,
                      )
                    case _: CO_SubscriptionMakePayment =>
                      State.fromNotification(
                        tree,
                        root,
                        TxLogEntry.Notification.SubscriptionPaymentFailed,
                        details,
                      )
                    // The errors below should not produce notifications
                    case _: CO_AppPayment | _: CO_SubscriptionAcceptAndMakeInitialPayment |
                        _: CO_MergeTransferInputs | _: CO_BuyExtraTraffic | _: CO_Tap =>
                      State.empty
                    case _ => throw new RuntimeException(s"Invalid operation $op")
                  }
                } else {
                  State.empty
                })
              // Tag wallet automation (coin merging, reward collection) as such, to distinguish from
              // explicit self-transfers
              case (_, _: COO_MergeTransferInputs, Right(Seq(childEvent))) =>
                defer(parseTree(tree, childEvent))
                  .map(_.setTransferSubtype(TxLogEntry.Transfer.WalletAutomation))
              // All other successful operations are handled by parsing their subtree
              case (_, _, Right(childEvents)) =>
                childEvents.foldMap(parseTree(tree, _))
              // The above cases should be exhaustive
              case (op, outcome, child) =>
                throw new RuntimeException(
                  s"Impossible combination of $op with $outcome and $child"
                )
            }

          // ------------------------------------------------------------------
          // P2P transfers
          // ------------------------------------------------------------------

          case AcceptedTransferOffer_Complete(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
              )
            }.map(_.setTransferSubtype(TxLogEntry.Transfer.P2PPaymentCompleted))

          // ------------------------------------------------------------------
          // App payments
          // ------------------------------------------------------------------

          // Accepting app payment = locking a coin for the provider
          case AppPaymentRequest_Accept(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
              )
            }.map(_.setTransferSubtype(TxLogEntry.Transfer.AppPaymentAccepted))

          // Collecting app payments = unlocking a locked coin + transferring the coin to the provider
          case AcceptedAppPayment_Collect(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
              )
            }.map(_.mergeBalanceChangesIntoTransfer(TxLogEntry.Transfer.AppPaymentCollected))

          case AcceptedAppPayment_Reject(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.AppPaymentRejected,
              )
            )

          case AcceptedAppPayment_Expire(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.AppPaymentExpired,
              )
            )

          // ------------------------------------------------------------------
          // Subscriptions
          // ------------------------------------------------------------------

          // Accepting subscription = locking a coin for the provider
          case SubscriptionRequest_AcceptAndMakePayment(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
              )
            }.map(_.setTransferSubtype(TxLogEntry.Transfer.SubscriptionInitialPaymentAccepted))

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionInitialPayment_Collect(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
              )
            }.map(
              _.mergeBalanceChangesIntoTransfer(
                TxLogEntry.Transfer.SubscriptionInitialPaymentCollected
              )
            )

          case SubscriptionInitialPayment_Reject(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.SubscriptionInitialPaymentRejected,
              )
            )

          case SubscriptionInitialPayment_Expire(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.SubscriptionInitialPaymentExpired,
              )
            )

          case SubscriptionIdleState_MakePayment(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
              )
            }.map(_.setTransferSubtype(TxLogEntry.Transfer.SubscriptionPaymentAccepted))

          case SubscriptionIdleState_ExpireSubscription(node) =>
            // Note: this notification is shown to both the provider and the subscriber
            now(
              State.fromNotification(
                tree,
                exercised,
                TxLogEntry.Notification.SubscriptionExpired,
                s"Expired by ${node.argument.value.actor} because the last subscription payment was missed",
              )
            )

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionPayment_Collect(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
              )
            }.map(
              _.mergeBalanceChangesIntoTransfer(TxLogEntry.Transfer.SubscriptionPaymentCollected)
            )

          case SubscriptionPayment_Reject(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value._2,
                TxLogEntry.BalanceChange.SubscriptionPaymentRejected,
              )
            )

          case SubscriptionPayment_Expire(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value._2,
                TxLogEntry.BalanceChange.SubscriptionPaymentExpired,
              )
            )

          // ------------------------------------------------------------------
          // Other transfers
          // ------------------------------------------------------------------

          case Transfer(node) =>
            // Note: we do not parse the child events, as we can extract all information about the transfer from this node
            now(State.fromTransfer(tree, root, node, TxLogEntry.Transfer.Transfer))

          // ------------------------------------------------------------------
          // Minting new coins
          // ------------------------------------------------------------------

          case Tap(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.Tap,
              )
            )

          case SvcRules_CollectSvReward(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.SvRewardCollected,
              )
            )

          case Mint(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.Mint,
              )
            )

          case ImportCrate_Receive(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                // We show imports as minted coins.
                TxLogEntry.BalanceChange.Mint,
              )
            )

          // ------------------------------------------------------------------
          // Unlocking locked coins
          // ------------------------------------------------------------------

          case LockedCoinUnlock(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.LockedCoinUnlocked,
              )
            )

          case LockedCoinOwnerExpireLock(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                TxLogEntry.BalanceChange.LockedCoinOwnerExpired,
              )
            )

          // ------------------------------------------------------------------
          // Removing coins with zero value
          // ------------------------------------------------------------------

          case CoinExpire(node) =>
            now(
              State.fromCoinExpire(
                tree,
                exercised,
                node.result.value.owner,
                TxLogEntry.BalanceChange.CoinExpired,
              )
            )

          case LockedCoinExpireCoin(node) =>
            now(
              State.fromCoinExpire(
                tree,
                exercised,
                node.result.value.owner,
                TxLogEntry.BalanceChange.LockedCoinExpired,
              )
            )

          // ------------------------------------------------------------------
          // Buying extra traffic
          // ------------------------------------------------------------------

          case CoinRules_BuyExtraTraffic(_) =>
            now(State.fromBuyExtraTraffic(tree, exercised))

          // ------------------------------------------------------------------
          // Other
          // ------------------------------------------------------------------

          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that archive coins.
          case CoinArchive(_) =>
            // TODO(#6480) cleanup expecting unexpected error messages in logs as a workaround
            throw new RuntimeException(
              s"Unexpected coin archive event for coin ${exercised.getContractId} in transaction ${tree.getTransactionId}"
            )

          case _ =>
            defer { parseTrees(tree, exercised.getChildEventIds.asScala.toList) }
        }

      case created: CreatedEvent =>
        created match {
          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that produce new coins.
          case CoinCreate(coin) =>
            // TODO(#6480) cleanup expecting unexpected error messages in logs as a workaround
            throw new RuntimeException(
              s"Unexpected coin create event for coin ${coin.contractId.contractId} in transaction ${tree.getTransactionId}"
            )

          case _ =>
            now(State.empty)
        }

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }
  private def parseTrees(tree: TransactionTree, rootsEventIds: List[String])(implicit
      tc: TraceContext
  ): Eval[State] = {
    val roots = rootsEventIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, _))
  }

  override def tryParse(tx: TransactionTree)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    parseTrees(tx, tx.getRootEventIds.asScala.toList).value
      .filterByParty(endUserParty)
      .entries
  }

  override def error(offset: String, eventId: String): Option[TxLogEntry] =
    Some(
      TxLogEntry.Unknown(
        TxLogIndexRecord(offset, eventId)
      )
    )
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

    sealed abstract class TransactionSubtype(
        val companion: ExerciseNodeCompanion,
        coinOperation: Option[String],
    ) {
      val templateId: Identifier = companion.templateOrInterface match {
        case Left(value) => value.TEMPLATE_ID
        case Right(value) => value.TEMPLATE_ID
      }
      val choice: Choice[companion.Tpl, companion.Arg, companion.Res] = companion.choice

      def toResponseItem: httpDef.TransactionSubtype = httpDef.TransactionSubtype(
        templateId =
          s"${templateId.getPackageId}:${templateId.getModuleName}:${templateId.getEntityName}",
        choice = choice.name,
        coinOperation = coinOperation,
      )
    }

    /* Unknown event, caused the parser failing to parse a transaction tree */
    final case class Unknown(
        indexRecord: TxLogIndexRecord
    ) extends TxLogEntry {
      override def toResponseItem: httpDef.ListTransactionsResponseItem =
        httpDef.ListTransactionsResponseItem(
          transactionType = Unknown.TransactionType,
          transactionSubtype = httpDef.TransactionSubtype("unknown", "unknown"),
          eventId = indexRecord.eventId,
          offset = indexRecord.offset,
          date = java.time.OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC),
        )

      override def setEventId(eventId: String): TxLogEntry = {
        copy(indexRecord = indexRecord.copy(eventId = eventId))
      }
    }
    object Unknown {
      val TransactionType = "unknown"
    }

    /** Balance change due to a transfer */
    final case class Transfer(
        indexRecord: TxLogIndexRecord,
        transactionSubtype: TransferTransactionSubtype,
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
          transactionSubtype = transactionSubtype.toResponseItem,
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
      sealed abstract class TransferTransactionSubtype(
          companion: ExerciseNodeCompanion
      ) extends TransactionSubtype(companion, None)

      object TransferTransactionSubtype {
        val values: Map[String, TransferTransactionSubtype] = Set[TransferTransactionSubtype](
          P2PPaymentCompleted,
          AppPaymentAccepted,
          AppPaymentCollected,
          SubscriptionInitialPaymentAccepted,
          SubscriptionInitialPaymentCollected,
          SubscriptionPaymentAccepted,
          SubscriptionPaymentCollected,
          WalletAutomation,
          ExtraTrafficPurchase,
          Transfer,
        ).map(txSubtype => txSubtype.choice.name -> txSubtype).toMap

        def find(choiceName: String): Option[TransferTransactionSubtype] =
          values.get(choiceName)
      }
      case object P2PPaymentCompleted
          extends TransferTransactionSubtype(AcceptedTransferOffer_Complete)
      case object AppPaymentAccepted extends TransferTransactionSubtype(AppPaymentRequest_Accept)
      case object AppPaymentCollected extends TransferTransactionSubtype(AcceptedAppPayment_Collect)
      case object SubscriptionInitialPaymentAccepted
          extends TransferTransactionSubtype(SubscriptionRequest_AcceptAndMakePayment)
      case object SubscriptionInitialPaymentCollected
          extends TransferTransactionSubtype(SubscriptionInitialPayment_Collect)
      case object SubscriptionPaymentAccepted
          extends TransferTransactionSubtype(SubscriptionIdleState_MakePayment)
      case object SubscriptionPaymentCollected
          extends TransferTransactionSubtype(SubscriptionPayment_Collect)
      case object WalletAutomation extends TransferTransactionSubtype(WalletAppInstall_ExecuteBatch)
      case object ExtraTrafficPurchase extends TransferTransactionSubtype(CoinRules_BuyExtraTraffic)
      case object Transfer extends TransferTransactionSubtype(com.daml.network.history.Transfer)
    }

    /** Balance change not due to a transfer, for example a tap or returning a locked coin to the owner. */
    final case class BalanceChange(
        indexRecord: TxLogIndexRecord,
        transactionSubtype: BalanceChangeTransactionSubtype,
        date: Instant,
        receiver: String,
        amount: BigDecimal,
        coinPrice: BigDecimal,
    ) extends TxLogEntry {
      override def toResponseItem: httpDef.ListTransactionsResponseItem =
        httpDef.ListTransactionsResponseItem(
          transactionType = BalanceChange.TransactionType,
          transactionSubtype = transactionSubtype.toResponseItem,
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

      sealed abstract class BalanceChangeTransactionSubtype(companion: ExerciseNodeCompanion)
          extends TransactionSubtype(companion, None)

      object BalanceChangeTransactionSubtype {
        val values: Map[String, BalanceChangeTransactionSubtype] =
          Set[BalanceChangeTransactionSubtype](
            Tap,
            Mint,
            SvRewardCollected,
            AppPaymentRejected,
            AppPaymentExpired,
            SubscriptionInitialPaymentRejected,
            SubscriptionInitialPaymentExpired,
            SubscriptionPaymentRejected,
            SubscriptionPaymentExpired,
            LockedCoinUnlocked,
            LockedCoinOwnerExpired,
            LockedCoinExpired,
            CoinExpired,
          ).map(txSubtype => txSubtype.choice.name -> txSubtype).toMap

        def find(choiceName: String): Option[BalanceChangeTransactionSubtype] =
          values.get(choiceName)
      }
      case object Tap extends BalanceChangeTransactionSubtype(com.daml.network.history.Tap)
      case object Mint extends BalanceChangeTransactionSubtype(com.daml.network.history.Mint)
      case object SvRewardCollected
          extends BalanceChangeTransactionSubtype(SvcRules_CollectSvReward)
      case object AppPaymentRejected
          extends BalanceChangeTransactionSubtype(AcceptedAppPayment_Reject)
      case object AppPaymentExpired
          extends BalanceChangeTransactionSubtype(AcceptedAppPayment_Expire)
      case object SubscriptionInitialPaymentRejected
          extends BalanceChangeTransactionSubtype(SubscriptionInitialPayment_Reject)
      case object SubscriptionInitialPaymentExpired
          extends BalanceChangeTransactionSubtype(SubscriptionInitialPayment_Expire)
      case object SubscriptionPaymentRejected
          extends BalanceChangeTransactionSubtype(SubscriptionPayment_Reject)
      case object SubscriptionPaymentExpired
          extends BalanceChangeTransactionSubtype(SubscriptionPayment_Expire)
      case object LockedCoinUnlocked extends BalanceChangeTransactionSubtype(LockedCoinUnlock)
      case object LockedCoinOwnerExpired
          extends BalanceChangeTransactionSubtype(LockedCoinOwnerExpireLock)
      case object LockedCoinExpired extends BalanceChangeTransactionSubtype(LockedCoinExpireCoin)
      case object CoinExpired extends BalanceChangeTransactionSubtype(CoinExpire)
    }

    /** An event that does not change anyone's coin balance. */
    final case class Notification(
        indexRecord: TxLogIndexRecord,
        transactionSubtype: NotificationTransactionSubtype,
        date: Instant,
        details: String,
    ) extends TxLogEntry {
      override def toResponseItem: httpDef.ListTransactionsResponseItem =
        httpDef.ListTransactionsResponseItem(
          transactionType = Notification.TransactionType,
          transactionSubtype = transactionSubtype.toResponseItem,
          eventId = indexRecord.eventId,
          offset = indexRecord.offset,
          date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
          details = Some(details),
        )

      override def setEventId(eventId: String): TxLogEntry = {
        copy(indexRecord = indexRecord.copy(eventId = eventId))
      }
    }

    object Notification {
      val TransactionType = "notification"

      /** @param coinOperation the constructor name of the CoinOperation. Only relevant for WalletAppInstall_ExecuteBatch
        */
      sealed abstract class NotificationTransactionSubtype(
          companion: ExerciseNodeCompanion,
          val coinOperation: Option[String],
      ) extends TransactionSubtype(companion, coinOperation)
      object NotificationTransactionSubtype {
        val values: Map[(String, Option[String]), NotificationTransactionSubtype] =
          Set[NotificationTransactionSubtype](
            DirectTransferFailed,
            SubscriptionPaymentFailed,
            SubscriptionExpired,
          ).map(txSubtype =>
            (
              txSubtype.choice.name,
              txSubtype.coinOperation,
            ) -> txSubtype
          ).toMap
        def find(
            choiceName: String,
            coinOperationConstructor: Option[String],
        ): Option[NotificationTransactionSubtype] =
          values.get((choiceName, coinOperationConstructor))
      }
      case object DirectTransferFailed
          extends NotificationTransactionSubtype(
            WalletAppInstall_ExecuteBatch,
            Some("CO_CompleteAcceptedTransfer"),
          )
      case object SubscriptionPaymentFailed
          extends NotificationTransactionSubtype(
            WalletAppInstall_ExecuteBatch,
            Some("CO_SubscriptionMakePayment"),
          )
      case object SubscriptionExpired
          extends NotificationTransactionSubtype(SubscriptionIdleState_ExpireSubscription, None)
    }

    private def transferFromResponseItem(
        item: httpDef.ListTransactionsResponseItem
    ): Either[String, TxLogEntry.Transfer] = {
      val indexRecord = TxLogIndexRecord(
        offset = item.offset,
        eventId = item.eventId,
      )
      for {
        transactionSubtype <- TransferTransactionSubtype
          .find(item.transactionSubtype.choice)
          .toRight("TransactionSubtype not found")
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
        transactionSubtype = transactionSubtype,
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
        transactionSubtype <- BalanceChangeTransactionSubtype
          .find(item.transactionSubtype.choice)
          .toRight("TransactionSubtype not found")
        receiverAndAmount <- item.receivers.flatMap(_.headOption).toRight("No receivers")
        coinPriceStr <- item.coinPrice.toRight("Coin price missing")
        coinPrice <- Codec.decode(Codec.BigDecimal)(coinPriceStr)
      } yield TxLogEntry.BalanceChange(
        transactionSubtype = transactionSubtype,
        indexRecord = indexRecord,
        date = item.date.toInstant,
        receiver = receiverAndAmount.party,
        amount = Codec.tryDecode(Codec.BigDecimal)(receiverAndAmount.amount),
        coinPrice = coinPrice,
      )
    }

    private def notificationFromResponseItem(
        item: httpDef.ListTransactionsResponseItem
    ): Either[String, TxLogEntry.Notification] = {
      val indexRecord = TxLogIndexRecord(
        offset = item.offset,
        eventId = item.eventId,
      )
      for {
        transactionSubtype <- NotificationTransactionSubtype
          .find(item.transactionSubtype.choice, item.transactionSubtype.coinOperation)
          .toRight("TransactionSubtype not found")
        details <- item.details.toRight("Details missing")
      } yield TxLogEntry.Notification(
        transactionSubtype = transactionSubtype,
        indexRecord = indexRecord,
        date = item.date.toInstant,
        details = details,
      )
    }

    private def unknownFromResponseItem(
        item: httpDef.ListTransactionsResponseItem
    ): Either[String, TxLogEntry.Unknown] = {
      val indexRecord = TxLogIndexRecord(
        offset = item.offset,
        eventId = item.eventId,
      )
      Right(
        TxLogEntry.Unknown(
          indexRecord = indexRecord
        )
      )
    }

    // Note: deserialization is only needed for the Canton console
    def fromResponseItem(item: httpDef.ListTransactionsResponseItem): Either[String, TxLogEntry] = {
      item.transactionType match {
        case TxLogEntry.Transfer.TransactionType => transferFromResponseItem(item)
        case TxLogEntry.BalanceChange.TransactionType => balanceChangeFromResponseItem(item)
        case TxLogEntry.Notification.TransactionType => notificationFromResponseItem(item)
        case TxLogEntry.Unknown.TransactionType => unknownFromResponseItem(item)
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
        // Only relevant notifications are added to parsing state
        case _: TxLogEntry.Notification => true
        case _: TxLogEntry.Unknown => true
      }
    )

    /** Sets the transaction type of all transfer events to the given type */
    def setTransferSubtype(transactionSubtype: TransferTransactionSubtype): State = {
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
    def mergeBalanceChangesIntoTransfer(transactionSubtype: TransferTransactionSubtype): State = {
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
        case n: TxLogEntry.Notification => Some(n)
        case n: TxLogEntry.Unknown => Some(n)
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
        transactionSubtype: BalanceChangeTransactionSubtype,
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
        transactionSubtype: TransferTransactionSubtype,
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
      // second child event is the transfer of CC from validator to SVC to buy extra traffic
      val transferEvent = tx.getEventsById.get(event.getChildEventIds.get(1))
      // Calculate tx log entries from transfer of CC from validator to SVC
      val transferNode = (transferEvent match {
        case e: ExercisedEvent => Transfer.unapply(e)
        case _ => None
      }).getOrElse(
        throw new RuntimeException(s"Unable to parse event ${transferEvent.getEventId} as Transfer")
      )
      val stateFromTransfer =
        State.fromTransfer(tx, transferEvent, transferNode, TxLogEntry.Transfer.Transfer)

      // third child event is burning of transferred coin by SVC
      val coinArchiveEvent = tx.getEventsById.get(event.getChildEventIds.get(2))
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
            transactionSubtype =
              TxLogEntry.BalanceChange.Tap, // This doesn't matter - it'll be removed by mergeBalanceChangesIntoTransfer
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
        transactionSubtype: BalanceChangeTransactionSubtype,
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

    def fromNotification(
        tx: TransactionTree,
        event: TreeEvent,
        transactionSubtype: NotificationTransactionSubtype,
        details: String,
    ): State = {
      val newEntry = TxLogEntry.Notification(
        indexRecord = TxLogIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId,
        ),
        transactionSubtype = transactionSubtype,
        date = tx.getEffectiveAt,
        details = details,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }
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
