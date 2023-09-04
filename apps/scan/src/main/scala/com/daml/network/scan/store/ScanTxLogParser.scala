package com.daml.network.scan.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.{TreeEvent, *}
import com.daml.network.codegen.java.cc.api.v1.coin.{CoinCreateSummary, CoinExpireSummary}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.fees.ExpiringAmount
import com.daml.network.history.*
import com.daml.network.store.TxLogStore
import com.daml.network.util.{Codec, ExerciseNode}
import com.daml.network.util.CNNodeUtil.dollarsToCC
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import java.time.Instant
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.daml.network.environment.ledger.api.ActiveContract
import com.daml.network.environment.ledger.api.IncompleteReassignmentEvent
import com.daml.network.http.v0.definitions as httpDef
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.DomainId

class ScanTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      ScanTxLogParser.TxLogIndexRecord,
      ScanTxLogParser.TxLogEntry,
    ]
    with NamedLogging {

  import ScanTxLogParser.*

  private def parseTree(tree: TransactionTree, domainId: DomainId, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {
    // TODO(#2930) add more checks on the nodes, at least that the svc party is correct
    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case Transfer(node) =>
            State.fromTransfer(tree, root, domainId, node)
          case Mint(node) =>
            State.fromCoinCreateSummary(tree, exercised, domainId, node.result.value)
          case ImportCrate_Receive(_) =>
            State.empty
          case CoinRules_BuyMemberTraffic(node) =>
            State.fromBuyMemberTraffic(tree, exercised, domainId, node)
          case CoinExpire(node) =>
            State.fromCoinExpireSummary(tree, exercised, domainId, node.result.value)
          case LockedCoinExpireCoin(node) =>
            State.fromCoinExpireSummary(tree, exercised, domainId, node.result.value)
          case LockedCoinUnlock(_) =>
            State.empty
          case CnsRules_CollectInitialEntryPayment(_) =>
            fromCnsEntryPaymentCollection(tree, exercised, domainId)
          case CnsRules_CollectEntryRenewalPayment(_) =>
            fromCnsEntryPaymentCollection(tree, exercised, domainId)
          case CoinArchive(_) =>
            throw new RuntimeException(
              s"Unexpected coin archive event for coin ${exercised.getContractId} in transaction ${tree.getTransactionId}"
            )
          case _ => parseTrees(tree, domainId, exercised.getChildEventIds.asScala.toList)
        }

      case created: CreatedEvent =>
        created match {
          case CoinImportCrate(coin) =>
            State.fromCoinImportCrate(tree, root, domainId, coin)
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(tree, root, domainId, round)
          case ClosedMiningRoundCreate(round) =>
            State.fromClosedMiningRoundCreate(tree, root, domainId, round)
          case CoinCreate(coin) =>
            throw new RuntimeException(
              s"Unexpected coin create event for coin ${coin.contractId.contractId} in transaction ${tree.getTransactionId}"
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

  // TODO(#4906): handle in-flight contracts when we tackle global domain migration
  override def parseAcs(
      acs: Seq[ActiveContract],
      incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
      incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
  )(implicit
      tc: TraceContext
  ): Seq[(DomainId, ScanTxLogParser.TxLogEntry)] = acs.collect(ac =>
    ac.createdEvent match {
      case CoinCreate(c) =>
        (
          ac.domainId,
          entryFromCoin(None, ac.createdEvent.getEventId, ac.domainId, c.payload.amount),
        )
      case LockedCoinCreate(lc) =>
        (
          ac.domainId,
          entryFromCoin(None, ac.createdEvent.getEventId, ac.domainId, lc.payload.coin.amount),
        )
      case CoinImportCrate(ic) =>
        (ac.domainId, entryFromCoin(None, ac.createdEvent.getEventId, ac.domainId, ic.amount))
    }
  )

  override def tryParse(tx: TransactionTree, domain: DomainId)(implicit
      tc: TraceContext
  ): Seq[ScanTxLogParser.TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootEventIds.asScala.toList).entries
    ret
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(
      TxLogEntry.ErrorTxLogEntry(
        indexRecord = TxLogIndexRecord.ErrorIndexRecord(
          offset,
          eventId,
          domainId,
        )
      )
    )

  private def fromCnsEntryPaymentCollection(
      tree: TransactionTree,
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

  sealed trait TxLogIndexRecord extends TxLogStore.IndexRecord {
    val companion: TxLogIndexRecordCompanion
  }

  sealed trait TxLogIndexRecordCompanion {
    val shortType: String

    def dbType: String3 = String3.tryCreate(shortType)
  }

  object TxLogIndexRecord {

    final case class ErrorIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
    ) extends TxLogIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = ErrorIndexRecord
    }

    object ErrorIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "err"
    }

    final case class OpenMiningRoundIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
        round: Long,
    ) extends TxLogIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = OpenMiningRoundIndexRecord
    }

    object OpenMiningRoundIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "omr"
    }

    final case class ClosedMiningRoundIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
        round: Long,
        effectiveAt: Instant,
    ) extends TxLogIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = ClosedMiningRoundIndexRecord
    }

    object ClosedMiningRoundIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "cmr"
    }

    sealed trait RewardIndexRecord extends TxLogIndexRecord {
      def party: PartyId
      def amount: BigDecimal
      def round: Long
    }

    final case class AppRewardIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
        round: Long,
        party: PartyId,
        amount: BigDecimal,
    ) extends RewardIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = AppRewardIndexRecord
    }

    object AppRewardIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "are"
    }

    final case class ValidatorRewardIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
        round: Long,
        party: PartyId,
        amount: BigDecimal,
    ) extends RewardIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = ValidatorRewardIndexRecord
    }

    object ValidatorRewardIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "vre"
    }

    final case class ExtraTrafficPurchaseIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
        round: Long,
        validator: PartyId,
        trafficPurchased: Long,
        ccSpent: BigDecimal,
    ) extends TxLogIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = ExtraTrafficPurchaseIndexRecord
    }

    object ExtraTrafficPurchaseIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "etp"
    }

    final case class BalanceChangeIndexRecord(
        optOffset: Option[String],
        eventId: String,
        domainId: DomainId,
        round: Long,
        changeToInitialAmountAsOfRoundZero: BigDecimal,
        changeToHoldingFeesRate: BigDecimal,
    ) extends TxLogIndexRecord {
      override val companion: TxLogIndexRecordCompanion = BalanceChangeIndexRecord
      override def acsContractId: Option[codegen.ContractId[_]] = None
    }

    object BalanceChangeIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "bac"
    }

    final case class RecentActivityIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
    ) extends TxLogIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = RecentActivityIndexRecord
    }

    object RecentActivityIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "rar"
    }
  }

  sealed trait TxLogEntry extends TxLogStore.Entry[TxLogIndexRecord] {}

  object TxLogEntry {

    final case class ErrorTxLogEntry(indexRecord: TxLogIndexRecord.ErrorIndexRecord)
        extends TxLogEntry {}

    final case class EmptyTxLogEntry(indexRecord: TxLogIndexRecord) extends TxLogEntry {}

    final case class OpenMiningRoundLogEntry(
        indexRecord: TxLogIndexRecord,
        coinCreateFee: BigDecimal,
        holdingFee: BigDecimal,
        lockHolderFee: BigDecimal,
        initialTransferFee: BigDecimal,
        transferFeeSteps: Seq[(BigDecimal, BigDecimal)],
    ) extends TxLogEntry {}

    object OpenMiningRoundLogEntry {
      val transaction_type = "open_mining_round"
    }

    final case class RecentActivityLogEntry(
        indexRecord: TxLogIndexRecord.RecentActivityIndexRecord,
        provider: String,
        sender: String,
        receiver: String,
        amount: BigDecimal,
        coinPrice: BigDecimal,
    ) extends TxLogEntry {
      def toResponseItem = httpDef.ListRecentActivityResponseItem(
        provider = provider,
        sender = sender,
        receiver = receiver,
        amount = Codec.encode(amount),
        coinPrice = Codec.encode(coinPrice),
      )
    }
  }

  case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )
  }

  object State {
    def empty: State = State(
      entries = immutable.Queue.empty
    )

    implicit val stateMonoid: Monoid[State] = new Monoid[State] {
      override val empty = State(immutable.Queue.empty)

      override def combine(a: State, b: State) =
        a.appended(b)
    }

    def fromCoinImportCrate(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        coin: cc.coin.Coin,
    ): State =
      State(
        immutable.Queue(
          TxLogEntry.EmptyTxLogEntry(
            indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
              optOffset = Some(tx.getOffset()),
              eventId = event.getEventId(),
              domainId = domainId,
              round = coin.amount.createdAt.number,
              changeToInitialAmountAsOfRoundZero = amountAsOfRoundZero(coin.amount),
              changeToHoldingFeesRate = coin.amount.ratePerRound.rate,
            )
          )
        )
      )

    def fromCoinCreateSummary[T <: com.daml.ledger.javaapi.data.codegen.ContractId[_]](
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        ccsum: CoinCreateSummary[T],
    ): State = {
      val coinCid = ccsum.coin.contractId
      val coin = tx.getEventsById.asScala
        .collectFirst {
          case (_, c: CreatedEvent) if c.getContractId == coinCid => {
            CoinCreate.unapply(c).map(_.payload)
          }
        }
        .flatten
        .getOrElse {
          throw new RuntimeException(
            s"The coin contract $coinCid referenced by CoinCreateSummary was not found in transaction ${tx.getTransactionId}"
          )
        }

      State(
        immutable.Queue(
          ScanTxLogParser.entryFromCoin(
            Some(tx.getOffset()),
            event.getEventId(),
            domainId,
            coin.amount,
          )
        )
      )
    }

    def fromTransfer(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
        rootEventId: Option[String] = None,
    ): State = {
      val appRewards = node.result.value.summary.inputAppRewardAmount
      val validatorRewards = node.result.value.summary.inputValidatorRewardAmount
      val party = Codec
        .decode(Codec.Party)(node.argument.value.transfer.sender)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"Cannot decode party ID ${node.argument.value.transfer.sender}")
            .asRuntimeException()
        )
      val round = node.result.value.round

      val appRewardEntry =
        if (appRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            TxLogEntry.EmptyTxLogEntry(
              indexRecord = TxLogIndexRecord.AppRewardIndexRecord(
                offset = tx.getOffset(),
                eventId = rootEventId.getOrElse(event.getEventId()),
                domainId = domainId,
                round = round.number,
                party = party,
                amount = appRewards,
              )
            )
          State(immutable.Queue(entry))
        } else {
          State.empty
        }

      val validatorRewardEntry =
        if (validatorRewards.compareTo(BigDecimal(0.0)) > 0) {
          val entry =
            TxLogEntry.EmptyTxLogEntry(
              indexRecord = TxLogIndexRecord.ValidatorRewardIndexRecord(
                offset = tx.getOffset(),
                eventId = rootEventId.getOrElse(event.getEventId()),
                domainId = domainId,
                round = round.number,
                party = party,
                amount = validatorRewards,
              )
            )
          State(immutable.Queue(entry))
        } else {
          State.empty
        }

      val balanceChangeEntry = State(
        immutable.Queue(
          TxLogEntry.EmptyTxLogEntry(
            indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
              optOffset = Some(tx.getOffset()),
              eventId = rootEventId.getOrElse(event.getEventId()),
              domainId = domainId,
              round = round.number,
              changeToInitialAmountAsOfRoundZero =
                node.result.value.summary.changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate = node.result.value.summary.changeToHoldingFeesRate,
            )
          )
        )
      )

      val recentActivityEntry = State(
        immutable.Queue(
          TxLogEntry.RecentActivityLogEntry(
            indexRecord = TxLogIndexRecord.RecentActivityIndexRecord(
              offset = tx.getOffset(),
              eventId = rootEventId.getOrElse(event.getEventId()),
              domainId = domainId,
            ),
            provider = node.argument.value.transfer.provider,
            sender = node.argument.value.transfer.sender,
            receiver = node.argument.value.transfer.outputs.asScala.headOption
              .map(_.receiver)
              .getOrElse(""),
            amount = node.result.value.summary.inputCoinAmount,
            coinPrice = node.result.value.summary.coinPrice,
          )
        )
      )
      State.empty
        .appended(appRewardEntry)
        .appended(validatorRewardEntry)
        .appended(balanceChangeEntry)
        .appended(recentActivityEntry)
    }

    def fromCoinExpireSummary(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        cxsum: CoinExpireSummary,
    ): State = {
      State(
        immutable.Queue(
          TxLogEntry.EmptyTxLogEntry(
            indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
              optOffset = Some(tx.getOffset()),
              eventId = event.getEventId(),
              domainId = domainId,
              round = cxsum.round.number,
              changeToInitialAmountAsOfRoundZero = cxsum.changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate = cxsum.changeToHoldingFeesRate,
            )
          )
        )
      )
    }

    def fromBuyMemberTraffic(
        tx: TransactionTree,
        event: ExercisedEvent,
        domainId: DomainId,
        node: ExerciseNode[CoinRules_BuyMemberTraffic.Arg, CoinRules_BuyMemberTraffic.Res],
    )(implicit lc: ErrorLoggingContext): State = {

      // second child event is the transfer of CC from validator to SVC to buy extra traffic
      val transferEvent = tx.getEventsById.get(event.getChildEventIds.get(1))
      val transferNode = (transferEvent match {
        case e: ExercisedEvent => Transfer.unapply(e)
        case _ => None
      }).getOrElse(
        throw new RuntimeException(
          s"Unable to parse event ${transferEvent.getEventId} as Transfer"
        )
      )
      val validatorParty = Codec
        .decode(Codec.Party)(transferNode.argument.value.transfer.sender)
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(
              s"Cannot decode party ID ${transferNode.argument.value.transfer.sender}"
            )
            .asRuntimeException()
        )
      val round = transferNode.result.value.round
      val trafficPurchased = node.argument.value.trafficAmount
      val ccSpent = transferNode.argument.value.transfer.outputs.get(0).amount
      val buyExtraTrafficEntry = TxLogEntry.EmptyTxLogEntry(
        indexRecord = TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord(
          offset = tx.getOffset(),
          eventId = event.getEventId(),
          domainId = domainId,
          round = round.number,
          validator = validatorParty,
          trafficPurchased = trafficPurchased,
          ccSpent = ccSpent,
        )
      )

      // third child event is the burning of transferred coin by the SVC
      val coinArchiveEvent = tx.getEventsById.get(event.getChildEventIds.get(2))

      State(immutable.Queue(buyExtraTrafficEntry))
        // append the entries for rewards
        .appended(
          State.fromTransfer(
            tx,
            transferEvent,
            domainId,
            transferNode,
            Some(event.getEventId()),
          )
        )
        // append the balance change entry from burning the transferred coin
        .appended(
          State.fromCoinArchiveEvent(
            tx,
            coinArchiveEvent,
            domainId,
            Some(event.getEventId()),
          )
        )

    }

    def fromCollectEntryPayment(
        tx: TransactionTree,
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
        tx: TransactionTree,
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
              s"was not found in transaction ${tx.getTransactionId}"
          )
        )
      State(
        immutable.Queue(
          TxLogEntry.EmptyTxLogEntry(
            indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
              optOffset = Some(tx.getOffset()),
              eventId = rootEventId.getOrElse(event.getEventId()),
              domainId = domainId,
              round = burntCoin.amount.createdAt.number,
              // negative value for both initial amount and holding fee so that the total balance can be calculated correctly
              changeToInitialAmountAsOfRoundZero = -amountAsOfRoundZero(burntCoin.amount),
              changeToHoldingFeesRate = -burntCoin.amount.ratePerRound.rate,
            )
          )
        )
      )
    }

    def fromOpenMiningRoundCreate(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        round: OpenMiningRoundCreate.ContractType,
    ): State = {
      val config = round.payload.transferConfigUsd
      val coinPrice = round.payload.coinPrice
      val newEntry = TxLogEntry.OpenMiningRoundLogEntry(
        indexRecord = TxLogIndexRecord.OpenMiningRoundIndexRecord(
          offset = tx.getOffset(),
          eventId = event.getEventId(),
          domainId = domainId,
          round = round.payload.round.number,
        ),
        coinCreateFee = dollarsToCC(config.createFee.fee, coinPrice),
        holdingFee = dollarsToCC(config.holdingFee.rate, coinPrice),
        lockHolderFee = dollarsToCC(config.lockHolderFee.fee, coinPrice),
        initialTransferFee = config.transferFee.initialRate,
        transferFeeSteps = config.transferFee.steps.asScala.toSeq.map(step =>
          (dollarsToCC(step._1, coinPrice), step._2)
        ),
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }

    def fromClosedMiningRoundCreate(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        round: ClosedMiningRoundCreate.ContractType,
    ): State = {
      val newEntry = TxLogEntry.EmptyTxLogEntry(
        indexRecord = TxLogIndexRecord.ClosedMiningRoundIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId(),
          domainId = domainId,
          round = round.payload.round.number,
          effectiveAt = tx.getEffectiveAt,
        )
      )

      State(
        entries = immutable.Queue(newEntry)
      )
    }
  }

  private def entryFromCoin(
      optOffset: Option[String],
      eventId: String,
      domainId: DomainId,
      amount: ExpiringAmount,
  ): TxLogEntry = {
    TxLogEntry.EmptyTxLogEntry(
      indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
        optOffset = optOffset,
        eventId = eventId,
        domainId = domainId,
        round = amount.createdAt.number,
        changeToInitialAmountAsOfRoundZero = amountAsOfRoundZero(amount),
        changeToHoldingFeesRate = amount.ratePerRound.rate,
      )
    )
  }

  private def amountAsOfRoundZero(amount: ExpiringAmount) =
    amount.initialAmount + amount.ratePerRound.rate * BigDecimal(amount.createdAt.number)

}
