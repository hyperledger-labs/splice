// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.{TreeEvent, *}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AmuletCreateSummary,
  AmuletExpireSummary,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequest,
  DsoRules_CloseVoteRequestResult,
}
import splice.wallet.subscriptions as sws
import org.lfdecentralizedtrust.splice.codegen.java.splice.fees.ExpiringAmount
import org.lfdecentralizedtrust.splice.history.*
import org.lfdecentralizedtrust.splice.store.TxLogStore
import org.lfdecentralizedtrust.splice.scan.store.TxLogEntry.*
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.util.{Codec, ExerciseNode}
import org.lfdecentralizedtrust.splice.util.SpliceUtil.dollarsToCC
import org.lfdecentralizedtrust.splice.util.TransactionTreeExtensions.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.digitalasset.canton.topology.DomainId

class ScanTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      TxLogEntry
    ]
    with NamedLogging {

  import ScanTxLogParser.*

  private def parseTree(tree: TransactionTree, domainId: DomainId, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {
    // TODO(#2930) add more checks on the nodes, at least that the DSO party is correct
    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case Transfer(node) =>
            State.fromTransfer(tree, exercised, domainId, node)
          case Tap(node) =>
            State.fromAmuletCreateSummary(
              tree,
              exercised,
              domainId,
              node.result.value.amuletSum,
              TransactionType.Tap,
            )
          case Mint(node) =>
            State.fromAmuletCreateSummary(
              tree,
              exercised,
              domainId,
              node.result.value.amuletSum,
              TransactionType.Mint,
            )
          case AmuletRules_BuyMemberTraffic(node) =>
            State.fromBuyMemberTraffic(exercised, domainId, node)
          case AmuletExpire(node) =>
            State.fromAmuletExpireSummary(exercised, domainId, node.result.value.expireSum)
          case LockedAmuletExpireAmulet(node) =>
            State.fromAmuletExpireSummary(exercised, domainId, node.result.value.expireSum)
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
              domainId,
              sws.SubscriptionInitialPayment.COMPANION,
              sws.SubscriptionInitialPayment.CHOICE_SubscriptionInitialPayment_Collect,
            )(_.amulet)
          case AnsRules_CollectEntryRenewalPayment(_) =>
            fromAnsEntryPaymentCollection(
              tree,
              exercised,
              domainId,
              sws.SubscriptionPayment.COMPANION,
              sws.SubscriptionPayment.CHOICE_SubscriptionPayment_Collect,
            )(_.amulet)
          case AmuletArchive(_) =>
            throw new RuntimeException(
              s"Unexpected amulet archive event for amulet ${exercised.getContractId} in transaction ${tree.getUpdateId}"
            )
          case DsoRulesCloseVoteRequest(node) =>
            State.fromCloseVoteRequest(exercised.getEventId, node)
          case _ => parseTrees(tree, domainId, exercised.getChildEventIds.asScala.toList)
        }

      case created: CreatedEvent =>
        created match {
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(root, domainId, round)
          case ClosedMiningRoundCreate(round) =>
            State.fromClosedMiningRoundCreate(tree, root, domainId, round)
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

  private def parseTrees(tree: TransactionTree, domainId: DomainId, rootsEventIds: List[String])(
      implicit tc: TraceContext
  ): State = {
    val roots = rootsEventIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, domainId, _))
  }

  override def tryParse(tx: TransactionTree, domain: DomainId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootEventIds.asScala.toList).entries
    ret
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(
      ErrorTxLogEntry(
        eventId = eventId
      )
    )

  private def fromAnsEntryPaymentCollection[Marker, Res](
      tree: TransactionTree,
      exercised: ExercisedEvent,
      domainId: DomainId,
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

    val stateFromPaymentCollection = parseTree(tree, domainId, paymentCollectionEvent)
    State.fromCollectEntryPayment(
      tree,
      exercised,
      producedAmulet,
      domainId,
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
    def append(entry: TxLogEntry) = State(entries = entries :+ entry)
  }

  private object State {
    def apply(entry: TxLogEntry): State = {
      State(immutable.Queue(entry))
    }

    def empty: State = State(immutable.Queue.empty)

    implicit val stateMonoid: Monoid[State] = new Monoid[State] {
      override val empty = State(immutable.Queue.empty)

      override def combine(a: State, b: State) =
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
        domainId: DomainId,
        acsum: AmuletCreateSummary[_ <: codegen.ContractId[AmuletCreate.T]],
        activityType: TransactionType,
    ): State = {
      val amulet = getAmuletFromSummary(tx, acsum)
      val activityEntry: TransactionTxLogEntry = activityType match {
        case TransactionType.Tap =>
          TapTxLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
            date = Some(tx.getEffectiveAt),
            amuletOwner = PartyId.tryFromProtoPrimitive(amulet.owner),
            amuletAmount = amulet.amount.initialAmount,
            amuletPrice = acsum.amuletPrice,
            round = acsum.round.number,
          )
        case TransactionType.Mint =>
          MintTxLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
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
          event.getEventId(),
          domainId,
          amulet,
        )
      ).append(activityEntry)
    }

    def rewardsEntriesFromTransferSummary(
        sender: PartyId,
        summary: splice.amuletrules.TransferSummary,
        event: TreeEvent,
        round: Long,
        domainId: DomainId,
        rootEventId: Option[String] = None,
    ): State = {
      val appRewards = summary.inputAppRewardAmount
      val validatorRewards = summary.inputValidatorRewardAmount
      val svRewards = summary.inputSvRewardAmount

      val appRewardEntry =
        if (appRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            AppRewardTxLogEntry(
              eventId = rootEventId.getOrElse(event.getEventId()),
              domainId = domainId,
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
              eventId = rootEventId.getOrElse(event.getEventId()),
              domainId = domainId,
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
              eventId = rootEventId.getOrElse(event.getEventId()),
              domainId = domainId,
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
        domainId: DomainId,
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
      val rewardEntries =
        rewardsEntriesFromTransferSummary(
          sender,
          node.result.value.summary,
          event,
          round.number,
          domainId,
          rootEventId,
        )

      val balanceChangeEntry = State(
        BalanceChangeTxLogEntry(
          eventId = rootEventId.getOrElse(event.getEventId()),
          domainId = domainId,
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
        transferTxLogEntry(tx, event, domainId, node)
      )

      rewardEntries
        .appended(balanceChangeEntry)
        .appended(activityEntry)
    }

    def transferTxLogEntry(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
    ): TransferTxLogEntry = {
      val amuletPrice = node.result.value.summary.amuletPrice
      val sender = parseSenderAmount(node.argument.value, node.result.value)
      val receivers = parseReceiverAmounts(node.argument.value, node.result.value)

      new TransferTxLogEntry(
        offset = tx.getOffset,
        eventId = event.getEventId,
        domainId = domainId,
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
        event: TreeEvent,
        domainId: DomainId,
        cxsum: AmuletExpireSummary,
    ): State = {
      State(
        BalanceChangeTxLogEntry(
          eventId = event.getEventId(),
          domainId = domainId,
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
        event: ExercisedEvent,
        domainId: DomainId,
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
        eventId = event.getEventId(),
        domainId = domainId,
        round = round.number,
        validator = validatorParty,
        trafficPurchased = trafficPurchased,
        ccSpent = ccSpent,
      )

      val balanceChangeEntry = State(
        BalanceChangeTxLogEntry(
          eventId = event.getEventId(),
          domainId = domainId,
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
        event,
        round.number,
        domainId,
        Some(event.getEventId()),
      )

      State(buyExtraTrafficEntry)
        .appended(rewardEntries)
        // append the balance change entry from burning the transferred amulet
        .appended(balanceChangeEntry)
    }

    def fromCollectEntryPayment(
        tx: TransactionTree,
        event: ExercisedEvent,
        producedAmulet: codegen.ContractId[AmuletCreate.T],
        domainId: DomainId,
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
          domainId,
          Some(event.getEventId()),
        )
      stateFromPaymentCollection.appended(stateFromBurntAmulet)
    }

    def fromAmuletArchiveEvent(
        tx: TransactionTree,
        event: TreeEvent,
        producedAmulet: codegen.ContractId[AmuletCreate.T],
        domainId: DomainId,
        rootEventId: Option[String] = None,
    ): State = {
      val burntAmulet = tx
        .findCreation(AmuletCreate.companion, producedAmulet)
        .map(_.payload)
        .getOrElse(
          throw new RuntimeException(
            s"The amulet contract ${event.getContractId} " +
              s"referenced by the amulet archive event ${event.getEventId} " +
              s"was not found in transaction ${tx.getUpdateId}"
          )
        )
      // negative value for both initial amount and holding fee so that the total balance can be calculated correctly
      val amountAO0 = -amountAsOfRoundZero(burntAmulet.amount)
      val holdingFees = -burntAmulet.amount.ratePerRound.rate
      State(
        BalanceChangeTxLogEntry(
          eventId = rootEventId.getOrElse(event.getEventId()),
          domainId = domainId,
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
        event: TreeEvent,
        domainId: DomainId,
        round: OpenMiningRoundCreate.ContractType,
    ): State = {
      val config = round.payload.transferConfigUsd
      val amuletPrice = round.payload.amuletPrice
      val newEntry = OpenMiningRoundTxLogEntry(
        eventId = event.getEventId(),
        domainId = domainId,
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
        domainId: DomainId,
        round: ClosedMiningRoundCreate.ContractType,
    ): State = {
      val newEntry = ClosedMiningRoundTxLogEntry(
        eventId = event.getEventId(),
        domainId = domainId,
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
  }

  private def entryFromAmulet(
      eventId: String,
      domainId: DomainId,
      amulet: splice.amulet.Amulet,
  ): TxLogEntry = {
    val amount = amulet.amount
    val amountAO0 = amountAsOfRoundZero(amount)
    BalanceChangeTxLogEntry(
      eventId = eventId,
      domainId = domainId,
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
