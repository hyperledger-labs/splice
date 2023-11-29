package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.{TreeEvent, *}
import com.daml.network.codegen.java.cc
import com.daml.network.history.*
import com.daml.network.scan.store.TxLogIndexRecord.*
import com.daml.network.store.TxLogStore
import com.daml.network.util.{Codec, ExerciseNode}
import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import java.time.Instant
import scala.math.BigDecimal.{RoundingMode, javaBigDecimal2bigDecimal}
import com.daml.network.http.v0.definitions as httpDef
import com.digitalasset.canton.topology.DomainId
import java.time.ZoneOffset

sealed trait TxLogEntry extends TxLogStore.Entry[TxLogIndexRecord] {}

object TxLogEntry {

  final case class ErrorTxLogEntry(indexRecord: ErrorIndexRecord) extends TxLogEntry {}

  final case class EmptyTxLogEntry(indexRecord: TxLogIndexRecord) extends TxLogEntry {}

  final case class OpenMiningRoundLogEntry(
      indexRecord: TxLogIndexRecord,
      coinCreateFee: BigDecimal,
      holdingFee: BigDecimal,
      lockHolderFee: BigDecimal,
      initialTransferFee: BigDecimal,
      transferFeeSteps: Seq[(BigDecimal, BigDecimal)],
  ) extends TxLogEntry

  object OpenMiningRoundLogEntry {
    val transaction_type = "open_mining_round"
  }
  sealed trait TransactionType {
    def toResponse: httpDef.TransactionHistoryResponseItem.TransactionType
  }
  object TransactionType {
    import httpDef.TransactionHistoryResponseItem.{TransactionType as HttpTransactionType}
    case object Transfer extends TransactionType {
      def toResponse = HttpTransactionType.Transfer
    }
    case object Mint extends TransactionType {
      def toResponse = HttpTransactionType.Mint
    }
    case object Tap extends TransactionType {
      def toResponse = HttpTransactionType.DevnetTap
    }
    case object SvRewardCollected extends TransactionType {
      def toResponse = HttpTransactionType.SvRewardCollected
    }
  }

  final case class SenderAmount(
      party: String,
      inputCoinAmount: BigDecimal,
      inputAppRewardAmount: BigDecimal,
      inputValidatorRewardAmount: BigDecimal,
      senderChangeAmount: BigDecimal,
      senderChangeFee: BigDecimal,
      senderFee: BigDecimal,
      holdingFees: BigDecimal,
  ) {
    def toResponse = httpDef.SenderAmount(
      party = party,
      inputCoinAmount = Some(Codec.encode(inputCoinAmount)),
      inputAppRewardAmount = Some(Codec.encode(inputAppRewardAmount)),
      inputValidatorRewardAmount = Some(Codec.encode(inputValidatorRewardAmount)),
      senderChangeAmount = Codec.encode(senderChangeAmount),
      senderChangeFee = Codec.encode(senderChangeFee),
      senderFee = Codec.encode(senderFee),
      holdingFees = Codec.encode(holdingFees),
    )
  }
  final case class ReceiverAmount(
      party: String,
      amount: BigDecimal,
      receiverFee: BigDecimal,
  ) {
    def toResponse = httpDef.ReceiverAmount(
      party = party,
      amount = Codec.encode(amount),
      receiverFee = Codec.encode(receiverFee),
    )
  }

  final case class BalanceChange(
      party: String,
      changeToInitialAmountAsOfRoundZero: BigDecimal,
      changeToHoldingFeesRate: BigDecimal,
  ) {
    def toResponse = httpDef.BalanceChange(
      party = party,
      changeToInitialAmountAsOfRoundZero = Codec.encode(changeToInitialAmountAsOfRoundZero),
      changeToHoldingFeesRate = Codec.encode(changeToHoldingFeesRate),
    )
  }

  sealed trait TransactionLogEntry extends TxLogEntry {
    def transactionType: TransactionType
    def toResponseItem: httpDef.TransactionHistoryResponseItem
    def date: Instant
  }

  object TransferLogEntry {
    def apply(
        tx: TransactionTree,
        event: TreeEvent,
        domainId: DomainId,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
    ): TransferLogEntry = {
      val coinPrice = node.result.value.summary.coinPrice
      val sender = parseSenderAmount(node.argument.value, node.result.value)
      val receivers = parseReceiverAmounts(node.argument.value, node.result.value)

      TransferLogEntry(
        indexRecord = TransactionIndexRecord(tx, event, domainId),
        date = tx.getEffectiveAt,
        provider = node.argument.value.transfer.provider,
        sender = sender,
        receivers = receivers,
        balanceChanges = node.result.value.summary.balanceChanges.asScala
          .map { case (party, bc) =>
            BalanceChange(
              party = party,
              changeToInitialAmountAsOfRoundZero = bc.changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate = bc.changeToHoldingFeesRate,
            )
          }
          .toSeq
          .sortBy(_.party),
        round = node.result.value.round,
        coinPrice = coinPrice,
      )
    }
  }

  final case class TransferLogEntry(
      indexRecord: TransactionIndexRecord,
      date: Instant,
      provider: String,
      sender: SenderAmount,
      receivers: Seq[ReceiverAmount],
      balanceChanges: Seq[BalanceChange],
      round: cc.round.types.Round,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    val transactionType = TransactionType.Transfer
    def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = indexRecord.eventId,
      offset = indexRecord.optOffset,
      domainId = indexRecord.domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      transfer = Some(
        httpDef.Transfer(
          provider = provider,
          sender = sender.toResponse,
          receivers = receivers.map(_.toResponse).toVector,
          balanceChanges = balanceChanges.map(_.toResponse).toVector,
        )
      ),
      round = Codec.encode(round),
      coinPrice = Codec.encode(coinPrice),
    )
  }

  final case class TapLogEntry(
      indexRecord: TransactionIndexRecord,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      round: cc.round.types.Round,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    val transactionType = TransactionType.Tap
    def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = indexRecord.eventId,
      offset = indexRecord.optOffset,
      domainId = indexRecord.domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      tap = Some(
        httpDef.CoinAmount(
          coinOwner = coinOwner,
          coinAmount = Codec.encode(coinAmount),
        )
      ),
      round = Codec.encode(round),
      coinPrice = Codec.encode(coinPrice),
    )
  }

  final case class MintLogEntry(
      indexRecord: TransactionIndexRecord,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      round: cc.round.types.Round,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    val transactionType = TransactionType.Mint
    def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = indexRecord.eventId,
      offset = indexRecord.optOffset,
      domainId = indexRecord.domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      mint = Some(
        httpDef.CoinAmount(
          coinOwner = coinOwner,
          coinAmount = Codec.encode(coinAmount),
        )
      ),
      round = Codec.encode(round),
      coinPrice = Codec.encode(coinPrice),
    )
  }

  final case class SvRewardCollectedLogEntry(
      indexRecord: TransactionIndexRecord,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      round: cc.round.types.Round,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    val transactionType = TransactionType.SvRewardCollected
    def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = indexRecord.eventId,
      offset = indexRecord.optOffset,
      domainId = indexRecord.domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      svRewardCollected = Some(
        httpDef.CoinAmount(
          coinOwner = coinOwner,
          coinAmount = Codec.encode(coinAmount),
        )
      ),
      round = Codec.encode(round),
      coinPrice = Codec.encode(coinPrice),
    )
  }

  private def parseSenderAmount(
      arg: cc.coinrules.CoinRules_Transfer,
      res: cc.coinrules.TransferResult,
  ): SenderAmount = {
    val sender = arg.transfer.sender
    val senderFee = parseOutputAmounts(arg, res)
      .map(_.senderFee)
      .sum

    SenderAmount(
      party = sender,
      inputCoinAmount = res.summary.inputCoinAmount,
      inputAppRewardAmount = res.summary.inputAppRewardAmount,
      inputValidatorRewardAmount = res.summary.inputValidatorRewardAmount,
      senderChangeAmount = res.summary.senderChangeAmount,
      senderChangeFee = res.summary.senderChangeFee,
      senderFee = senderFee,
      holdingFees = res.summary.holdingFees,
    )
  }

  private def parseReceiverAmounts(
      arg: cc.coinrules.CoinRules_Transfer,
      res: cc.coinrules.TransferResult,
  ): Seq[ReceiverAmount] = {

    // Note: the same receiver party can appear multiple times in the transfer result
    // The code below merges amounts and fees for the same receiver, while preserving
    // the order of receivers.
    parseOutputAmounts(arg, res)
      .map(o =>
        ReceiverAmount(
          party = o.output.receiver,
          amount = o.output.amount,
          receiverFee = o.receiverFee,
        )
      )
      .foldLeft(immutable.ListMap.empty[String, ReceiverAmount])((acc, receiverAmount) =>
        acc.updatedWith(receiverAmount.party)(prev =>
          Some(prev.fold(receiverAmount) { r =>
            r.copy(
              amount = r.amount + receiverAmount.amount,
              receiverFee = r.receiverFee + receiverAmount.receiverFee,
            )
          })
        )
      )
      .values
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
