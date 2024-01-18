package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.{TreeEvent, *}
import com.daml.network.codegen.java.cc
import com.daml.network.history.*
import com.daml.network.store.{StoreErrors, TxLogStore}
import com.daml.network.util.{Codec, ExerciseNode}

import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import java.time.Instant
import com.daml.network.http.v0.definitions as httpDef
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.{DomainId, PartyId}
import spray.json.{
  DefaultJsonProtocol,
  DeserializationException,
  JsString,
  JsValue,
  JsonFormat,
  RootJsonFormat,
  deserializationError,
}

import java.time.ZoneOffset
import scala.math.BigDecimal.RoundingMode

sealed trait TxLogEntry extends TxLogStore.Entry {
  // Scan store uses the eventId for pagination
  def eventId: String
}

object TxLogEntry {

  def encode(entry: TxLogEntry): (String3, JsValue) = {
    import spray.json.*
    import JsonProtocol.*
    entry match {
      case e: ErrorLogEntry => (ErrorLogEntry.dbType, e.toJson)
      case e: BalanceChangeLogEntry => (BalanceChangeLogEntry.dbType, e.toJson)
      case e: ClosedMiningRoundLogEntry => (ClosedMiningRoundLogEntry.dbType, e.toJson)
      case e: ExtraTrafficPurchaseLogEntry => (ExtraTrafficPurchaseLogEntry.dbType, e.toJson)
      case e: OpenMiningRoundLogEntry => (OpenMiningRoundLogEntry.dbType, e.toJson)
      case e: AppRewardLogEntry => (AppRewardLogEntry.dbType, e.toJson)
      case e: MintLogEntry => (MintLogEntry.dbType, e.toJson)
      case e: SvRewardCollectedLogEntry => (SvRewardCollectedLogEntry.dbType, e.toJson)
      case e: TapLogEntry => (TapLogEntry.dbType, e.toJson)
      case e: TransferLogEntry => (TransferLogEntry.dbType, e.toJson)
      case e: ValidatorRewardLogEntry => (ValidatorRewardLogEntry.dbType, e.toJson)

    }
  }
  def decode(dbType: String3, json: JsValue): TxLogEntry = {
    import TxLogEntry.JsonProtocol.*
    try {
      dbType match {
        case ErrorLogEntry.dbType => json.convertTo[ErrorLogEntry]
        case BalanceChangeLogEntry.dbType => json.convertTo[BalanceChangeLogEntry]
        case ClosedMiningRoundLogEntry.dbType => json.convertTo[ClosedMiningRoundLogEntry]
        case ExtraTrafficPurchaseLogEntry.dbType => json.convertTo[ExtraTrafficPurchaseLogEntry]
        case OpenMiningRoundLogEntry.dbType => json.convertTo[OpenMiningRoundLogEntry]
        case AppRewardLogEntry.dbType => json.convertTo[AppRewardLogEntry]
        case MintLogEntry.dbType => json.convertTo[MintLogEntry]
        case SvRewardCollectedLogEntry.dbType => json.convertTo[SvRewardCollectedLogEntry]
        case TapLogEntry.dbType => json.convertTo[TapLogEntry]
        case TransferLogEntry.dbType => json.convertTo[TransferLogEntry]
        case ValidatorRewardLogEntry.dbType => json.convertTo[ValidatorRewardLogEntry]
        case _ => throw txDecodingFailed()
      }
    } catch {
      case _: DeserializationException => throw txDecodingFailed()
    }
  }

  object JsonProtocol extends DefaultJsonProtocol with StoreErrors {
    import com.digitalasset.canton.http.json.JsonProtocol.*
    implicit val domainIdFormat: JsonFormat[DomainId] =
      new JsonFormat[DomainId] {
        override def write(obj: DomainId) =
          JsString(obj.uid.toProtoPrimitive)

        override def read(json: JsValue) = json match {
          case JsString(s) =>
            DomainId.fromProtoPrimitive(s, "").fold(f => deserializationError(f.message), identity)
          case _ => deserializationError("DomainId must be a string")
        }
      }
    implicit val partyIdFormat: JsonFormat[PartyId] =
      new JsonFormat[PartyId] {
        override def write(obj: PartyId) =
          JsString(obj.uid.toProtoPrimitive)

        override def read(json: JsValue) = json match {
          case JsString(s) =>
            PartyId.fromProtoPrimitive(s, "").fold(f => deserializationError(f.message), identity)
          case _ => deserializationError("PartyId must be a string")
        }
      }

    import BalanceChangeLogEntry.PartyBalanceChange

    implicit val partyBalanceChangeFormat: RootJsonFormat[PartyBalanceChange] =
      jsonFormat2(PartyBalanceChange.apply)

    implicit val errorEntryFormat: RootJsonFormat[ErrorLogEntry] = jsonFormat1(ErrorLogEntry.apply)
    implicit val balanceChangeEntryFormat: RootJsonFormat[BalanceChangeLogEntry] = jsonFormat6(
      BalanceChangeLogEntry.apply
    )
    implicit val closedMiningRoundEntryFormat: RootJsonFormat[ClosedMiningRoundLogEntry] =
      jsonFormat4(
        ClosedMiningRoundLogEntry.apply
      )
    implicit val extraTrafficPurchaseEntryFormat: RootJsonFormat[ExtraTrafficPurchaseLogEntry] =
      jsonFormat6(
        ExtraTrafficPurchaseLogEntry.apply
      )
    implicit val openMiningRoundEntryFormat: RootJsonFormat[OpenMiningRoundLogEntry] = jsonFormat8(
      OpenMiningRoundLogEntry.apply
    )
    implicit val appRewardEntryFormat: RootJsonFormat[AppRewardLogEntry] = jsonFormat5(
      AppRewardLogEntry.apply
    )
    implicit val mintEntryFormat: RootJsonFormat[MintLogEntry] = jsonFormat8(
      MintLogEntry.apply
    )
    implicit val svRewardCollectedEntryFormat: RootJsonFormat[SvRewardCollectedLogEntry] =
      jsonFormat8(
        SvRewardCollectedLogEntry.apply
      )
    implicit val tapEntryFormat: RootJsonFormat[TapLogEntry] = jsonFormat8(
      TapLogEntry.apply
    )
    implicit val senderAmountFormat: JsonFormat[SenderAmount] = jsonFormat8(SenderAmount.apply)
    implicit val receiverAmountFormat: JsonFormat[ReceiverAmount] = jsonFormat3(
      ReceiverAmount.apply
    )
    implicit val balanceChangeFormat: JsonFormat[BalanceChange] = jsonFormat3(BalanceChange.apply)
    implicit val transferEntryFormat: RootJsonFormat[TransferLogEntry] = jsonFormat10(
      TransferLogEntry.apply
    )
    implicit val validatorRewardEntryFormat: RootJsonFormat[ValidatorRewardLogEntry] = jsonFormat5(
      ValidatorRewardLogEntry.apply
    )

  }

  final case class ErrorLogEntry(
      eventId: String
  ) extends TxLogEntry

  object ErrorLogEntry {
    val dbType: String3 = String3.tryCreate("err")
  }

  final case class BalanceChangeLogEntry(
      override val eventId: String,
      domainId: DomainId,
      round: Long,
      changeToInitialAmountAsOfRoundZero: BigDecimal,
      changeToHoldingFeesRate: BigDecimal,
      partyBalanceChanges: Map[PartyId, BalanceChangeLogEntry.PartyBalanceChange],
  ) extends TxLogEntry

  object BalanceChangeLogEntry {
    val dbType: String3 = String3.tryCreate("bac")

    final case class PartyBalanceChange(
        changeToInitialAmountAsOfRoundZero: BigDecimal,
        changeToHoldingFeesRate: BigDecimal,
    )
  }

  sealed trait RewardLogEntry extends TxLogEntry {
    def party: PartyId

    def amount: BigDecimal

    def round: Long
  }

  final case class AppRewardLogEntry(
      override val eventId: String,
      domainId: DomainId,
      round: Long,
      party: PartyId,
      amount: BigDecimal,
  ) extends RewardLogEntry

  object AppRewardLogEntry {
    val dbType: String3 = String3.tryCreate("are")
  }

  final case class ValidatorRewardLogEntry(
      override val eventId: String,
      domainId: DomainId,
      round: Long,
      party: PartyId,
      amount: BigDecimal,
  ) extends RewardLogEntry

  object ValidatorRewardLogEntry {
    val dbType: String3 = String3.tryCreate("vre")
  }

  final case class ExtraTrafficPurchaseLogEntry(
      override val eventId: String,
      domainId: DomainId,
      round: Long,
      validator: PartyId,
      trafficPurchased: Long,
      ccSpent: BigDecimal,
  ) extends TxLogEntry

  object ExtraTrafficPurchaseLogEntry {
    val dbType: String3 = String3.tryCreate("etp")
  }

  final case class OpenMiningRoundLogEntry(
      override val eventId: String,
      domainId: DomainId,
      round: Long,
      coinCreateFee: BigDecimal,
      holdingFee: BigDecimal,
      lockHolderFee: BigDecimal,
      initialTransferFee: BigDecimal,
      transferFeeSteps: Seq[(BigDecimal, BigDecimal)],
  ) extends TxLogEntry

  object OpenMiningRoundLogEntry {
    val dbType: String3 = String3.tryCreate("omr")
  }

  final case class ClosedMiningRoundLogEntry(
      override val eventId: String,
      domainId: DomainId,
      round: Long,
      effectiveAt: Instant,
  ) extends TxLogEntry

  object ClosedMiningRoundLogEntry {
    val dbType: String3 = String3.tryCreate("cmr")
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
    val dbType: String3 = String3.tryCreate("tra")

    def apply(
        tx: TransactionTreeV2,
        event: TreeEvent,
        domainId: DomainId,
        node: ExerciseNode[Transfer.Arg, Transfer.Res],
    ): TransferLogEntry = {
      val coinPrice = node.result.value.summary.coinPrice
      val sender = parseSenderAmount(node.argument.value, node.result.value)
      val receivers = parseReceiverAmounts(node.argument.value, node.result.value)

      TransferLogEntry(
        offset = tx.getOffset,
        eventId = event.getEventId,
        domainId = domainId,
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
        round = node.result.value.round.number,
        coinPrice = coinPrice,
      )
    }
  }

  final case class TransferLogEntry(
      override val eventId: String,
      offset: String,
      domainId: DomainId,
      date: Instant,
      provider: String,
      sender: SenderAmount,
      receivers: Seq[ReceiverAmount],
      balanceChanges: Seq[BalanceChange],
      round: Long,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    override def transactionType = TransactionType.Transfer
    override def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = eventId,
      offset = Some(offset),
      domainId = domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      transfer = Some(
        httpDef.Transfer(
          provider = provider,
          sender = sender.toResponse,
          receivers = receivers.map(_.toResponse).toVector,
          balanceChanges = balanceChanges.map(_.toResponse).toVector,
        )
      ),
      round = round,
      coinPrice = Codec.encode(coinPrice),
    )
  }

  final case class TapLogEntry(
      override val eventId: String,
      offset: String,
      domainId: DomainId,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      round: Long,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    override def transactionType = TransactionType.Tap
    override def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = eventId,
      offset = Some(offset),
      domainId = domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      tap = Some(
        httpDef.CoinAmount(
          coinOwner = coinOwner,
          coinAmount = Codec.encode(coinAmount),
        )
      ),
      round = round,
      coinPrice = Codec.encode(coinPrice),
    )
  }

  object TapLogEntry {
    val dbType: String3 = String3.tryCreate("tap")
  }

  final case class MintLogEntry(
      override val eventId: String,
      offset: String,
      domainId: DomainId,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      round: Long,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    override def transactionType = TransactionType.Mint
    override def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = eventId,
      offset = Some(offset),
      domainId = domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      mint = Some(
        httpDef.CoinAmount(
          coinOwner = coinOwner,
          coinAmount = Codec.encode(coinAmount),
        )
      ),
      round = round,
      coinPrice = Codec.encode(coinPrice),
    )
  }

  object MintLogEntry {
    val dbType: String3 = String3.tryCreate("min")
  }

  final case class SvRewardCollectedLogEntry(
      override val eventId: String,
      offset: String,
      domainId: DomainId,
      date: Instant,
      coinOwner: String,
      coinAmount: BigDecimal,
      round: Long,
      coinPrice: BigDecimal,
  ) extends TransactionLogEntry {
    override def transactionType = TransactionType.SvRewardCollected
    override def toResponseItem = httpDef.TransactionHistoryResponseItem(
      transactionType = transactionType.toResponse,
      eventId = eventId,
      offset = Some(offset),
      domainId = domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
      svRewardCollected = Some(
        httpDef.CoinAmount(
          coinOwner = coinOwner,
          coinAmount = Codec.encode(coinAmount),
        )
      ),
      round = round,
      coinPrice = Codec.encode(coinPrice),
    )
  }

  object SvRewardCollectedLogEntry {
    val dbType: String3 = String3.tryCreate("src")
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
