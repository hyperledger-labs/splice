// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AmuletCreateSummary,
  AmuletExpireSummary,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferResult
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequest,
  DsoRules_CloseVoteRequestResult,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.transfercommandresult.{
  TransferCommandResultFailure,
  TransferCommandResultSuccess,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.fees.ExpiringAmount
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as sws
import org.lfdecentralizedtrust.splice.history.*
import org.lfdecentralizedtrust.splice.scan.store.TxLogEntry.*
import org.lfdecentralizedtrust.splice.store.TxLogStore
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.util.SpliceUtil.dollarsToCC
import org.lfdecentralizedtrust.splice.util.TransactionTreeExtensions.*
import org.lfdecentralizedtrust.splice.util.{Codec, EventId, ExerciseNode, LegacyOffset}

import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal

class ScanTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      TxLogEntry
    ]
    with NamedLogging {

  import ScanTxLogParser.*

  private def parseTree(tree: TransactionTree, synchronizerId: SynchronizerId, root: TreeEvent)(
      implicit tc: TraceContext
  ): State = {
    // TODO(#2930) add more checks on the nodes, at least that the DSO party is correct
    root match {
      case exercised: ExercisedEvent =>
        val eventId = EventId.prefixedFromUpdateIdAndNodeId(
          tree.getUpdateId,
          exercised.getNodeId,
        )
        exercised match {
          case Transfer(node) =>
            State.fromTransfer(tree, exercised, synchronizerId, node)
          case Tap(node) =>
            State.fromAmuletCreateSummary(
              tree,
              exercised,
              synchronizerId,
              node.result.value.amuletSum,
              TransactionType.Tap,
            )
          case Mint(node) =>
            State.fromAmuletCreateSummary(
              tree,
              exercised,
              synchronizerId,
              node.result.value.amuletSum,
              TransactionType.Mint,
            )
          case AmuletRules_BuyMemberTraffic(node) =>
            State.fromBuyMemberTraffic(eventId, synchronizerId, node)
          case AmuletRules_CreateExternalPartySetupProposal(node) =>
            State.fromCreateExternalPartySetupProposal(eventId, synchronizerId, node)
          case AmuletRules_CreateTransferPreapproval(node) =>
            State.fromCreateTransferPreapproval(eventId, synchronizerId, node)
          case TransferPreapproval_Renew(node) =>
            State.fromRenewTransferPreapproval(eventId, synchronizerId, node)
          case AmuletExpire(node) =>
            State.fromAmuletExpireSummary(eventId, synchronizerId, node.result.value.expireSum)
          case LockedAmuletExpireAmulet(node) =>
            State.fromAmuletExpireSummary(eventId, synchronizerId, node.result.value.expireSum)
          // We track the sum of locked/unlocked so this is a noop.
          case LockedAmuletUnlock(_) =>
            State.empty
          // We track the sum of locked/unlocked so this is a noop.
          case LockedAmuletOwnerExpireLock(_) =>
            State.empty
          case AnsRules_CollectInitialEntryPayment(_) =>
            fromAnsEntryPaymentCollection(
              tree,
              exercised,
              synchronizerId,
              sws.SubscriptionInitialPayment.COMPANION,
              sws.SubscriptionInitialPayment.CHOICE_SubscriptionInitialPayment_Collect,
            )(_.amulet)
          case AnsRules_CollectEntryRenewalPayment(_) =>
            fromAnsEntryPaymentCollection(
              tree,
              exercised,
              synchronizerId,
              sws.SubscriptionPayment.COMPANION,
              sws.SubscriptionPayment.CHOICE_SubscriptionPayment_Collect,
            )(_.amulet)
          case AmuletArchive(_) =>
            throw new RuntimeException(
              s"Unexpected amulet archive event for amulet ${exercised.getContractId} in transaction ${tree.getUpdateId}"
            )
          case DsoRulesCloseVoteRequest(node) =>
            State.fromCloseVoteRequest(eventId, node)
          case ExternalPartyAmuletRules_CreateTransferCommand(node) =>
            State.fromCreateTransferCommand(eventId, node)
          case TransferCommand_Send(node) =>
            State.fromTransferCommand_Send(eventId, exercised, node)
          case TransferCommand_Withdraw(node) =>
            State.fromTransferCommand_Withdraw(eventId, exercised, node)
          case TransferCommand_Expire(node) =>
            State.fromTransferCommand_Expire(eventId, exercised, node)
          case _ =>
            parseTrees(
              tree,
              synchronizerId,
              tree.getChildNodeIds(exercised).asScala.toList,
            )
        }

      case created: CreatedEvent =>
        created match {
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(
              EventId.prefixedFromUpdateIdAndNodeId(
                tree.getUpdateId,
                root.getNodeId,
              ),
              synchronizerId,
              round,
            )
          case ClosedMiningRoundCreate(round) =>
            State.fromClosedMiningRoundCreate(tree, root, synchronizerId, round)
          case AmuletCreate(_) =>
            throw new RuntimeException(
              s"Unexpected amulet create event for amulet ${created.getContractId} in transaction ${tree.getUpdateId}"
            )
          case LockedAmuletCreate(_) =>
            throw new RuntimeException(
              s"Unexpected locked amulet create event for amulet ${created.getContractId} in transaction ${tree.getUpdateId}"
            )
          case _ => State.empty
        }

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }

  private def parseTrees(
      tree: TransactionTree,
      synchronizerId: SynchronizerId,
      rootsNodeIds: List[Integer],
  )(implicit
      tc: TraceContext
  ): State = {
    val roots = rootsNodeIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, synchronizerId, _))
  }

  override def tryParse(tx: TransactionTree, domain: SynchronizerId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootNodeIds.asScala.toList).entries
    ret
  }

  override def error(
      offset: Long,
      eventId: String,
      synchronizerId: SynchronizerId,
  ): Option[TxLogEntry] =
    Some(
      ErrorTxLogEntry(
        eventId = eventId
      )
    )

  private def fromAnsEntryPaymentCollection[Marker, Res](
      tree: TransactionTree,
      exercised: ExercisedEvent,
      synchronizerId: SynchronizerId,
      paymentCollectionTemplate: codegen.ContractCompanion[?, ?, Marker],
      paymentCollectionChoice: codegen.Choice[Marker, ?, Res],
  )(
      collectionProducedAmulet: Res => AmuletCreate.TCid
  )(implicit tc: TraceContext) = {
    // first child event is the initial subscription payment collected by DSO
    val (paymentCollectionEvent, producedAmulet) =
      tree
        .firstDescendantExercise(exercised, paymentCollectionTemplate, paymentCollectionChoice)
        .map { case (e, pr) => (e, collectionProducedAmulet(pr)) }
        .getOrElse {
          sys.error(
            s"Unable to find ${paymentCollectionChoice.name} in ${exercised.getChoice}"
          )
        }

    val stateFromPaymentCollection = parseTree(tree, synchronizerId, paymentCollectionEvent)
    State.fromCollectEntryPayment(
      tree,
      exercised,
      producedAmulet,
      synchronizerId,
      stateFromPaymentCollection,
    )
  }
}

object ScanTxLogParser {

  private case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )
    private def append(entry: TxLogEntry) = State(entries = entries :+ entry)
  }

  private object State {
    def apply(entry: TxLogEntry): State = {
      State(immutable.Queue(entry))
    }

    def empty: State = State(immutable.Queue.empty)

    implicit val stateMonoid: Monoid[State] = new Monoid[State] {
      override val empty: State = State(immutable.Queue.empty)

      override def combine(a: State, b: State): State =
        a.appended(b)
    }

    private def getAmuletFromSummary(
        tx: TransactionTree,
        ccsum: AmuletCreateSummary[_ <: codegen.ContractId[AmuletCreate.T]],
    ) = {
      val amuletCid = ccsum.amulet
      tx.findCreation(AmuletCreate.companion, amuletCid)
        .map(_.payload)
        .getOrElse {
          throw new RuntimeException(
            s"The amulet contract $amuletCid referenced by AmuletCreateSummary was not found in transaction ${tx.getUpdateId}"
          )
        }
    }

    def fromAmuletCreateSummary(
        tx: TransactionTree,
        event: TreeEvent,
        synchronizerId: SynchronizerId,
        acsum: AmuletCreateSummary[_ <: codegen.ContractId[AmuletCreate.T]],
        activityType: TransactionType,
    ): State = {
      val amulet = getAmuletFromSummary(tx, acsum)
      val eventId = EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, event.getNodeId)
      val activityEntry: TransactionTxLogEntry = activityType match {
        case TransactionType.Tap =>
          TapTxLogEntry(
            offset = LegacyOffset.Api.fromLong(tx.getOffset),
            eventId = eventId,
            domainId = synchronizerId,
            date = Some(tx.getEffectiveAt),
            amuletOwner = PartyId.tryFromProtoPrimitive(amulet.owner),
            amuletAmount = amulet.amount.initialAmount,
            amuletPrice = acsum.amuletPrice,
            round = acsum.round.number,
          )
        case TransactionType.Mint =>
          MintTxLogEntry(
            offset = LegacyOffset.Api.fromLong(tx.getOffset),
            eventId = eventId,
            domainId = synchronizerId,
            date = Some(tx.getEffectiveAt),
            amuletOwner = PartyId.tryFromProtoPrimitive(amulet.owner),
            amuletAmount = amulet.amount.initialAmount,
            amuletPrice = acsum.amuletPrice,
            round = acsum.round.number,
          )
        case unexpected =>
          throw new Exception(
            s"unexpected activityType: $unexpected in fromAmuletCreateSummary"
          )
      }

      State(
        ScanTxLogParser.entryFromAmulet(
          eventId,
          synchronizerId,
          amulet,
        )
      ).append(activityEntry)
    }

    private def rewardsEntriesFromTransferSummary(
        sender: PartyId,
        summary: splice.amuletrules.TransferSummary,
        round: Long,
        synchronizerId: SynchronizerId,
        rootEventId: String,
    ): State = {
      val appRewards = summary.inputAppRewardAmount
      val validatorRewards = summary.inputValidatorRewardAmount
      val svRewards = summary.inputSvRewardAmount

      val appRewardEntry =
        if (appRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            AppRewardTxLogEntry(
              eventId = rootEventId,
              domainId = synchronizerId,
              round = round,
              party = sender,
              amount = appRewards,
            )
          State(entry)
        } else {
          State.empty
        }

      val validatorRewardEntry =
        if (validatorRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            ValidatorRewardTxLogEntry(
              eventId = rootEventId,
              domainId = synchronizerId,
              round = round,
              party = sender,
              amount = validatorRewards,
            )
          State(entry)
        } else {
          State.empty
        }

      val svRewardEntry =
        if (svRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            SvRewardTxLogEntry(
              eventId = rootEventId,
              domainId = synchronizerId,
              round = round,
              party = sender,
              amount = svRewards,
            )
          State(entry)
        } else {
          State.empty
        }

      appRewardEntry.appended(validatorRewardEntry).appended(svRewardEntry)
    }

    def fromTransfer(
        tx: TransactionTree,
        event: ExercisedEvent,
        synchronizerId: SynchronizerId,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
        rootEventId: Option[String] = None,
    ): State = {
      val sender = Codec
        .decode(Codec.Party)(node.argument.value.transfer.sender)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"Cannot decode party ID ${node.argument.value.transfer.sender}")
            .asRuntimeException()
        )
      val round = node.result.value.round
      val eventId = EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, event.getNodeId)
      val rewardEntries =
        rewardsEntriesFromTransferSummary(
          sender,
          node.result.value.summary,
          round.number,
          synchronizerId,
          rootEventId.getOrElse(eventId),
        )

      val balanceChangeEntry = State(
        BalanceChangeTxLogEntry(
          eventId = rootEventId.getOrElse(eventId),
          domainId = synchronizerId,
          round = round.number,
          changeToInitialAmountAsOfRoundZero =
            node.result.value.summary.balanceChanges.values.asScala
              .map(bc => BigDecimal(bc.changeToInitialAmountAsOfRoundZero))
              .sum,
          changeToHoldingFeesRate = node.result.value.summary.balanceChanges.values.asScala
            .map(bc => BigDecimal(bc.changeToHoldingFeesRate))
            .sum,
          partyBalanceChanges =
            node.result.value.summary.balanceChanges.asScala.toMap.map { case (party, bc) =>
              PartyId.tryFromProtoPrimitive(party) -> PartyBalanceChange(
                bc.changeToInitialAmountAsOfRoundZero,
                bc.changeToHoldingFeesRate,
              )
            },
        )
      )

      val activityEntry = State(
        transferTxLogEntry(tx, event, synchronizerId, node)
      )

      rewardEntries
        .appended(balanceChangeEntry)
        .appended(activityEntry)
    }

    private def transferTxLogEntry(
        tx: TransactionTree,
        event: TreeEvent,
        synchronizerId: SynchronizerId,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
    ): TransferTxLogEntry = {
      val amuletPrice = node.result.value.summary.amuletPrice
      val sender = parseSenderAmount(node.argument.value, node.result.value)
      val receivers = parseReceiverAmounts(node.argument.value, node.result.value)

      new TransferTxLogEntry(
        offset = LegacyOffset.Api.fromLong(tx.getOffset),
        eventId = EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, event.getNodeId),
        domainId = synchronizerId,
        date = Some(tx.getEffectiveAt),
        provider = PartyId.tryFromProtoPrimitive(node.argument.value.transfer.provider),
        sender = Some(sender),
        receivers = receivers,
        balanceChanges = node.result.value.summary.balanceChanges.asScala
          .map { case (party, bc) =>
            BalanceChange(
              party = PartyId.tryFromProtoPrimitive(party),
              changeToInitialAmountAsOfRoundZero = bc.changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate = bc.changeToHoldingFeesRate,
            )
          }
          .toSeq
          .sortBy(_.party),
        round = node.result.value.round.number,
        amuletPrice = amuletPrice,
      )
    }

    def fromAmuletExpireSummary(
        eventId: String,
        synchronizerId: SynchronizerId,
        cxsum: AmuletExpireSummary,
    ): State = {
      State(
        BalanceChangeTxLogEntry(
          eventId = eventId,
          domainId = synchronizerId,
          round = cxsum.round.number,
          changeToInitialAmountAsOfRoundZero = cxsum.changeToInitialAmountAsOfRoundZero,
          changeToHoldingFeesRate = cxsum.changeToHoldingFeesRate,
          partyBalanceChanges = Map(
            PartyId.tryFromProtoPrimitive(cxsum.owner) -> PartyBalanceChange(
              cxsum.changeToInitialAmountAsOfRoundZero,
              cxsum.changeToHoldingFeesRate,
            )
          ),
        )
      )
    }

    def fromBuyMemberTraffic(
        eventId: String,
        synchronizerId: SynchronizerId,
        node: ExerciseNode[AmuletRules_BuyMemberTraffic.Arg, AmuletRules_BuyMemberTraffic.Res],
    ): State = {
      val validatorParty = Codec
        .decode(Codec.Party)(node.argument.value.provider)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(
              s"Cannot decode party ID ${node.argument.value.provider}"
            )
            .asRuntimeException()
        )
      val round = node.result.value.round
      val trafficPurchased = node.argument.value.trafficAmount
      val ccSpent = node.result.value.amuletPaid
      val buyExtraTrafficEntry = ExtraTrafficPurchaseTxLogEntry(
        eventId = eventId,
        domainId = synchronizerId,
        round = round.number,
        validator = validatorParty,
        trafficPurchased = trafficPurchased,
        ccSpent = ccSpent,
      )

      val balanceChangeEntry = State(
        BalanceChangeTxLogEntry(
          eventId = eventId,
          domainId = synchronizerId,
          round = round.number,
          changeToInitialAmountAsOfRoundZero =
            node.result.value.summary.balanceChanges.values.asScala
              .map(bc => BigDecimal(bc.changeToInitialAmountAsOfRoundZero))
              .sum,
          changeToHoldingFeesRate = node.result.value.summary.balanceChanges.values.asScala
            .map(bc => BigDecimal(bc.changeToHoldingFeesRate))
            .sum,
          partyBalanceChanges = node.result.value.summary.balanceChanges.asScala.collect {
            // filter out the change from the transfer to the DSO party
            case (party, bc) if party == validatorParty.toProtoPrimitive =>
              validatorParty -> PartyBalanceChange(
                bc.changeToInitialAmountAsOfRoundZero,
                bc.changeToHoldingFeesRate,
              )
          }.toMap,
        )
      )

      val rewardEntries = rewardsEntriesFromTransferSummary(
        validatorParty,
        node.result.value.summary,
        round.number,
        synchronizerId,
        eventId,
      )

      State(buyExtraTrafficEntry)
        .appended(rewardEntries)
        // append the balance change entry from burning the transferred amulet
        .appended(balanceChangeEntry)
    }

    def fromCreateExternalPartySetupProposal(
        eventId: String,
        synchronizerId: SynchronizerId,
        node: ExerciseNode[
          AmuletRules_CreateExternalPartySetupProposal.Arg,
          AmuletRules_CreateExternalPartySetupProposal.Res,
        ],
    ): State = {
      val validatorParty = Codec
        .decode(Codec.Party)(node.result.value.validator)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(
              s"Cannot decode party ID ${node.argument.value.validator}"
            )
            .asRuntimeException()
        )
      val transferResult = node.result.value.transferResult
      fromTransferPreapprovalPurchase(
        eventId,
        synchronizerId,
        validatorParty,
        transferResult,
      )
    }

    def fromCreateTransferPreapproval(
        eventId: String,
        synchronizerId: SynchronizerId,
        node: ExerciseNode[
          AmuletRules_CreateTransferPreapproval.Arg,
          AmuletRules_CreateTransferPreapproval.Res,
        ],
    ): State = {
      val validatorParty = Codec
        .decode(Codec.Party)(node.argument.value.provider)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(
              s"Cannot decode party ID ${node.argument.value.provider}"
            )
            .asRuntimeException()
        )
      val transferResult = node.result.value.transferResult
      fromTransferPreapprovalPurchase(
        eventId,
        synchronizerId,
        validatorParty,
        transferResult,
      )
    }

    def fromRenewTransferPreapproval(
        eventId: String,
        synchronizerId: SynchronizerId,
        node: ExerciseNode[
          TransferPreapproval_Renew.Arg,
          TransferPreapproval_Renew.Res,
        ],
    ): State = {
      val validatorParty = Codec
        .decode(Codec.Party)(node.result.value.provider)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(
              s"Cannot decode party ID ${node.result.value.provider}"
            )
            .asRuntimeException()
        )
      val transferResult = node.result.value.transferResult
      fromTransferPreapprovalPurchase(
        eventId,
        synchronizerId,
        validatorParty,
        transferResult,
      )
    }

    private def fromTransferPreapprovalPurchase(
        eventId: String,
        synchronizerId: SynchronizerId,
        validatorParty: PartyId,
        transferResult: TransferResult,
    ) = {
      val round = transferResult.round
      val balanceChangeEntry = State(
        BalanceChangeTxLogEntry(
          eventId = eventId,
          domainId = synchronizerId,
          round = round.number,
          changeToInitialAmountAsOfRoundZero = transferResult.summary.balanceChanges.values.asScala
            .map(bc => BigDecimal(bc.changeToInitialAmountAsOfRoundZero))
            .sum,
          changeToHoldingFeesRate = transferResult.summary.balanceChanges.values.asScala
            .map(bc => BigDecimal(bc.changeToHoldingFeesRate))
            .sum,
          partyBalanceChanges = transferResult.summary.balanceChanges.asScala.map {
            case (party, bc) if party == validatorParty.toProtoPrimitive =>
              validatorParty -> PartyBalanceChange(
                bc.changeToInitialAmountAsOfRoundZero,
                bc.changeToHoldingFeesRate,
              )
            case (party, bc) =>
              throw Status.INTERNAL
                .withDescription(
                  s"Balance change of $bc for non-validator party $party detected as part of CreateTransferPreapproval"
                )
                .asRuntimeException()
          }.toMap,
        )
      )

      val rewardEntries = rewardsEntriesFromTransferSummary(
        validatorParty,
        transferResult.summary,
        round.number,
        synchronizerId,
        eventId,
      )

      balanceChangeEntry.appended(rewardEntries)
    }

    def fromCollectEntryPayment(
        tx: TransactionTree,
        event: ExercisedEvent,
        producedAmulet: codegen.ContractId[AmuletCreate.T],
        synchronizerId: SynchronizerId,
        stateFromPaymentCollection: State,
    ): State = {
      val amuletArchiveEvent = tx
        .findArchive(event, producedAmulet, splice.amulet.Amulet.CHOICE_Archive)
        .getOrElse(sys.error(s"No archive of $producedAmulet in ${tx.getUpdateId}"))
      // Adjust tx log entries for DSO since the amulet it receives is immediately burnt
      val stateFromBurntAmulet =
        State.fromAmuletArchiveEvent(
          tx,
          amuletArchiveEvent,
          producedAmulet,
          synchronizerId,
          EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, event.getNodeId),
        )
      stateFromPaymentCollection.appended(stateFromBurntAmulet)
    }

    private def fromAmuletArchiveEvent(
        tx: TransactionTree,
        event: TreeEvent,
        producedAmulet: codegen.ContractId[AmuletCreate.T],
        synchronizerId: SynchronizerId,
        rootEventId: String,
    ): State = {
      val burntAmulet = tx
        .findCreation(AmuletCreate.companion, producedAmulet)
        .map(_.payload)
        .getOrElse(
          throw new RuntimeException(
            s"The amulet contract ${event.getContractId} " +
              s"referenced by the amulet archive event ${EventId
                  .prefixedFromUpdateIdAndNodeId(tx.getUpdateId, event.getNodeId)} " +
              s"was not found in transaction ${tx.getUpdateId}"
          )
        )
      // negative value for both initial amount and holding fee so that the total balance can be calculated correctly
      val amountAO0 = -amountAsOfRoundZero(burntAmulet.amount)
      val holdingFees = -burntAmulet.amount.ratePerRound.rate
      State(
        BalanceChangeTxLogEntry(
          eventId = rootEventId,
          domainId = synchronizerId,
          round = burntAmulet.amount.createdAt.number,
          changeToInitialAmountAsOfRoundZero = amountAO0,
          changeToHoldingFeesRate = holdingFees,
          partyBalanceChanges = Map(
            PartyId.tryFromProtoPrimitive(burntAmulet.owner) -> PartyBalanceChange(
              amountAO0,
              holdingFees,
            )
          ),
        )
      )
    }

    def fromOpenMiningRoundCreate(
        eventId: String,
        synchronizerId: SynchronizerId,
        round: OpenMiningRoundCreate.ContractType,
    ): State = {
      val config = round.payload.transferConfigUsd
      val amuletPrice = round.payload.amuletPrice
      val newEntry = OpenMiningRoundTxLogEntry(
        eventId = eventId,
        domainId = synchronizerId,
        round = round.payload.round.number,
        amuletCreateFee = dollarsToCC(config.createFee.fee, amuletPrice),
        holdingFee = dollarsToCC(config.holdingFee.rate, amuletPrice),
        lockHolderFee = dollarsToCC(config.lockHolderFee.fee, amuletPrice),
        transferFee = Some(
          SteppedRate(
            initialRate = config.transferFee.initialRate,
            steps = config.transferFee.steps.asScala.toSeq
              .map(step =>
                SteppedRate.Step(
                  from = dollarsToCC(step._1, amuletPrice),
                  rate = step._2,
                )
              ),
          )
        ),
      )

      State(newEntry)
    }

    def fromClosedMiningRoundCreate(
        tx: TransactionTree,
        event: TreeEvent,
        synchronizerId: SynchronizerId,
        round: ClosedMiningRoundCreate.ContractType,
    ): State = {
      val newEntry = ClosedMiningRoundTxLogEntry(
        eventId = EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, event.getNodeId),
        domainId = synchronizerId,
        round = round.payload.round.number,
        effectiveAt = Some(tx.getEffectiveAt),
      )

      State(newEntry)
    }

    def fromCloseVoteRequest(
        eventId: String,
        node: ExerciseNode[DsoRules_CloseVoteRequest, DsoRules_CloseVoteRequestResult],
    ): State = {
      State(
        immutable.Queue(
          VoteRequestTxLogEntry(
            eventId,
            result = Some(node.result.value),
          )
        )
      )
    }

    def fromCreateTransferCommand(
        eventId: String,
        node: ExerciseNode[
          ExternalPartyAmuletRules_CreateTransferCommand.Arg,
          ExternalPartyAmuletRules_CreateTransferCommand.Res,
        ],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId,
            contractId = Codec.encodeContractId(node.result.value.transferCommandCid),
            sender = PartyId.tryFromProtoPrimitive(node.argument.value.sender),
            nonce = node.argument.value.nonce,
            status = TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
        )
      )
    }

    def fromTransferCommand_Send(
        eventId: String,
        exercised: ExercisedEvent,
        node: ExerciseNode[TransferCommand_Send.Arg, TransferCommand_Send.Res],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId = eventId,
            contractId = exercised.getContractId,
            sender = PartyId.tryFromProtoPrimitive(node.result.value.sender),
            nonce = node.result.value.nonce,
            status = node.result.value.result match {
              case failure: TransferCommandResultFailure =>
                TransferCommandTxLogEntry.Status.Failed(
                  TransferCommandFailed(failure.reason.toString)
                )
              case _: TransferCommandResultSuccess =>
                TransferCommandTxLogEntry.Status.Sent(TransferCommandSent())
              case e =>
                sys.error(s"TransferCommandResult must be either failure or success but got: $e")
            },
          )
        )
      )
    }

    def fromTransferCommand_Withdraw(
        eventId: String,
        exercised: ExercisedEvent,
        node: ExerciseNode[TransferCommand_Withdraw.Arg, TransferCommand_Withdraw.Res],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId = eventId,
            contractId = exercised.getContractId,
            sender = PartyId.tryFromProtoPrimitive(node.result.value.sender),
            nonce = node.result.value.nonce,
            status = TransferCommandTxLogEntry.Status.Withdrawn(TransferCommandWithdrawn()),
          )
        )
      )
    }

    def fromTransferCommand_Expire(
        eventId: String,
        exercised: ExercisedEvent,
        node: ExerciseNode[TransferCommand_Expire.Arg, TransferCommand_Expire.Res],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId = eventId,
            contractId = exercised.getContractId,
            sender = PartyId.tryFromProtoPrimitive(node.result.value.sender),
            nonce = node.result.value.nonce,
            status = TransferCommandTxLogEntry.Status.Expired(TransferCommandExpired()),
          )
        )
      )
    }
  }

  private def entryFromAmulet(
      eventId: String,
      synchronizerId: SynchronizerId,
      amulet: splice.amulet.Amulet,
  ): TxLogEntry = {
    val amount = amulet.amount
    val amountAO0 = amountAsOfRoundZero(amount)
    BalanceChangeTxLogEntry(
      eventId = eventId,
      domainId = synchronizerId,
      round = amount.createdAt.number,
      changeToInitialAmountAsOfRoundZero = amountAO0,
      changeToHoldingFeesRate = amount.ratePerRound.rate,
      partyBalanceChanges = Map(
        PartyId.tryFromProtoPrimitive(amulet.owner) -> PartyBalanceChange(
          amountAO0,
          amount.ratePerRound.rate,
        )
      ),
    )
  }

  private def amountAsOfRoundZero(amount: ExpiringAmount) =
    amount.initialAmount + amount.ratePerRound.rate * BigDecimal(amount.createdAt.number)
}
