// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store

import cats.{Eval, Monoid}
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.*
import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.AmuletCreateSummary
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.InvalidTransferReason
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.invalidtransferreason.{
  ITR_InsufficientFunds,
  ITR_InsufficientTopupAmount,
  ITR_Other,
  ITR_UnknownSynchronizer,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.buytrafficrequest.BuyTrafficRequestTrackingInfo
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.{
  CO_AppPayment,
  CO_BuyMemberTraffic,
  CO_CompleteAcceptedTransfer,
  CO_CompleteBuyTrafficRequest,
  CO_MergeTransferInputs,
  CO_SubscriptionAcceptAndMakeInitialPayment,
  CO_SubscriptionMakePayment,
  CO_Tap,
  ExtAmuletOperation,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.{
  AmuletOperation,
  AmuletOperationOutcome,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome.{
  COO_Error,
  COO_MergeTransferInputs,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferoffer.TransferOfferTrackingInfo
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  transferoffer as transferCodegen,
}
import org.lfdecentralizedtrust.splice.history.{
  AnsRules_CollectEntryRenewalPayment,
  AnsRules_CollectInitialEntryPayment,
  AmuletArchive,
  AmuletCreate,
  AmuletExpire,
  AmuletRules_BuyMemberTraffic,
  LockedAmuletExpireAmulet,
  LockedAmuletOwnerExpireLock,
  LockedAmuletUnlock,
  Mint,
  Tap,
  Transfer,
}
import org.lfdecentralizedtrust.splice.store.TxLogStore
import org.lfdecentralizedtrust.splice.util.{ExerciseNode, ExerciseNodeCompanion}
import org.lfdecentralizedtrust.splice.util.TransactionTreeExtensions.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.collection.immutable
import scala.collection.immutable.Queue
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.math.BigDecimal.{RoundingMode, javaBigDecimal2bigDecimal}
import org.lfdecentralizedtrust.splice.environment.ledger.api.ActiveContract
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry.{
  BalanceChangeTransactionSubtype,
  NotificationTransactionSubtype,
  TransferTransactionSubtype,
}
import com.digitalasset.canton.topology.{DomainId, PartyId}

class UserWalletTxLogParser(
    override val loggerFactory: NamedLoggerFactory,
    endUserParty: PartyId,
) extends TxLogStore.Parser[TxLogEntry]
    with NamedLogging {
  import UserWalletTxLogParser.*

  private def parseTree(tree: TransactionTree, root: TreeEvent, domainId: DomainId)(implicit
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
          // merging amulets and collecting rewards from manually triggered transfers where the user sends amulet to themselves.
          case WalletAppInstall_ExecuteBatch(node) =>
            val operations = node.argument.value.operations.asScala
            val outcomes = node.result.value.outcomes.asScala
            assert(
              operations.size == outcomes.size,
              "WalletAppInstall_ExecuteBatch should return exactly one AmuletOperationOutcome for each AmuletOperation",
            )

            // Unfortunately there is not a 1:1 correspondence between AmuletOperationOutcome and child events in the
            // transaction tree:
            // - COO_Error does not produce any child event
            // - COO_BuyMemberTraffic can produce up to 2 child events of interest
            //   - tapping of amulets to pay for extra traffic (only on DevNet)
            //   - the actual purchase of extra traffic
            // - all other outcomes produce exactly one child exercise event
            val outputsWithChildEvent =
              operations
                .zip(outcomes)
                .foldLeft(
                  (
                    Queue.empty[
                      (
                          AmuletOperation,
                          AmuletOperationOutcome,
                          Either[InvalidTransferReason, Seq[ExercisedEvent]],
                      )
                    ],
                    exercised.getChildEventIds.asScala.map(tree.getEventsById.asScala),
                  )
                )({
                  case ((result, nextChildEvents), r) => {
                    r match {
                      case (op, outcome: COO_Error) =>
                        (
                          result.appended(
                            (op, outcome, Left(outcome.invalidTransferReasonValue))
                          ),
                          nextChildEvents,
                        )
                      case (op: CO_BuyMemberTraffic, outcome) =>
                        // Special handling for CO_BuyMemberTraffic to associate multiple events with it.
                        // There will be several events until the final event, AmuletRules_BuyMemberTraffic.
                        // We look for that event, and filter in only those interim events we
                        // also want to add to the result, i.e. the AmuletRules_DevNet_Tap.
                        import splice.amuletrules.AmuletRules as AR
                        val finalEventChoice = AR.CHOICE_AmuletRules_BuyMemberTraffic.name
                        val interimEventChoices = Set(AR.CHOICE_AmuletRules_DevNet_Tap.name)
                        val (eventsOfInterest, remainingEvents) = splitFirst(nextChildEvents) {
                          case e: ExercisedEvent if e.getChoice == finalEventChoice =>
                            e
                        } match {
                          case (priorEvents, Some((bmtEvent, furtherChildEvents))) =>
                            (
                              priorEvents.collect {
                                case e: ExercisedEvent if interimEventChoices(e.getChoice) =>
                                  e
                              } :+ bmtEvent,
                              furtherChildEvents,
                            )
                          case (allEvents, None) =>
                            logger.warn {
                              val remainingExercises =
                                nextChildEvents.collect { case e: ExercisedEvent => e.getChoice }
                              s"Expected events to include $finalEventChoice. Got $remainingExercises"
                            }
                            (Seq.empty, allEvents)
                        }
                        (
                          result.appended((op, outcome, Right(eventsOfInterest.toSeq))),
                          remainingEvents,
                        )
                      case (op, outcome) =>
                        nextChildEvents match {
                          case (e: ExercisedEvent) +: furtherChildEvents =>
                            (result.appended((op, outcome, Right(Seq(e)))), furtherChildEvents)
                          case _ =>
                            // ...except if CO_BuyMemberTraffic is used
                            throw new RuntimeException(
                              "All child events of WalletAppInstall_ExecuteBatch should be exercise events"
                            )
                        }
                    }
                  }
                })
                ._1

            outputsWithChildEvent.zipWithIndex.foldMap {
              // All errors are handled by producing a notification (if applicable)
              case ((op, _: COO_Error, Left(reason)), idx) =>
                // Only show notifications if the batch was submitted for the end-user party of this TxLog. We do not
                // want the validator party's tx log  (who is also signatory on the WalletAppInstall contract) to
                // generate notifications for actions of all other end-user parties.
                val actingEndUserParty = node.result.value.optEndUserParty.toScala
                now(
                  if (
                    // Err on the safe side when parsing log entries before the upgrade that introduced the optEndUserParty
                    // annotation in the batch result.
                    actingEndUserParty.isEmpty || actingEndUserParty
                      .contains(endUserParty.toProtoPrimitive)
                  ) {
                    val details = reason match {
                      case r: ITR_InsufficientFunds =>
                        s"ITR_InsufficientFunds: missing ${r.missingAmount} CC"
                      case r: ITR_UnknownSynchronizer =>
                        s"ITR_UnknownSynchronizer: domainId ${r.synchronizerId}"
                      case r: ITR_InsufficientTopupAmount =>
                        s"ITR_InsufficientTopupAmount: requested ${r.requestedTopupAmount}, minimum required ${r.minTopupAmount}"
                      case r: ITR_Other => s"ITR_Other: ${r.description}"
                      case _ => throw new RuntimeException(s"Invalid reason $reason")
                    }
                    // Necessary because COO_Error does not produce any child event
                    val syntheticEventId = s"${root.getEventId}_err_$idx"
                    op match {
                      case _: CO_CompleteAcceptedTransfer =>
                        State.fromNotification(
                          tree,
                          syntheticEventId,
                          TxLogEntry.NotificationTransactionSubtype.DirectTransferFailed,
                          details,
                        )
                      case _: CO_SubscriptionMakePayment =>
                        State.fromNotification(
                          tree,
                          syntheticEventId,
                          TxLogEntry.NotificationTransactionSubtype.SubscriptionPaymentFailed,
                          details,
                        )
                      // The errors below should not produce notifications
                      case _: CO_AppPayment | _: CO_SubscriptionAcceptAndMakeInitialPayment |
                          _: CO_MergeTransferInputs | _: CO_BuyMemberTraffic |
                          _: CO_CompleteBuyTrafficRequest | _: CO_Tap | _: ExtAmuletOperation =>
                        State.empty
                      case _ => throw new RuntimeException(s"Invalid operation $op")
                    }
                  } else {
                    State.empty
                  }
                )
              // Tag wallet automation (amulet merging, reward collection) as such, to distinguish from
              // explicit self-transfers
              case ((_, _: COO_MergeTransferInputs, Right(Seq(childEvent))), _) =>
                defer(parseTree(tree, childEvent, domainId))
                  .map(_.setTransferSubtype(TransferTransactionSubtype.WalletAutomation))
              // All other successful operations are handled by parsing their subtree
              case ((_, _, Right(childEvents)), _) =>
                childEvents.foldMap(parseTree(tree, _, domainId))
              // The above cases should be exhaustive
              case ((op, outcome, child), _) =>
                throw new RuntimeException(
                  s"Impossible combination of $op with $outcome and $child"
                )
            }

          // ------------------------------------------------------------------
          // Transfer Offers
          // TODO (#7153): these are not used for the Transaction History, and would benefit from being split off
          // ------------------------------------------------------------------

          case WalletAppInstall_CreateTransferOffer(ex) =>
            now(State.fromCreateTransferOffer(tree, ex.result.value.transferOffer))

          case TransferOffer_Accept(ex) =>
            now(State.fromTransferOfferAccept(tree, ex.result.value.acceptedTransferOffer))

          case TransferOffer_Reject(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Rejected(TransferOfferStatusRejected()),
                node.result.value.trackingInfo,
              )
            )

          case TransferOffer_Withdraw(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Withdrawn(
                  TransferOfferStatusWithdrawn(node.argument.value.reason)
                ),
                node.result.value.trackingInfo,
              )
            )

          case TransferOffer_Expire(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Expired(TransferOfferStatusExpired()),
                node.result.value.trackingInfo,
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
                node.result.value.trackingInfo,
              )
            )

          case AcceptedTransferOffer_Expire(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Expired(
                  TransferOfferStatusExpired()
                ),
                node.result.value.trackingInfo,
              )
            )

          case AcceptedTransferOffer_Withdraw(node) =>
            now(
              State.fromTransferOfferFailure(
                TransferOfferTxLogEntry.Status.Withdrawn(
                  TransferOfferStatusWithdrawn(node.argument.value.reason)
                ),
                node.result.value.trackingInfo,
              )
            )

          // ------------------------------------------------------------------
          // Buy Traffic Requests
          // ------------------------------------------------------------------

          case WalletAppInstall_CreateBuyTrafficRequest(ex) =>
            now(
              State.fromCreateBuyTrafficRequest(tree, exercised, ex.result.value.buyTrafficRequest)
            )

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
                node.result.value.trackingInfo,
              )
            )

          case BuyTrafficRequest_Expire(node) =>
            now(
              State.fromBuyTrafficRequestFailure(
                BuyTrafficRequestTxLogEntry.Status.Expired(
                  BuyTrafficRequestStatusExpired()
                ),
                node.result.value.trackingInfo,
              )
            )

          // ------------------------------------------------------------------
          // App payments
          // ------------------------------------------------------------------

          // Accepting app payment = locking a amulet for the provider
          case AppPaymentRequest_Accept(_) =>
            defer {
              parseTrees(
                tree,
                exercised.getChildEventIds.asScala.toList,
                domainId,
              )
            }.map(_.setTransferSubtype(TransferTransactionSubtype.AppPaymentAccepted))

          // Collecting app payments = unlocking a locked amulet + transferring the amulet to the provider
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
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amulet,
                BalanceChangeTransactionSubtype.AppPaymentRejected,
              )
            )

          case AcceptedAppPayment_Expire(node) =>
            now(
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amulet,
                BalanceChangeTransactionSubtype.AppPaymentExpired,
              )
            )

          // ------------------------------------------------------------------
          // Subscriptions
          // ------------------------------------------------------------------

          // Accepting subscription = locking a amulet for the provider
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

          // Collecting subscription payments = unlocking a locked amulet + transferring the amulet to the provider
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
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
                BalanceChangeTransactionSubtype.SubscriptionInitialPaymentRejected,
              )
            )

          case SubscriptionInitialPayment_Expire(node) =>
            now(
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
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
                exercised.getEventId,
                NotificationTransactionSubtype.SubscriptionExpired,
                s"Expired by ${node.argument.value.actor} because the last subscription payment was missed",
              )
            )

          // Collecting subscription payments = unlocking a locked amulet + transferring the amulet to the provider
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
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
                BalanceChangeTransactionSubtype.SubscriptionPaymentRejected,
              )
            )

          case SubscriptionPayment_Expire(node) =>
            now(
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
                BalanceChangeTransactionSubtype.SubscriptionPaymentExpired,
              )
            )

          // ------------------------------------------------------------------
          // Other transfers
          // ------------------------------------------------------------------

          case Transfer(node) =>
            // Note: we do not parse the child events, as we can extract all information about the transfer from this node
            now(State.fromTransfer(tree, exercised, node, TransferTransactionSubtype.Transfer))

          // ------------------------------------------------------------------
          // Minting new amulets
          // ------------------------------------------------------------------

          case Tap(node) =>
            now(
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
                BalanceChangeTransactionSubtype.Tap,
              )
            )

          case Mint(node) =>
            now(
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
                BalanceChangeTransactionSubtype.Mint,
              )
            )

          // ------------------------------------------------------------------
          // Unlocking locked amulets
          // ------------------------------------------------------------------

          case LockedAmuletUnlock(node) =>
            now(
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
                BalanceChangeTransactionSubtype.LockedAmuletUnlocked,
              )
            )

          case LockedAmuletOwnerExpireLock(node) =>
            now(
              State.fromAmuletCreateSummary(
                tree,
                root,
                node.result.value.amuletSum,
                BalanceChangeTransactionSubtype.LockedAmuletOwnerExpired,
              )
            )

          // ------------------------------------------------------------------
          // Removing amulets with zero value
          // ------------------------------------------------------------------

          case AmuletExpire(node) =>
            now(
              State.fromAmuletExpire(
                tree,
                exercised,
                node.result.value.expireSum.owner,
                BalanceChangeTransactionSubtype.AmuletExpired,
              )
            )

          case LockedAmuletExpireAmulet(node) =>
            now(
              State.fromAmuletExpire(
                tree,
                exercised,
                node.result.value.expireSum.owner,
                BalanceChangeTransactionSubtype.LockedAmuletExpired,
              )
            )

          // ------------------------------------------------------------------
          // Buying domain traffic
          // ------------------------------------------------------------------

          case AmuletRules_BuyMemberTraffic(node) =>
            now(State.fromBuyMemberTraffic(node, tree, exercised))

          // ------------------------------------------------------------------
          // Amulet name service subscription payment collection
          // ------------------------------------------------------------------

          case AnsRules_CollectInitialEntryPayment(_) =>
            fromAnsEntryPaymentCollection(
              tree,
              exercised,
              domainId,
              TransferTransactionSubtype.InitialEntryPaymentCollection,
              SubscriptionInitialPayment_Collect,
            )(_.amulet)

          case AnsRules_CollectEntryRenewalPayment(_) =>
            fromAnsEntryPaymentCollection(
              tree,
              exercised,
              domainId,
              TransferTransactionSubtype.EntryRenewalPaymentCollection,
              SubscriptionPayment_Collect,
            )(_.amulet)

          // ------------------------------------------------------------------
          // Other
          // ------------------------------------------------------------------

          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that archive amulets.
          case AmuletArchive(_) =>
            throw new RuntimeException(
              s"Unexpected amulet archive event for amulet ${exercised.getContractId} in transaction ${tree.getUpdateId}"
            )

          case _ =>
            defer { parseTrees(tree, exercised.getChildEventIds.asScala.toList, domainId) }
        }

      case created: CreatedEvent =>
        created match {
          // The parser should never reach this leaf event, it should instead make sure to exhaustively match on
          // all possible exercise events that produce new amulets.
          case AmuletCreate(amulet) =>
            throw new RuntimeException(
              s"Unexpected amulet create event for amulet ${amulet.contractId.contractId} in transaction ${tree.getUpdateId}"
            )

          case _ =>
            now(State.empty)
        }

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }
  private def parseTrees(tree: TransactionTree, rootsEventIds: List[String], domainId: DomainId)(
      implicit tc: TraceContext
  ): Eval[State] = {
    val roots = rootsEventIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, _, domainId))
  }

  override def tryParse(tx: TransactionTree, domainId: DomainId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    parseTrees(tx, tx.getRootEventIds.asScala.toList, domainId).value
      .filterByParty(endUserParty)
      .entries
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(UnknownTxLogEntry(eventId))

  private def fromAnsEntryPaymentCollection(
      tree: TransactionTree,
      exercised: ExercisedEvent,
      domainId: DomainId,
      transactionSubtype: TransferTransactionSubtype,
      paymentCollection: ExerciseNodeCompanion,
  )(
      collectionProducedAmulet: paymentCollection.Res => AmuletCreate.TCid
  )(implicit tc: TraceContext): Eval[State] = {
    import Eval.defer
    // child events contain a subscription payment collected by DSO
    val (paymentCollectionEvent, producedAmulet) =
      tree
        .firstDescendantExercise(exercised, paymentCollection.template, paymentCollection.choice)
        .map { case (e, pr) => (e, collectionProducedAmulet(pr)) }
        .getOrElse {
          sys.error(
            s"Unable to find ${paymentCollection.choice.name} in ${exercised.getChoice}"
          )
        }

    defer(
      parseTree(tree, paymentCollectionEvent, domainId).map { stateFromPaymentCollection =>
        State.fromCollectEntryPayment(
          tree,
          exercised,
          producedAmulet,
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
      * This is useful for app payments where the payment collection first unlocks locked amulets and immediately uses
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
      assert(entries.collect { case t: TransferTxLogEntry => t }.length == 1, s"Entries: $entries")

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
    def fromAmuletExpire(
        tx: TransactionTree,
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
        amuletPrice = BigDecimal(0),
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }
    def fromCreateTransferOffer(
        tx: TransactionTree,
        offerCid: transferCodegen.TransferOffer.ContractId,
    ): State = {
      val transferOffer =
        tx.findCreation(transferCodegen.TransferOffer.COMPANION, offerCid)
          .map(_.payload)
          .getOrElse {
            throw new RuntimeException(
              s"Expected transaction to contain a TransferOffer $offerCid"
            )
          }
      fromTransferOfferOperation(
        transferOffer.trackingId,
        TransferOfferTxLogEntry.Status.Created(
          TransferOfferStatusCreated(
            offerCid.contractId,
            tx.getUpdateId,
          )
        ),
        transferOffer.sender,
        transferOffer.receiver,
      )
    }

    def fromTransferOfferAccept(
        tx: TransactionTree,
        acceptedCid: transferCodegen.AcceptedTransferOffer.ContractId,
    ): State = {
      val acceptedTransferOffer =
        tx.findCreation(transferCodegen.AcceptedTransferOffer.COMPANION, acceptedCid)
          .map(_.payload)
          .getOrElse {
            throw new RuntimeException(
              s"Expected transaction to contain an AcceptedTransferOffer $acceptedCid"
            )
          }
      fromTransferOfferOperation(
        acceptedTransferOffer.trackingId,
        TransferOfferTxLogEntry.Status.Accepted(
          TransferOfferStatusAccepted(
            acceptedCid.contractId,
            tx.getUpdateId,
          )
        ),
        acceptedTransferOffer.sender,
        acceptedTransferOffer.receiver,
      )
    }

    def fromTransferOfferFailure(
        failureReason: TransferOfferTxLogEntry.Status,
        trackingInfo: TransferOfferTrackingInfo,
    ): State = {
      fromTransferOfferOperation(
        trackingInfo.trackingId,
        failureReason,
        trackingInfo.sender,
        trackingInfo.receiver,
      )
    }

    def fromTransferOfferComplete(
        tx: TransactionTree,
        node: ExerciseNode[?, AcceptedTransferOffer_Complete.Res],
    ): State = {
      val trackingInfo = node.result.value.trackingInfo
      val receiverAmuletContractId =
        node.result.value.transferResult.createdAmulets.asScala.toList match {
          case (amulet: splice.amuletrules.createdamulet.TransferResultAmulet) :: Nil =>
            amulet.contractIdValue.contractId
          case x =>
            throw new RuntimeException(
              s"Expected createdAmulets to contain a single TransferResultAmulet, but was $x"
            )
        }
      fromTransferOfferOperation(
        trackingInfo.trackingId,
        TransferOfferTxLogEntry.Status.Completed(
          TransferOfferStatusCompleted(
            receiverAmuletContractId,
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
        tx: TransactionTree,
        event: ExercisedEvent,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
        transactionSubtype: TransferTransactionSubtype,
    ): State = {
      val sender = parseSender(node.argument.value, node.result.value)
      val receivers = parseReceivers(node.argument.value, node.result.value)
      val transferEntry =
        TransferTxLogEntry(
          eventId = event.getEventId,
          subtype = Some(transactionSubtype.toProto),
          date = Some(tx.getEffectiveAt),
          provider = node.argument.value.transfer.provider,
          sender = Some(sender),
          receivers = receivers,
          senderHoldingFees = node.result.value.summary.holdingFees,
          amuletPrice = node.result.value.summary.amuletPrice,
          appRewardsUsed = BigDecimal(node.result.value.summary.inputAppRewardAmount),
          validatorRewardsUsed = BigDecimal(node.result.value.summary.inputValidatorRewardAmount),
          svRewardsUsed = Some(BigDecimal(node.result.value.summary.inputSvRewardAmount)),
        )

      State(entries = immutable.Queue[TxLogEntry](transferEntry))
    }

    def fromCreateBuyTrafficRequest(
        tx: TransactionTree,
        event: ExercisedEvent,
        buyTrafficRequestId: trafficRequestCodegen.BuyTrafficRequest.ContractId,
    ): State = {
      val buyTrafficRequest =
        tx.findCreation(trafficRequestCodegen.BuyTrafficRequest.COMPANION, buyTrafficRequestId)
          .map(_.payload)
          .getOrElse {
            throw new RuntimeException(
              s"Expected descendants to contain a BuyTrafficRequest, but were ${tx.preorderDescendants(event).toSeq}"
            )
          }
      fromBuyTrafficRequestOperation(
        buyTrafficRequest.trackingId,
        BuyTrafficRequestTxLogEntry.Status.Created(BuyTrafficRequestStatusCreated()),
        buyTrafficRequest.endUserParty,
      )
    }

    def fromBuyTrafficRequestComplete(
        tx: TransactionTree,
        node: ExerciseNode[?, BuyTrafficRequest_Complete.Res],
    ): State = {
      val trackingInfo = node.result.value.trackingInfo
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
        trackingInfo: BuyTrafficRequestTrackingInfo,
    ): State = {
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
        node: ExerciseNode[AmuletRules_BuyMemberTraffic.Arg, AmuletRules_BuyMemberTraffic.Res],
        tx: TransactionTree,
        event: ExercisedEvent,
    ): State = {
      val sender = node.argument.value.provider
      // Hack to grab the DSO party-id from the balance changes, which list both sender and the DSO
      val receivers = node.result.value.summary.balanceChanges.keySet().asScala.toSeq.collect {
        case party if party != sender => PartyAndAmount(party, BigDecimal(0.0))
      }
      val summary = node.result.value.summary
      val netSenderInput = summary.inputAmuletAmount - summary.holdingFees
      val senderBalanceChange = BigDecimal(summary.senderChangeAmount) - netSenderInput

      val newEntry = TransferTxLogEntry(
        eventId = event.getEventId,
        subtype = Some(TransferTransactionSubtype.ExtraTrafficPurchase.toProto),
        date = Some(tx.getEffectiveAt),
        provider = sender,
        sender = Some(PartyAndAmount(sender, senderBalanceChange)),
        receivers = receivers,
        senderHoldingFees = node.result.value.summary.holdingFees,
        amuletPrice = node.result.value.summary.amuletPrice,
        appRewardsUsed = BigDecimal(node.result.value.summary.inputAppRewardAmount),
        validatorRewardsUsed = BigDecimal(node.result.value.summary.inputValidatorRewardAmount),
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromCollectEntryPayment(
        tx: TransactionTree,
        event: ExercisedEvent,
        producedAmulet: ContractId[AmuletCreate.T],
        stateFromPaymentCollection: State,
        transactionSubtype: TransferTransactionSubtype,
    ): State = {
      // Adjust tx log entries for DSO since the amulet it receives is
      // immediately burnt
      val burntAmulet = getAmuletCreateEvent(tx, producedAmulet)
      val stateFromBurntAmulet = State.fromBurntAmulet(burntAmulet)

      stateFromPaymentCollection
        .appended(stateFromBurntAmulet)
        .mergeBalanceChangesIntoTransfer(transactionSubtype)
        .setEventId(event.getEventId)
    }

    /** State from a choice that returns a `MintSummary`.
      * These are choices that create exactly one new amulet in their transaction subtree.
      */
    def fromAmuletCreateSummary(
        tx: TransactionTree,
        event: TreeEvent,
        acsum: AmuletCreateSummary[_ <: ContractId[AmuletCreate.T]],
        transactionSubtype: BalanceChangeTransactionSubtype,
    ): State = {
      // Note: AmuletCreateSummary only contains the contract id of the new amulet, but not the amulet payload.
      // However, the new amulet is always created in the same transaction.
      // Instead of including the amulet price and owner in AmuletCreateSummary,
      // we locate the corresponding amulet create event in the transaction tree.
      val amuletCid = acsum.amulet
      val amulet = getAmuletCreateEvent(tx, amuletCid)
      val newEntry = BalanceChangeTxLogEntry(
        eventId = event.getEventId,
        subtype = Some(transactionSubtype.toProto),
        date = Some(tx.getEffectiveAt),
        amount = amulet.amount.initialAmount,
        receiver = amulet.owner,
        amuletPrice = acsum.amuletPrice,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromNotification(
        tx: TransactionTree,
        eventId: String,
        transactionSubtype: NotificationTransactionSubtype,
        details: String,
    ): State = {
      val newEntry = NotificationTxLogEntry(
        eventId = eventId,
        subtype = Some(transactionSubtype.toProto),
        date = Some(tx.getEffectiveAt),
        details = details,
      )
      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromAcsAmulet(
        ac: ActiveContract,
        activeAmulet: AmuletCreate.ContractType,
    ): (DomainId, Option[ContractId[?]], TxLogEntry) = {
      (
        ac.domainId,
        Some(new codegen.ContractId(ac.createdEvent.getContractId)),
        BalanceChangeTxLogEntry(
          eventId = ac.createdEvent.getEventId,
          subtype = Some(BalanceChangeTransactionSubtype.Mint.toProto),
          receiver = activeAmulet.payload.owner,
          amount = activeAmulet.payload.amount.initialAmount,
          // We know the round in which the amulet was created (activeAmulet.payload.amount.createdAt),
          // but we don't know when that round was open (let alone when exactly the amulet was created),
          // and what the amulet price was at that time.
          date = Some(Instant.EPOCH),
          amuletPrice = BigDecimal(1),
        ),
      )
    }

    private def fromBurntAmulet(
        burntAmulet: AmuletCreate.T
    ) = State(entries =
      immutable.Queue(
        BalanceChangeTxLogEntry(
          receiver = burntAmulet.owner,
          amount = -burntAmulet.amount.initialAmount,
          // all values below don't matter - they will be removed by mergeBalanceChangesIntoTransfer
          eventId = "",
          subtype = Some(BalanceChangeTransactionSubtype.Tap.toProto),
          date = Some(Instant.now()),
          amuletPrice = BigDecimal(0),
        )
      )
    )

    private def getAmuletCreateEvent(tx: TransactionTree, cid: ContractId[AmuletCreate.T]) =
      tx.findCreation(AmuletCreate.companion, cid)
        .map(_.payload)
        .getOrElse(
          throw new RuntimeException(
            s"The amulet contract $cid was not found in transaction ${tx.getUpdateId}"
          )
        )
  }

  private def parseSender(
      arg: splice.amuletrules.AmuletRules_Transfer,
      res: splice.amuletrules.TransferResult,
  ): PartyAndAmount = {
    val sender = arg.transfer.sender

    // Input amulets, excluding holding fees
    val netInput = res.summary.inputAmuletAmount - res.summary.holdingFees

    // Output amulets going back to the sender, after deducting transfer fees
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
      arg: splice.amuletrules.AmuletRules_Transfer,
      res: splice.amuletrules.TransferResult,
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
      output: splice.amuletrules.TransferOutput,
      senderFee: BigDecimal,
      receiverFee: BigDecimal,
  )

  private def parseOutputAmounts(
      arg: splice.amuletrules.AmuletRules_Transfer,
      res: splice.amuletrules.TransferResult,
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

  private[splice] def splitFirst[A, CC[_], C, Z](
      fa: collection.SeqOps[A, CC, C] & C
  )(p: A PartialFunction Z): (C, Option[(Z, C)]) = {
    val pivot = fa indexWhere p.isDefinedAt
    if (pivot < 0) (fa, None) else (fa take pivot, Some((p(fa(pivot)), fa drop (pivot + 1))))
  }

  /** Returns the input number modified such that it has the same number of decimal places as a daml decimal */
  private def setDamlDecimalScale(x: BigDecimal): BigDecimal =
    x.setScale(10, RoundingMode.HALF_EVEN)

}
