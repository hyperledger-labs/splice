package com.daml.network.scan.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.{TreeEvent, *}
import com.daml.network.codegen.java.cc.coin.{CoinCreateSummary, CoinExpireSummary}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.fees.ExpiringAmount
import com.daml.network.history.*
import com.daml.network.store.TxLogStore
import com.daml.network.scan.store.TxLogEntry.*
import com.daml.network.util.{Codec, ExerciseNode}
import com.daml.network.util.CNNodeUtil.dollarsToCC
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.daml.network.environment.ledger.api.ActiveContract
import com.daml.network.environment.ledger.api.IncompleteReassignmentEvent
import com.digitalasset.canton.topology.DomainId

class ScanTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      TxLogEntry
    ]
    with NamedLogging {

  import ScanTxLogParser.*

  private def parseTree(tree: TransactionTreeV2, domainId: DomainId, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {
    // TODO(#2930) add more checks on the nodes, at least that the SVC party is correct
    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case Transfer(node) =>
            State.fromTransfer(tree, root, domainId, node)
          case Tap(node) =>
            State.fromCoinCreateSummary(
              tree,
              exercised,
              domainId,
              node.result.value,
              TransactionType.Tap,
            )
          case Mint(node) =>
            State.fromCoinCreateSummary(
              tree,
              exercised,
              domainId,
              node.result.value,
              TransactionType.Mint,
            )
          case SvcRules_CollectSvReward(node) =>
            State.fromCoinCreateSummary(
              tree,
              exercised,
              domainId,
              node.result.value,
              TransactionType.SvRewardCollected,
            )
          case ImportCrate_Receive(_) =>
            State.empty
          case CoinRules_BuyMemberTraffic(node) =>
            State.fromBuyMemberTraffic(exercised, domainId, node)
          case CoinExpire(node) =>
            State.fromCoinExpireSummary(exercised, domainId, node.result.value)
          case LockedCoinExpireCoin(node) =>
            State.fromCoinExpireSummary(exercised, domainId, node.result.value)
          case LockedCoinUnlock(_) =>
            State.empty
          case CnsRules_CollectInitialEntryPayment(_) =>
            fromCnsEntryPaymentCollection(tree, exercised, domainId)
          case CnsRules_CollectEntryRenewalPayment(_) =>
            fromCnsEntryPaymentCollection(tree, exercised, domainId)
          case CoinArchive(_) =>
            throw new RuntimeException(
              s"Unexpected coin archive event for coin ${exercised.getContractId} in transaction ${tree.getUpdateId}"
            )
          case _ => parseTrees(tree, domainId, exercised.getChildEventIds.asScala.toList)
        }

      case created: CreatedEvent =>
        created match {
          case CoinImportCrate(coin) =>
            State.fromCoinImportCrate(root, domainId, coin)
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(root, domainId, round)
          case ClosedMiningRoundCreate(round) =>
            State.fromClosedMiningRoundCreate(tree, root, domainId, round)
          case CoinCreate(coin) =>
            throw new RuntimeException(
              s"Unexpected coin create event for coin ${coin.contractId.contractId} in transaction ${tree.getUpdateId}"
            )
          case _ => State.empty
        }

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }

  private def parseTrees(tree: TransactionTreeV2, domainId: DomainId, rootsEventIds: List[String])(
      implicit tc: TraceContext
  ): State = {
    val roots = rootsEventIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, domainId, _))
  }

  // TODO(#4906): handle in-flight contracts when we tackle global domain migration
  override def parseAcs(
      acs: Seq[ActiveContract],
      incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
      incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
  )(implicit
      tc: TraceContext
  ) = acs.flatMap { ac =>
    // This is necessary because the CreatedEvents from the ACS might have the same eventId for different contract ids.
    // So either:
    // TODO (#6882): Canton fixes this, so we can just assign the eventId
    // TODO (#8132): We end up using PQS, redesign the txlog taking this issue into account, or something else.
    val eventId = ac.createdEvent.getContractId
    ac.createdEvent match {
      case CoinCreate(c) =>
        val contractId = new cc.coin.Coin.ContractId(ac.createdEvent.getContractId)
        Some(
          (
            ac.domainId,
            Some(contractId),
            entryFromCoin(eventId, ac.domainId, c.payload),
          )
        )
      case LockedCoinCreate(lc) =>
        val contractId = new cc.coin.LockedCoin.ContractId(ac.createdEvent.getContractId)
        Some(
          (
            ac.domainId,
            Some(contractId),
            entryFromCoin(eventId, ac.domainId, lc.payload.coin),
          )
        )
      case CoinImportCrate(ic) =>
        val contractId = new cc.coinimport.ImportCrate.ContractId(ac.createdEvent.getContractId)
        Some(
          (
            ac.domainId,
            Some(contractId),
            entryFromCoin(eventId, ac.domainId, ic),
          )
        )
      case _ =>
        None
    }
  }

  override def tryParse(tx: TransactionTreeV2, domain: DomainId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootEventIds.asScala.toList).entries
    ret
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(
      ErrorLogEntry(
        eventId = eventId
      )
    )

  private def fromCnsEntryPaymentCollection(
      tree: TransactionTreeV2,
      exercised: ExercisedEvent,
      domainId: DomainId,
  )(implicit tc: TraceContext) = {
    // first child event is the initial subscription payment collected by SVC
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

  import BalanceChangeLogEntry.PartyBalanceChange

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

    def fromCoinImportCrate(
        event: TreeEvent,
        domainId: DomainId,
        coin: cc.coin.Coin,
    ): State = {
      val amountAO0 = amountAsOfRoundZero(coin.amount)
      State(
        BalanceChangeLogEntry(
          eventId = event.getEventId(),
          domainId = domainId,
          round = coin.amount.createdAt.number,
          changeToInitialAmountAsOfRoundZero = amountAO0,
          changeToHoldingFeesRate = coin.amount.ratePerRound.rate,
          partyBalanceChanges = Map(
            PartyId.tryFromProtoPrimitive(coin.owner) -> PartyBalanceChange(
              amountAO0,
              coin.amount.ratePerRound.rate,
            )
          ),
        )
      )
    }

    private def getCoinFromSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTreeV2,
        ccsum: CoinCreateSummary[T],
    ) = {
      val coinCid = ccsum.coin.contractId
      tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == coinCid => {
            CoinCreate.unapply(c).map(_.payload)
          }
        }
        .flatten
        .getOrElse {
          throw new RuntimeException(
            s"The coin contract $coinCid referenced by CoinCreateSummary was not found in transaction ${tx.getUpdateId}"
          )
        }
    }

    def fromCoinCreateSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTreeV2,
        event: TreeEvent,
        domainId: DomainId,
        ccsum: CoinCreateSummary[T],
        activityType: TransactionType,
    ): State = {
      val coin = getCoinFromSummary(tx, ccsum)
      val activityEntry = activityType match {
        case TransactionType.Tap =>
          TapLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
            date = tx.getEffectiveAt,
            coinOwner = coin.owner,
            coinAmount = coin.amount.initialAmount,
            coinPrice = ccsum.coinPrice,
            round = ccsum.round.number,
          )
        case TransactionType.Mint =>
          MintLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
            date = tx.getEffectiveAt,
            coinOwner = coin.owner,
            coinAmount = coin.amount.initialAmount,
            coinPrice = ccsum.coinPrice,
            round = ccsum.round.number,
          )
        case TransactionType.SvRewardCollected =>
          SvRewardCollectedLogEntry(
            offset = tx.getOffset,
            eventId = event.getEventId,
            domainId = domainId,
            date = tx.getEffectiveAt,
            coinOwner = coin.owner,
            coinAmount = coin.amount.initialAmount,
            coinPrice = ccsum.coinPrice,
            round = ccsum.round.number,
          )
        case unexpected =>
          throw new Exception(
            s"unexpected activityType: $unexpected in fromCoinCreateSummary"
          )
      }

      State(
        ScanTxLogParser.entryFromCoin(
          event.getEventId(),
          domainId,
          coin,
        )
      ).append(activityEntry)
    }

    def rewardsEntriesFromTransferSummary(
        sender: PartyId,
        summary: cc.coinrules.TransferSummary,
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
            AppRewardLogEntry(
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
            ValidatorRewardLogEntry(
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
        tx: TransactionTreeV2,
        event: TreeEvent,
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
        BalanceChangeLogEntry(
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
        TransferLogEntry(tx, event, domainId, node)
      )
      rewardEntries
        .appended(balanceChangeEntry)
        .appended(activityEntry)
    }

    def fromCoinExpireSummary(
        event: TreeEvent,
        domainId: DomainId,
        cxsum: CoinExpireSummary,
    ): State = {
      State(
        BalanceChangeLogEntry(
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
        node: ExerciseNode[CoinRules_BuyMemberTraffic.Arg, CoinRules_BuyMemberTraffic.Res],
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
      val buyExtraTrafficEntry = ExtraTrafficPurchaseLogEntry(
        eventId = event.getEventId(),
        domainId = domainId,
        round = round.number,
        validator = validatorParty,
        trafficPurchased = trafficPurchased,
        ccSpent = ccSpent,
      )

      val balanceChangeEntry = State(
        BalanceChangeLogEntry(
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
            // filter out the change from the transfer to the SVC party
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
        // append the balance change entry from burning the transferred coin
        .appended(balanceChangeEntry)
    }

    def fromCollectEntryPayment(
        tx: TransactionTreeV2,
        event: ExercisedEvent,
        domainId: DomainId,
        stateFromPaymentCollection: State,
    ): State = {
      // second child event is burning of transferred coin by SVC
      val coinArchiveEvent = tx.getEventsById.get(event.getChildEventIds.get(1))
      // Adjust tx log entries for SVC since the coin it receives is immediately burnt
      val stateFromBurntCoin =
        State.fromCoinArchiveEvent(tx, coinArchiveEvent, domainId, Some(event.getEventId()))
      stateFromPaymentCollection.appended(stateFromBurntCoin)
    }

    def fromCoinArchiveEvent(
        tx: TransactionTreeV2,
        event: TreeEvent,
        domainId: DomainId,
        rootEventId: Option[String] = None,
    ): State = {
      val burntCoin = tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == event.getContractId =>
            CoinCreate.unapply(c).map(_.payload)
        }
        .flatten
        .getOrElse(
          throw new RuntimeException(
            s"The coin contract ${event.getContractId} " +
              s"referenced by the coin archive event ${event.getEventId} " +
              s"was not found in transaction ${tx.getUpdateId}"
          )
        )
      // negative value for both initial amount and holding fee so that the total balance can be calculated correctly
      val amountAO0 = -amountAsOfRoundZero(burntCoin.amount)
      val holdingFees = -burntCoin.amount.ratePerRound.rate
      State(
        BalanceChangeLogEntry(
          eventId = rootEventId.getOrElse(event.getEventId()),
          domainId = domainId,
          round = burntCoin.amount.createdAt.number,
          changeToInitialAmountAsOfRoundZero = amountAO0,
          changeToHoldingFeesRate = holdingFees,
          partyBalanceChanges = Map(
            PartyId.tryFromProtoPrimitive(burntCoin.owner) -> PartyBalanceChange(
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
      val coinPrice = round.payload.coinPrice
      val newEntry = OpenMiningRoundLogEntry(
        eventId = event.getEventId(),
        domainId = domainId,
        round = round.payload.round.number,
        coinCreateFee = dollarsToCC(config.createFee.fee, coinPrice),
        holdingFee = dollarsToCC(config.holdingFee.rate, coinPrice),
        lockHolderFee = dollarsToCC(config.lockHolderFee.fee, coinPrice),
        initialTransferFee = config.transferFee.initialRate,
        transferFeeSteps = config.transferFee.steps.asScala.toSeq.map(step =>
          (dollarsToCC(step._1, coinPrice), step._2)
        ),
      )

      State(newEntry)
    }

    def fromClosedMiningRoundCreate(
        tx: TransactionTreeV2,
        event: TreeEvent,
        domainId: DomainId,
        round: ClosedMiningRoundCreate.ContractType,
    ): State = {
      val newEntry = ClosedMiningRoundLogEntry(
        eventId = event.getEventId(),
        domainId = domainId,
        round = round.payload.round.number,
        effectiveAt = tx.getEffectiveAt,
      )

      State(newEntry)
    }
  }

  private def entryFromCoin(
      eventId: String,
      domainId: DomainId,
      coin: cc.coin.Coin,
  ): TxLogEntry = {
    val amount = coin.amount
    val amountAO0 = amountAsOfRoundZero(amount)
    BalanceChangeLogEntry(
      eventId = eventId,
      domainId = domainId,
      round = amount.createdAt.number,
      changeToInitialAmountAsOfRoundZero = amountAO0,
      changeToHoldingFeesRate = amount.ratePerRound.rate,
      partyBalanceChanges = Map(
        PartyId.tryFromProtoPrimitive(coin.owner) -> PartyBalanceChange(
          amountAO0,
          amount.ratePerRound.rate,
        )
      ),
    )
  }

  private def amountAsOfRoundZero(amount: ExpiringAmount) =
    amount.initialAmount + amount.ratePerRound.rate * BigDecimal(amount.createdAt.number)
}
