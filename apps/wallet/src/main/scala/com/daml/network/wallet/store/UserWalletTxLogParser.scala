package com.daml.network.wallet.store

import cats.{Eval, Monoid}
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.*
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.CoinCreateSummary
import com.daml.network.codegen.java.cc.coinrules.InvalidTransferReason
import com.daml.network.codegen.java.cc.coinrules.invalidtransferreason.{
  ITR_InsufficientFunds,
  ITR_InsufficientTopupAmount,
  ITR_Other,
  ITR_UnknownDomain,
}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.{
  CO_AppPayment,
  CO_BuyMemberTraffic,
  CO_CompleteAcceptedTransfer,
  CO_CompleteBuyTrafficRequest,
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
import com.daml.network.codegen.java.cn.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  transferoffer as transferCodegen,
}
import com.daml.network.history.{
  CnsRules_CollectEntryRenewalPayment,
  CnsRules_CollectInitialEntryPayment,
  CoinArchive,
  CoinCreate,
  CoinExpire,
  CoinRules_BuyMemberTraffic,
  ImportCrate_ReceiveCoin,
  LockedCoinExpireCoin,
  LockedCoinOwnerExpireLock,
  LockedCoinUnlock,
  Mint,
  Tap,
  Transfer,
}
import com.daml.network.store.TxLogStore
import com.daml.network.util.ExerciseNode
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.collection.immutable
import scala.collection.immutable.Queue
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.{RoundingMode, javaBigDecimal2bigDecimal}
import com.daml.network.environment.ledger.api.{ActiveContract, IncompleteReassignmentEvent}
import com.daml.network.wallet.store.TxLogEntry.{
  BalanceChangeTransactionSubtype,
  NotificationTransactionSubtype,
  TransferTransactionSubtype,
}
import com.digitalasset.canton.topology.{DomainId, PartyId}

class UserWalletTxLogParser(
    override val loggerFactory: NamedLoggerFactory,
    endUserParty: PartyId,
    endUserName: String,
) extends TxLogStore.Parser[TxLogEntry]
    with NamedLogging {
  import UserWalletTxLogParser.*

  private def parseTree(tree: TransactionTreeV2, root: TreeEvent, domainId: DomainId)(implicit
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
            // - COO_BuyMemberTraffic can produce up to 2 child events of interest
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
                      case (op: CO_BuyMemberTraffic, outcome) =>
                        // Special handling for CO_BuyMemberTraffic to associate multiple events with it.
                        // Since we auto-tap coins on DevNet, there will be 7 associated events.
                        // - Archive (of the old ValidatorTopUpState)
                        // - Create (of the new ValidatorTopUpState)
                        // - CoinRules_Fetch
                        // - OpenMiningRound_Fetch
                        // - CoinRules_ComputeFees
                        // - CoinRules_DevNet_Tap
                        // - CoinRules_BuyMemberTraffic
                        // The first 4 of these are related to identifying the amount of CC to tap to cover
                        // the purchase and then actually tapping it.
                        // On non-DevNet, there will be only 4 associated events.
                        // - Archive (of the old ValidatorTopUpState)
                        // - Create (of the new ValidatorTopUpState)
                        // - CoinRules_Fetch
                        // - CoinRules_BuyMemberTraffic
                        val (childEventCount, expectedChildExercisedEvents) = tree.getEventsById
                          // assume non-DevNet if the fourth event is the BuyMemberTraffic choice
                          .get(exercised.getChildEventIds.get(nextChildEventId + 3)) match {
                          case e: ExercisedEvent if e.getChoice == "CoinRules_BuyMemberTraffic" =>
                            (4, Seq("Archive", "CoinRules_Fetch", "CoinRules_BuyMemberTraffic"))
                          case _ =>
                            (
                              7,
                              Seq(
                                "Archive",
                                "CoinRules_Fetch",
                                "OpenMiningRound_Fetch",
                                "CoinRules_ComputeFees",
                                "CoinRules_DevNet_Tap",
                                "CoinRules_BuyMemberTraffic",
                              ),
                            )
                        }

                        val childExercisedEvents = exercised.getChildEventIds.asScala.toSeq
                          .slice(nextChildEventId, nextChildEventId + childEventCount)
                          .map(tree.getEventsById.get)
                          .flatMap {
                            case e: ExercisedEvent =>
                              Seq(e)
                            case e: CreatedEvent
                                if e.getTemplateId.getEntityName == "ValidatorTopUpState" =>
                              Seq()
                            case e =>
                              logger.warn(s"Unexpected event $e.")
                              Seq()
                          }
                        if (childExercisedEvents.map(_.getChoice) != expectedChildExercisedEvents)
                          logger.warn(
                            s"Expected events $expectedChildExercisedEvents. Got ${childExercisedEvents
                                .map(_.getChoice)}"
                          )
                        val eventsOfInterest = childExercisedEvents.filter(e =>
                          Seq("CoinRules_BuyMemberTraffic", "CoinRules_DevNet_Tap")
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
                    case r: ITR_UnknownDomain =>
                      s"ITR_UnknownDomain: domainId ${r.domainId}"
                    case r: ITR_InsufficientTopupAmount =>
                      s"ITR_InsufficientTopupAmount: requested ${r.requestedTopupAmount}, minimum required ${r.minTopupAmount}"
                    case r: ITR_Other => s"ITR_Other: ${r.description}"
                    case _ => throw new RuntimeException(s"Invalid reason $reason")
                  }
                  op match {
                    case _: CO_CompleteAcceptedTransfer =>
                      State.fromNotification(
                        tree,
                        root,
                        TxLogEntry.NotificationTransactionSubtype.DirectTransferFailed,
                        details,
                      )
                    case _: CO_SubscriptionMakePayment =>
                      State.fromNotification(
                        tree,
                        root,
                        TxLogEntry.NotificationTransactionSubtype.SubscriptionPaymentFailed,
                        details,
                      )
                    // The errors below should not produce notifications
                    case _: CO_AppPayment | _: CO_SubscriptionAcceptAndMakeInitialPayment |
                        _: CO_MergeTransferInputs | _: CO_BuyMemberTraffic |
                        _: CO_CompleteBuyTrafficRequest | _: CO_Tap =>
                      State.empty
                    case _ => throw new RuntimeException(s"Invalid operation $op")
                  }
                } else {
                  State.empty
                })
              // Tag wallet automation (coin merging, reward collection) as such, to distinguish from
              // explicit self-transfers
              case (_, _: COO_MergeTransferInputs, Right(Seq(childEvent))) =>
                defer(parseTree(tree, childEvent, domainId))
                  .map(_.setTransferSubtype(TransferTransactionSubtype.WalletAutomation))
              // All other successful operations are handled by parsing their subtree
              case (_, _, Right(childEvents)) =>
                childEvents.foldMap(parseTree(tree, _, domainId))
              // The above cases should be exhaustive
              case (op, outcome, child) =>
                throw new RuntimeException(
                  s"Impossible combination of $op with $outcome and $child"
                )
            }

          // ------------------------------------------------------------------
          // Transfer Offers
          // TODO (#7153): these are not used for the Transaction History, and would benefit from being split off
          // ------------------------------------------------------------------

          case WalletAppInstall_CreateTransferOffer(_) =>
            now(State.fromCreateTransferOffer(tree, exercised))

          case TransferOffer_Accept(_) =>
            now(State.fromTransferOfferAccept(tree, exercised))

          case TransferOffer_Reject(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Rejected(TransferOfferStatusRejected()),
                node,
              )
            )

          case TransferOffer_Withdraw(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Withdrawn(
                  TransferOfferStatusWithdrawn(node.argument.value.reason)
                ),
                node,
              )
            )

          case TransferOffer_Expire(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Expired(TransferOfferStatusExpired()),
                node,
              )
            )

          // ------------------------------------------------------------------
          // P2P transfers
          // ------------------------------------------------------------------

          case AcceptedTransferOffer_Complete(node) =>
            // this creates different entries with different event ids:
            // one is from parsing the AcceptedTransferOffer_Complete itself, the others are from parsing the children
            for {
              stateFromOfferCompletion <- now(
                State.fromTransferOfferComplete(tree, node)
              )
              stateFromChildren <- defer {
                parseTrees(
                  tree,
                  exercised.getChildEventIds.asScala.toList,
                  domainId,
                )
              }.map(_.setTransferSubtype(TransferTransactionSubtype.P2PPaymentCompleted))
            } yield stateFromOfferCompletion.appended(stateFromChildren)

          case AcceptedTransferOffer_Abort(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Withdrawn(
                  TransferOfferStatusWithdrawn(node.argument.value.reason)
                ),
                node,
              )
            )

          case AcceptedTransferOffer_Expire(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Expired(
                  TransferOfferStatusExpired()
                ),
                node,
              )
            )

          case AcceptedTransferOffer_Withdraw(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Withdrawn(
                  TransferOfferStatusWithdrawn(node.argument.value.reason)
                ),
                node,
              )
            )

          // ------------------------------------------------------------------
          // Buy Traffic Requests
          // ------------------------------------------------------------------

          case WalletAppInstall_CreateBuyTrafficRequest(_) =>
            now(State.fromCreateBuyTrafficRequest(tree, exercised))

          case BuyTrafficRequest_Complete(node) =>
            // this creates different entries with different event ids:
            // one is from parsing the BuyTrafficRequest_Complete itself, the others are from parsing the children
            for {
              stateFromRequestCompletion <- now(
                State.fromBuyTrafficRequestComplete(tree, node)
              )
              stateFromChildren <- defer {
                parseTrees(
                  tree,
                  exercised.getChildEventIds.asScala.toList,
                  domainId,
                )
              }
            } yield stateFromRequestCompletion.appended(stateFromChildren)

          case BuyTrafficRequest_Cancel(node) =>
            now(
              State.fromBuyTrafficRequestFailure(
                BuyTrafficRequestTxLogEntry.Status.Rejected(
                  BuyTrafficRequestStatusRejected(node.argument.value.reason)
                ),
                node,
              )
            )

          case BuyTrafficRequest_Expire(node) =>
            now(
              State.fromBuyTrafficRequestFailure(
                BuyTrafficRequestTxLogEntry.Status.Expired(
                  BuyTrafficRequestStatusExpired()
                ),
                node,
              )
            )

          // ------------------------------------------------------------------
          // App payments
          // ------------------------------------------------------------------

          // Accepting app payment = locking a coin for the provider
          case AppPaymentRequest_Accept(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
                domainId,
              )
            }.map(_.setTransferSubtype(TransferTransactionSubtype.AppPaymentAccepted))

          // Collecting app payments = unlocking a locked coin + transferring the coin to the provider
          case AcceptedAppPayment_Collect(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
                domainId,
              )
            }.map(_.mergeBalanceChangesIntoTransfer(TransferTransactionSubtype.AppPaymentCollected))

          case AcceptedAppPayment_Reject(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.AppPaymentRejected,
              )
            )

          case AcceptedAppPayment_Expire(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.AppPaymentExpired,
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
                domainId,
              )
            }.map(
              _.setTransferSubtype(TransferTransactionSubtype.SubscriptionInitialPaymentAccepted)
            )

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionInitialPayment_Collect(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
                domainId,
              )
            }.map(
              _.mergeBalanceChangesIntoTransfer(
                TransferTransactionSubtype.SubscriptionInitialPaymentCollected
              )
            )

          case SubscriptionInitialPayment_Reject(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.SubscriptionInitialPaymentRejected,
              )
            )

          case SubscriptionInitialPayment_Expire(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.SubscriptionInitialPaymentExpired,
              )
            )

          case SubscriptionIdleState_MakePayment(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
                domainId,
              )
            }.map(_.setTransferSubtype(TransferTransactionSubtype.SubscriptionPaymentAccepted))

          case SubscriptionIdleState_ExpireSubscription(node) =>
            // Note: this notification is shown to both the provider and the subscriber
            now(
              State.fromNotification(
                tree,
                exercised,
                NotificationTransactionSubtype.SubscriptionExpired,
                s"Expired by ${node.argument.value.actor} because the last subscription payment was missed",
              )
            )

          // Collecting subscription payments = unlocking a locked coin + transferring the coin to the provider
          case SubscriptionPayment_Collect(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
                domainId,
              )
            }.map(
              _.mergeBalanceChangesIntoTransfer(
                TransferTransactionSubtype.SubscriptionPaymentCollected
              )
            )

          case SubscriptionPayment_Reject(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value._2,
                BalanceChangeTransactionSubtype.SubscriptionPaymentRejected,
              )
            )

          case SubscriptionPayment_Expire(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value._2,
                BalanceChangeTransactionSubtype.SubscriptionPaymentExpired,
              )
            )

          // ------------------------------------------------------------------
          // Other transfers
          // ------------------------------------------------------------------

          case Transfer(node) =>
            // Note: we do not parse the child events, as we can extract all information about the transfer from this node
            now(State.fromTransfer(tree, root, node, TransferTransactionSubtype.Transfer))

          // ------------------------------------------------------------------
          // Minting new coins
          // ------------------------------------------------------------------

          case Tap(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.Tap,
              )
            )

          case SvcRules_CollectSvReward(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.SvRewardCollected,
              )
            )

          case Mint(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.Mint,
              )
            )

          case ImportCrate_ReceiveCoin(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                // We show imports as minted coins.
                BalanceChangeTransactionSubtype.Mint,
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
                BalanceChangeTransactionSubtype.LockedCoinUnlocked,
              )
            )

          case LockedCoinOwnerExpireLock(node) =>
            now(
              State.fromCoinCreateSummary(
                tree,
                root,
                node.result.value,
                BalanceChangeTransactionSubtype.LockedCoinOwnerExpired,
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
                BalanceChangeTransactionSubtype.CoinExpired,
              )
            )

          case LockedCoinExpireCoin(node) =>
            now(
              State.fromCoinExpire(
                tree,
                exercised,
                node.result.value.owner,
                BalanceChangeTransactionSubtype.LockedCoinExpired,
              )
            )

          // ------------------------------------------------------------------
          // Buying domain traffic
          // ------------------------------------------------------------------

          case CoinRules_BuyMemberTraffic(node) =>
            now(State.fromBuyMemberTraffic(node, tree, exercised))

          // ------------------------------------------------------------------
          // Canton name service subscription payment collection
          // ------------------------------------------------------------------

          case CnsRules_CollectInitialEntryPayment(_) =>
            fromCnsEntryPaymentCollection(
              tree,
              exercised,
              domainId,
              TransferTransactionSubtype.InitialEntryPaymentCollection,
            )

          case CnsRules_CollectEntryRenewalPayment(_) =>
            fromCnsEntryPaymentCollection(
              tree,
              exercised,
              domainId,
              TransferTransactionSubtype.EntryRenewalPaymentCollection,
            )

          // ------------------------------------------------------------------
          // Other
          // ------------------------------------------------------------------

          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that archive coins.
          case CoinArchive(_) =>
            throw new RuntimeException(
              s"Unexpected coin archive event for coin ${exercised.getContractId} in transaction ${tree.getUpdateId}"
            )

          case _ =>
            defer { parseTrees(tree, exercised.getChildEventIds.asScala.toList, domainId) }
        }

      case created: CreatedEvent =>
        created match {
          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that produce new coins.
          case CoinCreate(coin) =>
            throw new RuntimeException(
              s"Unexpected coin create event for coin ${coin.contractId.contractId} in transaction ${tree.getUpdateId}"
            )

          case _ =>
            now(State.empty)
        }

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }
  private def parseTrees(tree: TransactionTreeV2, rootsEventIds: List[String], domainId: DomainId)(
      implicit tc: TraceContext
  ): Eval[State] = {
    val roots = rootsEventIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, _, domainId))
  }

  override def parseAcs(
      acs: Seq[ActiveContract],
      incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
      incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
  )(implicit
      tc: TraceContext
  ): Seq[(DomainId, Option[ContractId[?]], TxLogEntry)] = {
    // Note: entries may appear in a random order, but it's unlikely that a user has many coins,
    // due to the wallet automation automatically merging coins.
    acs.collect(ac => ac.createdEvent match { case CoinCreate(c) => State.fromAcsCoin(ac, c) })
  }

  override def tryParse(tx: TransactionTreeV2, domainId: DomainId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    parseTrees(tx, tx.getRootEventIds.asScala.toList, domainId).value
      .filterByParty(endUserParty)
      .entries
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(UnknownTxLogEntry(eventId))

  private def fromCnsEntryPaymentCollection(
      tree: TransactionTreeV2,
      exercised: ExercisedEvent,
      domainId: DomainId,
      transactionSubtype: TransferTransactionSubtype,
  )(implicit tc: TraceContext): Eval[State] = {
    import Eval.defer
    // first child event is the subscription payment collected by SVC
    val paymentCollectionEvent =
      tree.getEventsById.get(exercised.getChildEventIds.get(0)) match {
        case e: ExercisedEvent => e
        case e =>
          throw new RuntimeException(
            s"Unable to parse event ${e.getEventId} as ExercisedEvent"
          )
      }
    defer(
      parseTree(tree, paymentCollectionEvent, domainId).map { stateFromPaymentCollection =>
        State.fromCollectEntryPayment(
          tree,
          exercised,
          stateFromPaymentCollection,
          transactionSubtype,
        )
      }
    )
  }
}

object UserWalletTxLogParser {

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
    def filterByParty(party: PartyId): State = {
      val partyStr = party.toProtoPrimitive
      State(
        entries = entries.filter {
          case t: TransferTxLogEntry =>
            t.sender.exists(_.party == partyStr) || t.receivers.exists(_.party == partyStr)
          case to: TransferOfferTxLogEntry => to.sender == partyStr || to.receiver == partyStr
          case btr: BuyTrafficRequestTxLogEntry => btr.buyer == partyStr
          case b: BalanceChangeTxLogEntry => b.receiver == partyStr
          // Only relevant notifications are added to parsing state
          case _: NotificationTxLogEntry => true
          case _: UnknownTxLogEntry => true
          case e => throw new RuntimeException(s"Unknown TxLogEntry type $e")
        }
      )
    }

    /** Sets the transaction type of all transfer events to the given type */
    def setTransferSubtype(
        transactionSubtype: TransferTransactionSubtype
    ): State = {
      State(
        entries = entries.map {
          case b: TransferTxLogEntry =>
            b.copy(subtype = Some(transactionSubtype.toProto))
          case other => other
        }
      )
    }

    /** Sets the eventId for all entries that store eventIds to the given eventId.
      *
      * This is useful when you want to re-use the parsing logic of existing methods
      * like State.fromTransfer for some new event but want the TxLogEntry to reflect
      * the eventId of the new event.
      */
    def setEventId(eventId: String): State = {
      State(
        entries = entries.map {
          case e: UnknownTxLogEntry => e.copy(eventId = eventId)
          case e: TransferTxLogEntry => e.copy(eventId = eventId)
          case e: BalanceChangeTxLogEntry => e.copy(eventId = eventId)
          case e: NotificationTxLogEntry => e.copy(eventId = eventId)
          case e => e
        }
      )
    }

    /** Given a parsing state where the parser has encountered exactly one transfer and zero or more balance changes,
      * returns a parsing state where all the balance changes have been merged into the transfer event.
      *
      * This is useful for app payments where the payment collection first unlocks locked coins and immediately uses
      * them for a transfer. In this case, we only want to display one balance change for the user.
      */
    def mergeBalanceChangesIntoTransfer(
        transactionSubtype: TransferTransactionSubtype
    ): State = {
      val balanceChanges = entries.foldLeft(Map[String, BigDecimal]())((changes, entry) =>
        entry match {
          case b: BalanceChangeTxLogEntry =>
            changes.updatedWith(b.receiver)(amount => Some(amount.fold(b.amount)(_ + b.amount)))
          case _ => changes
        }
      )
      def netAmount(p: PartyAndAmount) =
        PartyAndAmount(
          party = p.party,
          amount = (p.amount + balanceChanges
            .getOrElse(p.party, BigDecimal(0))),
        )

      // The code below works only if there is exactly one transfer.
      // Otherwise the balance changes are lost or duplicated by adding them to multiple transfers.
      assert(entries.collect { case t: TransferTxLogEntry => t }.length == 1)

      val newEntries: Queue[TxLogEntry] = entries.flatMap {
        case t: TransferTxLogEntry =>
          Some(
            t.copy(
              subtype = Some(transactionSubtype.toProto),
              sender = t.sender.map(netAmount),
              receivers = t.receivers.map(netAmount),
            )
          )
        case _: BalanceChangeTxLogEntry => None
        case n: TransferOfferTxLogEntry => Some(n)
        case n: BuyTrafficRequestTxLogEntry => Some(n)
        case n: NotificationTxLogEntry => Some(n)
        case n: UnknownTxLogEntry => Some(n)
        case n => throw new RuntimeException(s"Unknown TxLogEntry type $n")
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
        tx: TransactionTreeV2,
        event: TreeEvent,
        owner: String,
        transactionSubtype: BalanceChangeTransactionSubtype,
    ): State = {
      val newEntry = BalanceChangeTxLogEntry(
        eventId = event.getEventId,
        subtype = Some(transactionSubtype.toProto),
        date = Some(tx.getEffectiveAt),
        amount = BigDecimal(0),
        receiver = owner,
        coinPrice = BigDecimal(0),
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }
    def fromCreateTransferOffer(
        tx: TransactionTreeV2,
        event: ExercisedEvent,
    ): State = {
      val (offerCid, transferOffer) = tx.getEventsById.get(event.getChildEventIds.get(0)) match {
        case event: CreatedEvent =>
          event.getContractId -> transferCodegen.TransferOffer
            .valueDecoder()
            .decode(event.getArguments)
        case x =>
          throw new RuntimeException(
            s"Expected first child to be the CreatedEvent of a TransferOffer, but was $x"
          )
      }
      fromTransferOfferOperation(
        transferOffer.trackingId,
        TransferOfferTxLogEntry.Status.Created(
          TransferOfferStatusCreated(
            offerCid,
            tx.getUpdateId,
          )
        ),
        transferOffer.sender,
        transferOffer.receiver,
      )
    }

    def fromTransferOfferAccept(
        tx: TransactionTreeV2,
        event: ExercisedEvent,
    ): State = {
      val (acceptedCid, acceptedTransferOffer) =
        tx.getEventsById.get(event.getChildEventIds.get(0)) match {
          case event: CreatedEvent =>
            event.getContractId -> transferCodegen.AcceptedTransferOffer
              .valueDecoder()
              .decode(event.getArguments)
          case x =>
            throw new RuntimeException(
              s"Expected first child to be the CreatedEvent of a AcceptedTransferOffer, but was $x"
            )
        }
      fromTransferOfferOperation(
        acceptedTransferOffer.trackingId,
        TransferOfferTxLogEntry.Status.Accepted(
          TransferOfferStatusAccepted(
            acceptedCid,
            tx.getUpdateId,
          )
        ),
        acceptedTransferOffer.sender,
        acceptedTransferOffer.receiver,
      )
    }

    def fromTransferOfferFailure(
        failureReason: TransferOfferTxLogEntry.Status,
        node: ExerciseNode[?, transferCodegen.TransferOfferTrackingInfo],
    ): State = {
      val trackingInfo = node.result.value
      fromTransferOfferOperation(
        trackingInfo.trackingId,
        failureReason,
        trackingInfo.sender,
        trackingInfo.receiver,
      )
    }

    def fromTransferOfferComplete(
        tx: TransactionTreeV2,
        node: ExerciseNode[?, AcceptedTransferOffer_Complete.Res],
    ): State = {
      val trackingInfo = node.result.value._1._2
      val receiverCoinContractId = node.result.value._1._1.createdCoins.asScala.toList match {
        case (coin: cc.coinrules.createdcoin.TransferResultCoin) :: Nil =>
          coin.contractIdValue.contractId
        case x =>
          throw new RuntimeException(
            s"Expected createdCoins to contain a single TransferResultCoin, but was $x"
          )
      }
      fromTransferOfferOperation(
        trackingInfo.trackingId,
        TransferOfferTxLogEntry.Status.Completed(
          TransferOfferStatusCompleted(
            receiverCoinContractId,
            tx.getUpdateId,
          )
        ),
        trackingInfo.sender,
        trackingInfo.receiver,
      )
    }

    private def fromTransferOfferOperation(
        trackingId: String,
        status: TransferOfferTxLogEntry.Status,
        sender: String,
        receiver: String,
    ) = {
      val newEntry = TransferOfferTxLogEntry(
        trackingId = trackingId,
        status = status,
        sender = sender,
        receiver = receiver,
      )
      State(entries = immutable.Queue(newEntry))
    }

    def fromTransfer(
        tx: TransactionTreeV2,
        event: TreeEvent,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
        transactionSubtype: TransferTransactionSubtype,
    ): State = {
      val newEntry = TransferTxLogEntry(
        eventId = event.getEventId,
        subtype = Some(transactionSubtype.toProto),
        date = Some(tx.getEffectiveAt),
        provider = node.argument.value.transfer.provider,
        sender = Some(parseSender(node.argument.value, node.result.value)),
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

    def fromCreateBuyTrafficRequest(
        tx: TransactionTreeV2,
        event: ExercisedEvent,
    ): State = {
      val buyTrafficRequest = tx.getEventsById.get(event.getChildEventIds.get(0)) match {
        case event: CreatedEvent =>
          trafficRequestCodegen.BuyTrafficRequest
            .valueDecoder()
            .decode(event.getArguments)
        case x =>
          throw new RuntimeException(
            s"Expected first child to be the CreatedEvent of a BuyTrafficRequest, but was $x"
          )
      }
      fromBuyTrafficRequestOperation(
        buyTrafficRequest.trackingId,
        BuyTrafficRequestTxLogEntry.Status.Created(BuyTrafficRequestStatusCreated()),
        buyTrafficRequest.endUserParty,
      )
    }

    def fromBuyTrafficRequestComplete(
        tx: TransactionTreeV2,
        node: ExerciseNode[?, BuyTrafficRequest_Complete.Res],
    ): State = {
      val trackingInfo = node.result.value._1._2
      fromBuyTrafficRequestOperation(
        trackingInfo.trackingId,
        BuyTrafficRequestTxLogEntry.Status.Completed(
          BuyTrafficRequestStatusCompleted(
            tx.getUpdateId
          )
        ),
        trackingInfo.endUserParty,
      )
    }

    def fromBuyTrafficRequestFailure(
        failureReason: BuyTrafficRequestTxLogEntry.Status,
        node: ExerciseNode[?, trafficRequestCodegen.BuyTrafficRequestTrackingInfo],
    ): State = {
      val trackingInfo = node.result.value
      fromBuyTrafficRequestOperation(
        trackingInfo.trackingId,
        failureReason,
        trackingInfo.endUserParty,
      )
    }

    private def fromBuyTrafficRequestOperation(
        trackingId: String,
        status: BuyTrafficRequestTxLogEntry.Status,
        buyer: String,
    ) = {
      val newEntry = BuyTrafficRequestTxLogEntry(
        trackingId = trackingId,
        status = status,
        buyer = buyer,
      )
      State(entries = immutable.Queue(newEntry))
    }

    def fromBuyMemberTraffic(
        node: ExerciseNode[CoinRules_BuyMemberTraffic.Arg, CoinRules_BuyMemberTraffic.Res],
        tx: TransactionTreeV2,
        event: ExercisedEvent,
    ): State = {
      val sender = node.argument.value.provider
      // Hack to grab the SVC party-id from the balance changes, which list both sender and the SVC
      val receivers = node.result.value.summary.balanceChanges.keySet().asScala.toSeq.collect {
        case party if party != sender => PartyAndAmount(party, BigDecimal(0.0))
      }
      val summary = node.result.value.summary
      val netSenderInput = summary.inputCoinAmount - summary.holdingFees
      val senderBalanceChange = BigDecimal(summary.senderChangeAmount) - netSenderInput

      val newEntry = TransferTxLogEntry(
        eventId = event.getEventId,
        subtype = Some(TransferTransactionSubtype.ExtraTrafficPurchase.toProto),
        date = Some(tx.getEffectiveAt),
        provider = sender,
        sender = Some(PartyAndAmount(sender, senderBalanceChange)),
        receivers = receivers,
        senderHoldingFees = node.result.value.summary.holdingFees,
        coinPrice = node.result.value.summary.coinPrice,
        appRewardsUsed = BigDecimal(node.result.value.summary.inputAppRewardAmount),
        validatorRewardsUsed = BigDecimal(node.result.value.summary.inputValidatorRewardAmount),
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromCollectEntryPayment(
        tx: TransactionTreeV2,
        event: ExercisedEvent,
        stateFromPaymentCollection: State,
        transactionSubtype: TransferTransactionSubtype,
    ): State = {
      // second child event is burning of transferred coin by SVC
      val coinArchiveEvent = tx.getEventsById.get(event.getChildEventIds.get(1))
      // Adjust tx log entries for SVC since the coin it receives is immediately burnt
      val burntCoin = getCoinCreateEvent(tx, coinArchiveEvent.getContractId)
      val stateFromBurntCoin = State.fromBurntCoin(burntCoin)

      stateFromPaymentCollection
        .appended(stateFromBurntCoin)
        .mergeBalanceChangesIntoTransfer(transactionSubtype)
        .setEventId(event.getEventId)
    }

    /** State from a choice that returns a `MintSummary`.
      * These are choices that create exactly one new coin in their transaction subtree.
      */
    def fromCoinCreateSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTreeV2,
        event: TreeEvent,
        ccsum: CoinCreateSummary[T],
        transactionSubtype: BalanceChangeTransactionSubtype,
    ): State = {
      // Note: CoinCreateSummary only contains the contract id of the new coin, but not the coin payload.
      // However, the new coin is always created in the same transaction.
      // Instead of including the coin price and owner in CoinCreateSummary,
      // we locate the corresponding coin create event in the transaction tree.
      val coinCid = ccsum.coin.contractId
      val coin = getCoinCreateEvent(tx, coinCid)
      val newEntry = BalanceChangeTxLogEntry(
        eventId = event.getEventId,
        subtype = Some(transactionSubtype.toProto),
        date = Some(tx.getEffectiveAt),
        amount = coin.amount.initialAmount,
        receiver = coin.owner,
        coinPrice = ccsum.coinPrice,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromNotification(
        tx: TransactionTreeV2,
        event: TreeEvent,
        transactionSubtype: NotificationTransactionSubtype,
        details: String,
    ): State = {
      val newEntry = NotificationTxLogEntry(
        eventId = event.getEventId,
        subtype = Some(transactionSubtype.toProto),
        date = Some(tx.getEffectiveAt),
        details = details,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromAcsCoin(
        ac: ActiveContract,
        activeCoin: CoinCreate.ContractType,
    ): (DomainId, Option[ContractId[?]], TxLogEntry) = {
      (
        ac.domainId,
        Some(new codegen.ContractId(ac.createdEvent.getContractId)),
        BalanceChangeTxLogEntry(
          eventId = ac.createdEvent.getEventId,
          subtype = Some(BalanceChangeTransactionSubtype.Mint.toProto),
          receiver = activeCoin.payload.owner,
          amount = activeCoin.payload.amount.initialAmount,
          // We know the round in which the coin was created (activeCoin.payload.amount.createdAt),
          // but we don't know when that round was open (let alone when exactly the coin was created),
          // and what the coin price was at that time.
          date = Some(Instant.EPOCH),
          coinPrice = BigDecimal(1),
        ),
      )
    }

    private def fromBurntCoin(
        burntCoin: CoinCreate.T
    ) = State(entries =
      immutable.Queue(
        BalanceChangeTxLogEntry(
          receiver = burntCoin.owner,
          amount = -burntCoin.amount.initialAmount,
          // all values below don't matter - they will be removed by mergeBalanceChangesIntoTransfer
          eventId = "",
          subtype = Some(BalanceChangeTransactionSubtype.Tap.toProto),
          date = Some(Instant.now()),
          coinPrice = BigDecimal(0),
        )
      )
    )

    private def getCoinCreateEvent(tx: TransactionTreeV2, cid: String) = {
      tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == cid =>
            CoinCreate.unapply(c).map(_.payload)
        }
    }.flatten.getOrElse(
      throw new RuntimeException(
        s"The coin contract $cid was not found in transaction ${tx.getUpdateId}"
      )
    )
  }

  private def parseSender(
      arg: cc.coinrules.CoinRules_Transfer,
      res: cc.coinrules.TransferResult,
  ): PartyAndAmount = {
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
    PartyAndAmount(
      party = sender,
      amount = netOutput + netChange - netInput,
    )
  }

  /** Returns a list of receivers and their net balance changes */
  private def parseReceivers(
      arg: cc.coinrules.CoinRules_Transfer,
      res: cc.coinrules.TransferResult,
  ): Seq[PartyAndAmount] = {
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
      .map(o => PartyAndAmount(o._1, o._2))
      .toList
  }

  /** A requested output of a transfer, together with the actual fees paid for the transfer.
    *
    * @param output Contains the receiver and the gross amount received (before deducting fees).
    * @param senderFee Actual amount of fees paid by the sender.
    * @param receiverFee Actual amount of fees paid by the receiver.
    */
  private final case class OutputWithFees(
      output: cc.coinrules.TransferOutput,
      senderFee: BigDecimal,
      receiverFee: BigDecimal,
  )

  private def parseOutputAmounts(
      arg: cc.coinrules.CoinRules_Transfer,
      res: cc.coinrules.TransferResult,
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
