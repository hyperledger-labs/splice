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
  sealed trait ActivityType {
    def toResponse: httpDef.ListActivityResponseItem.ActivityType
  }
  object ActivityType {
    import httpDef.ListActivityResponseItem.{ActivityType as HttpActivityType}
    case object Transfer extends ActivityType {
      def toResponse = HttpActivityType.Transfer
    }
    case object Mint extends ActivityType {
      def toResponse = HttpActivityType.Mint
    }
    case object Tap extends ActivityType {
      def toResponse = HttpActivityType.DevnetTap
    }
    case object SvRewardCollected extends ActivityType {
      def toResponse = HttpActivityType.SvRewardCollected
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

  sealed trait ActivityLogEntry extends TxLogEntry {
    def activityType: ActivityType
    def toResponseItem: httpDef.ListActivityResponseItem
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
        indexRecord = ActivityIndexRecord(tx, event, domainId),
        date = tx.getEffectiveAt,
        provider = node.argument.value.transfer.provider,
        sender = sender,
        receivers = receivers,
        coinPrice = coinPrice,
      )
    }
  }

  final case class TransferLogEntry(
      indexRecord: ActivityIndexRecord,
      date: Instant,
      provider: String,
      sender: SenderAmount,
      receivers: Seq[ReceiverAmount],
      coinPrice: BigDecimal,
  ) extends ActivityLogEntry {
    val activityType = ActivityType.Transfer
    def toResponseItem = httpDef.ListActivityResponseItem(
      activityType = activityType.toResponse,
      eventId = indexRecord.eventId,
      offset = indexRecord.optOffset,
      domainId = indexRecord.domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      transfer = Some(
        httpDef.Transfer(
          provider = provider,
          sender = sender.toResponse,
          receivers = receivers.map(_.toResponse).toVector,
        )
      ),
      coinPrice = Codec.encode(coinPrice),
    )
  }

  final case class TapLogEntry(
      indexRecord: ActivityIndexRecord,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      coinPrice: BigDecimal,
  ) extends ActivityLogEntry {
    val activityType = ActivityType.Tap
    def toResponseItem = httpDef.ListActivityResponseItem(
      activityType = activityType.toResponse,
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
      coinPrice = Codec.encode(coinPrice),
    )
  }

  final case class MintLogEntry(
      indexRecord: ActivityIndexRecord,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      coinPrice: BigDecimal,
  ) extends ActivityLogEntry {
    val activityType = ActivityType.Mint
    def toResponseItem = httpDef.ListActivityResponseItem(
      activityType = activityType.toResponse,
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
      coinPrice = Codec.encode(coinPrice),
    )
  }

  final case class SvRewardCollectedLogEntry(
      indexRecord: ActivityIndexRecord,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      coinPrice: BigDecimal,
  ) extends ActivityLogEntry {
    val activityType = ActivityType.SvRewardCollected
    def toResponseItem = httpDef.ListActivityResponseItem(
      activityType = activityType.toResponse,
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
      coinPrice = Codec.encode(coinPrice),
    )
  }

  private def parseSenderAmount(
      arg: cc.coin.CoinRules_Transfer,
      res: cc.coin.TransferResult,
  ): SenderAmount = {
    val sender = arg.transfer.sender

    val senderFee = parseOutputAmounts(arg, res)
      .filter(o => o.output.receiver == sender && o.output.lock.isEmpty)
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
      arg: cc.coin.CoinRules_Transfer,
      res: cc.coin.TransferResult,
  ): Seq[ReceiverAmount] = {

    // Note: the same receiver party can appear multiple times in the transfer result
    // The code below merges amounts and fees for the same receiver, while preserving
    // the order of receivers.
    parseOutputAmounts(arg, res)
      .filter(_.output.receiver != arg.transfer.sender)
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
      output: cc.coin.TransferOutput,
      senderFee: BigDecimal,
      receiverFee: BigDecimal,
  )

  private def parseOutputAmounts(
      arg: cc.coin.CoinRules_Transfer,
      res: cc.coin.TransferResult,
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
