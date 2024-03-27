package com.daml.network.scan.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.{TreeEvent, *}
import com.daml.network.codegen.java.splice.amulet.{AmuletCreateSummary, AmuletExpireSummary}
import com.daml.network.codegen.java.splice
import com.daml.network.codegen.java.splice.fees.ExpiringAmount
import com.daml.network.history.*
import com.daml.network.store.TxLogStore
import com.daml.network.scan.store.TxLogEntry.*
import com.daml.network.util.{Codec, ExerciseNode}
import com.daml.network.util.CNNodeUtil.dollarsToCC
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.daml.network.store.events.SvRewardCoupon_ArchiveAsBeneficiary
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
              node.result.value,
              TransactionType.Tap,
            )
          case Mint(node) =>
            State.fromAmuletCreateSummary(
              tree,
              exercised,
              domainId,
              node.result.value,
              TransactionType.Mint,
            )
          case AmuletRules_BuyMemberTraffic(node) =>
            State.fromBuyMemberTraffic(exercised, domainId, node)
          case AmuletExpire(node) =>
            State.fromAmuletExpireSummary(exercised, domainId, node.result.value)
          case LockedAmuletExpireAmulet(node) =>
            State.fromAmuletExpireSummary(exercised, domainId, node.result.value)
          case LockedAmuletUnlock(_) =>
            State.empty
          case AnsRules_CollectInitialEntryPayment(_) =>
            fromAnsEntryPaymentCollection(tree, exercised, domainId)
          case AnsRules_CollectEntryRenewalPayment(_) =>
            fromAnsEntryPaymentCollection(tree, exercised, domainId)
          case AmuletArchive(_) =>
            throw new RuntimeException(
              s"Unexpected amulet archive event for amulet ${exercised.getContractId} in transaction ${tree.getUpdateId}"
            )
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

  private def fromAnsEntryPaymentCollection(
      tree: TransactionTree,
      exercised: ExercisedEvent,
      domainId: DomainId,
  )(implicit tc: TraceContext) = {
    // first child event is the initial subscription payment collected by DSO
    val paymentCollectionEvent =
      tree.getEventsById.get(exercised.getChildEventIds.get(0)) match {
        case e: ExercisedEvent => e
        case e =>
          throw new RuntimeException(
            s"Unable to parse event ${e.getEventId} as ExercisedEvent"
          )
      }

    val stateFromPaymentCollection = parseTree(tree, domainId, paymentCollectionEvent)
    State.fromCollectEntryPayment(
      tree,
      exercised,
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

    private def getAmuletFromSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTree,
        ccsum: AmuletCreateSummary[T],
    ) = {
      val amuletCid = ccsum.amulet.contractId
      tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == amuletCid => {
            AmuletCreate.unapply(c).map(_.payload)
          }
        }
        .flatten
        .getOrElse {
          throw new RuntimeException(
            s"The amulet contract $amuletCid referenced by AmuletCreateSummary was not found in transaction ${tx.getUpdateId}"
          )
        }
    }

    def fromAmuletCreateSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        ccsum: AmuletCreateSummary[T],
        activityType: TransactionType,
    ): State = {
      val amulet = getAmuletFromSummary(tx, ccsum)
      val activityEntry: TransactionTxLogEntry = activityType match {
        case TransactionType.Tap =>
          TapTxLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
            date = Some(tx.getEffectiveAt),
            amuletOwner = PartyId.tryFromProtoPrimitive(amulet.owner),
            amuletAmount = amulet.amount.initialAmount,
            amuletPrice = ccsum.amuletPrice,
            round = ccsum.round.number,
          )
        case TransactionType.Mint =>
          MintTxLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
            date = Some(tx.getEffectiveAt),
            amuletOwner = PartyId.tryFromProtoPrimitive(amulet.owner),
            amuletAmount = amulet.amount.initialAmount,
            amuletPrice = ccsum.amuletPrice,
            round = ccsum.round.number,
          )
        case TransactionType.SvRewardCollected =>
          SvRewardCollectedTxLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
            date = Some(tx.getEffectiveAt),
            amuletOwner = PartyId.tryFromProtoPrimitive(amulet.owner),
            amuletAmount = amulet.amount.initialAmount,
            amuletPrice = ccsum.amuletPrice,
            round = ccsum.round.number,
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

      appRewardEntry.appended(validatorRewardEntry)
    }

    def fromTransfer(
        tx: TransactionTree,
        event: ExercisedEvent,
        domainId: DomainId,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
        rootEventId: Option[String] = None,
    )(implicit elc: ErrorLoggingContext): State = {
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

      val stateWithoutSvRewards = rewardEntries
        .appended(balanceChangeEntry)
        .appended(activityEntry)

      // Workaround:
      // We want to have separate logs between the transfer and the SV reward collection.
      // So we need to extract a separate event id for pagination of `listTransactions` to work.
      // Note that this lumps together all the rewards from different SV operators and rounds together.
      // This is a current limitation that might be revisited later.
      val archiveSvRewardEventIdOpt = event.getChildEventIds.asScala
        .find { eventId =>
          tx.getEventsById.get(eventId) match {
            case SvRewardCoupon_ArchiveAsBeneficiary(_) => true
            case _ => false
          }
        }

      archiveSvRewardEventIdOpt match {
        case Some(archiveSvRewardEventId) =>
          val svRewardEntry = State(
            SvRewardCollectedTxLogEntry(
              offset = tx.getOffset,
              eventId = archiveSvRewardEventId,
              domainId = domainId,
              date = Some(tx.getEffectiveAt),
              amuletOwner = sender,
              amuletAmount = node.result.value.summary.inputSvRewardAmount,
              amuletPrice = node.result.value.summary.amuletPrice,
              round = round.number, // Of the collection, not the round the coupon is received.
            )
          )
          stateWithoutSvRewards.appended(svRewardEntry)
        case None =>
          stateWithoutSvRewards
      }
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
      val ccSpent = node.result.value.ccPaid
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
        domainId: DomainId,
        stateFromPaymentCollection: State,
    ): State = {
      // second child event is burning of transferred amulet by DSO
      val amuletArchiveEvent = tx.getEventsById.get(event.getChildEventIds.get(1))
      // Adjust tx log entries for DSO since the amulet it receives is immediately burnt
      val stateFromBurntAmulet =
        State.fromAmuletArchiveEvent(tx, amuletArchiveEvent, domainId, Some(event.getEventId()))
      stateFromPaymentCollection.appended(stateFromBurntAmulet)
    }

    def fromAmuletArchiveEvent(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        rootEventId: Option[String] = None,
    ): State = {
      val burntAmulet = tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == event.getContractId =>
            AmuletCreate.unapply(c).map(_.payload)
        }
        .flatten
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
