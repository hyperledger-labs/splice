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
import com.daml.network.environment.ledger.api.InFlightTransferOutEvent
import com.digitalasset.canton.topology.DomainId

class ScanTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      ScanTxLogParser.TxLogIndexRecord,
      ScanTxLogParser.TxLogEntry,
    ]
    with NamedLogging {

  import ScanTxLogParser.*

  private def parseTree(tree: TransactionTree, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {
    // TODO(#2930) add more checks on the nodes, at least that the svc party is correct
    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case Transfer(node) =>
            State.fromTransfer(tree, root, node)
          case Mint(node) =>
            State.fromCoinCreateSummary(tree, exercised, node.result.value)
          case ImportCrate_Receive(_) =>
            State.empty
          case CoinRules_BuyExtraTraffic(node) =>
            State.fromBuyExtraTraffic(tree, exercised, node)
          case CoinExpire(node) =>
            State.fromCoinExpireSummary(tree, exercised, node.result.value)
          case LockedCoinExpireCoin(node) =>
            State.fromCoinExpireSummary(tree, exercised, node.result.value)
          case LockedCoinUnlock(_) =>
            State.empty
          case CoinArchive(_) =>
            // TODO(#6480) cleanup expecting unexpected error messages in logs as a workaround
            logger.error(
              s"Unexpected coin archive event for coin ${exercised.getContractId} in transaction ${tree.getTransactionId}"
            )
            State.empty
          case _ => parseTrees(tree, exercised.getChildEventIds.asScala.toList)
        }

      case created: CreatedEvent =>
        created match {
          case CoinImportCrate(coin) =>
            State.fromCoinImportCrate(tree, root, coin)
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(tree, root, round)
          case ClosedMiningRoundCreate(round) =>
            State.fromClosedMiningRoundCreate(tree, root, round)
          case CoinCreate(coin) =>
            // TODO(#6480) cleanup expecting unexpected error messages in logs as a workaround
            logger.error(
              s"Unexpected coin create event for coin ${coin.contractId.contractId} in transaction ${tree.getTransactionId}"
            )
            State.empty
          case _ => State.empty
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

  // TODO(#4906): handle in-flight contracts when we tackle global domain migration
  override def parseAcs(acs: Seq[ActiveContract], inFlight: Seq[InFlightTransferOutEvent])(implicit
      tc: TraceContext
  ): Seq[(DomainId, ScanTxLogParser.TxLogEntry)] = acs.collect(ac =>
    ac.createdEvent match {
      case CoinCreate(c) =>
        (ac.domainId, entryFromCoinContract(ac.createdEvent.getEventId, c))
      case LockedCoinCreate(lc) =>
        (ac.domainId, entryFromLockedCoinContract(ac.createdEvent.getEventId, lc))
    }
  )

  def entryFromCoinContract(
      eventId: String,
      coin: CoinCreate.ContractType,
  ): TxLogEntry = entryFromCoin(None, eventId, coin.payload.amount)

  def entryFromLockedCoinContract(
      eventId: String,
      lc: LockedCoinCreate.ContractType,
  ): TxLogEntry = entryFromCoin(None, eventId, lc.payload.coin.amount)

  override def tryParse(tx: TransactionTree)(implicit
      tc: TraceContext
  ): Seq[ScanTxLogParser.TxLogEntry] = {
    val ret = parseTrees(tx, tx.getRootEventIds.asScala.toList).entries
    logger.debug(s"Extracted log entries: ${ret}")
    ret
  }

  override def error(offset: String, eventId: String): Option[TxLogEntry] = Some(
    TxLogEntry.ErrorTxLogEntry(
      indexRecord = TxLogIndexRecord.ErrorIndexRecord(
        offset,
        eventId,
      )
    )
  )

}

object ScanTxLogParser {

  sealed trait TxLogIndexRecord extends TxLogStore.IndexRecord

  object TxLogIndexRecord {

    final case class ErrorIndexRecord(
        offset: String,
        eventId: String,
    ) extends TxLogIndexRecord { override def optOffset = Some(offset) }

    final case class OpenMiningRoundIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
    ) extends TxLogIndexRecord { override def optOffset = Some(offset) }

    final case class ClosedMiningRoundIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
        effectiveAt: Instant,
    ) extends TxLogIndexRecord { override def optOffset = Some(offset) }

    sealed trait RewardIndexRecord extends TxLogIndexRecord {
      def party: PartyId
      def amount: BigDecimal
      def round: Long
    }

    final case class AppRewardIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
        party: PartyId,
        amount: BigDecimal,
    ) extends RewardIndexRecord { override def optOffset = Some(offset) }

    final case class ValidatorRewardIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
        party: PartyId,
        amount: BigDecimal,
    ) extends RewardIndexRecord { override def optOffset = Some(offset) }

    final case class ExtraTrafficPurchaseIndexRecord(
        offset: String,
        eventId: String,
        round: Long,
        validator: PartyId,
        trafficPurchased: Long,
        ccSpent: BigDecimal,
    ) extends TxLogIndexRecord { override def optOffset = Some(offset) }

    final case class BalanceChangeIndexRecord(
        optOffset: Option[String],
        eventId: String,
        round: Long,
        changeToInitialAmountAsOfRoundZero: BigDecimal,
        changeToHoldingFeesRate: BigDecimal,
    ) extends TxLogIndexRecord

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
        coin: cc.coin.Coin,
    ): State =
      State(
        immutable.Queue(
          TxLogEntry.EmptyTxLogEntry(
            indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
              optOffset = Some(tx.getOffset()),
              eventId = event.getEventId(),
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
            coin.amount,
          )
        )
      )
    }

    def fromTransfer(
        tx: TransactionTree,
        event: TreeEvent,
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
              round = round.number,
              changeToInitialAmountAsOfRoundZero =
                node.result.value.summary.changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate = node.result.value.summary.changeToHoldingFeesRate,
            )
          )
        )
      )

      State.empty
        .appended(appRewardEntry)
        .appended(validatorRewardEntry)
        .appended(balanceChangeEntry)
    }

    def fromCoinExpireSummary(
        tx: TransactionTree,
        event: TreeEvent,
        cxsum: CoinExpireSummary,
    ): State = {
      State(
        immutable.Queue(
          TxLogEntry.EmptyTxLogEntry(
            indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
              optOffset = Some(tx.getOffset()),
              eventId = event.getEventId(),
              round = cxsum.round.number,
              changeToInitialAmountAsOfRoundZero = cxsum.changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate = cxsum.changeToHoldingFeesRate,
            )
          )
        )
      )
    }

    def fromBuyExtraTraffic(
        tx: TransactionTree,
        event: ExercisedEvent,
        node: ExerciseNode[CoinRules_BuyExtraTraffic.Arg, CoinRules_BuyExtraTraffic.Res],
    )(implicit lc: ErrorLoggingContext): State = {

      // second child event is the transfer of CC from validator to SVC to buy extra traffic
      val transferEvent = tx.getEventsById.get(event.getChildEventIds.get(1))
      val transferNode = (transferEvent match {
        case e: ExercisedEvent => Transfer.unapply(e)
        case _ => None
      }).getOrElse(
        throw new RuntimeException(s"Unable to parse event ${transferEvent.getEventId} as Transfer")
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
      val trafficPurchased = node.argument.value.extraTraffic
      val ccSpent = transferNode.argument.value.transfer.outputs.get(0).amount
      val buyExtraTrafficEntry = TxLogEntry.EmptyTxLogEntry(
        indexRecord = TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord(
          offset = tx.getOffset(),
          eventId = event.getEventId(),
          round = round.number,
          validator = validatorParty,
          trafficPurchased = trafficPurchased,
          ccSpent = ccSpent,
        )
      )

      State(immutable.Queue(buyExtraTrafficEntry))
        // append the entries for rewards
        .appended(State.fromTransfer(tx, transferEvent, transferNode, Some(event.getEventId())))

    }

    def fromOpenMiningRoundCreate(
        tx: TransactionTree,
        event: TreeEvent,
        round: OpenMiningRoundCreate.ContractType,
    ): State = {
      val config = round.payload.transferConfigUsd
      val coinPrice = round.payload.coinPrice
      val newEntry = TxLogEntry.OpenMiningRoundLogEntry(
        indexRecord = TxLogIndexRecord.OpenMiningRoundIndexRecord(
          offset = tx.getOffset(),
          eventId = event.getEventId(),
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
        round: ClosedMiningRoundCreate.ContractType,
    ): State = {
      val newEntry = TxLogEntry.EmptyTxLogEntry(
        indexRecord = TxLogIndexRecord.ClosedMiningRoundIndexRecord(
          offset = tx.getOffset,
          eventId = event.getEventId(),
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
      amount: ExpiringAmount,
  ): TxLogEntry = {
    TxLogEntry.EmptyTxLogEntry(
      indexRecord = TxLogIndexRecord.BalanceChangeIndexRecord(
        optOffset = optOffset,
        eventId = eventId,
        round = amount.createdAt.number,
        changeToInitialAmountAsOfRoundZero = amountAsOfRoundZero(amount),
        changeToHoldingFeesRate = amount.ratePerRound.rate,
      )
    )
  }

  private def amountAsOfRoundZero(amount: ExpiringAmount) =
    amount.initialAmount + amount.ratePerRound.rate * BigDecimal(amount.createdAt.number)

}
