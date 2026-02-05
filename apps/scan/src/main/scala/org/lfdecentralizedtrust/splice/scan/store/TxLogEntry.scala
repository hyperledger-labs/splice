// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.store.StoreErrors
import org.lfdecentralizedtrust.splice.util.Codec

import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import java.time.Instant
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpDef
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryResponseItem.TransactionType as HttpTransactionType
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.PartyId

import java.time.ZoneOffset
import scala.math.BigDecimal.RoundingMode

trait TxLogEntry extends Product with Serializable {
  // Scan store uses the eventId for pagination
  def eventId: String
}

object TxLogEntry extends StoreErrors {

  object EntryType {
    val ErrorTxLogEntry = String3.tryCreate("err")
    val BalanceChangeTxLogEntry = String3.tryCreate("bac")
    val ClosedMiningRoundTxLogEntry = String3.tryCreate("cmr")
    val ExtraTrafficPurchaseTxLogEntry = String3.tryCreate("etp")
    val OpenMiningRoundTxLogEntry = String3.tryCreate("omr")
    val AppRewardTxLogEntry = String3.tryCreate("are")
    val MintTxLogEntry = String3.tryCreate("min")
    val TapTxLogEntry = String3.tryCreate("tap")
    val TransferTxLogEntry = String3.tryCreate("tra")
    val ValidatorRewardTxLogEntry = String3.tryCreate("vre")
    val SvRewardTxLogEntry = String3.tryCreate("sre")
    val VoteRequestTxLogEntry = String3.tryCreate("vot")
    val TransferCommandTxLogEntry = String3.tryCreate("trc")
    val AbortTransferInstructionTxLogEntry = String3.tryCreate("ati")
    // The following entry types correspond to entries that were removed from `scan_tx_log.proto`
    // Those entries might still exist in databases, but we don't produce new ones and we don't read them.
    // The values are only kept for documentation purposes.
    val Unused_SvRewardCollectedTxLogEntry = String3.tryCreate("src")
  }

  def encode(entry: TxLogEntry): (String3, String) = {
    import scalapb.json4s.JsonFormat
    val entryType = entry match {
      case _: ErrorTxLogEntry => EntryType.ErrorTxLogEntry
      case _: BalanceChangeTxLogEntry => EntryType.BalanceChangeTxLogEntry
      case _: ClosedMiningRoundTxLogEntry => EntryType.ClosedMiningRoundTxLogEntry
      case _: ExtraTrafficPurchaseTxLogEntry => EntryType.ExtraTrafficPurchaseTxLogEntry
      case _: OpenMiningRoundTxLogEntry => EntryType.OpenMiningRoundTxLogEntry
      case _: AppRewardTxLogEntry => EntryType.AppRewardTxLogEntry
      case _: MintTxLogEntry => EntryType.MintTxLogEntry
      case _: TapTxLogEntry => EntryType.TapTxLogEntry
      case _: TransferTxLogEntry => EntryType.TransferTxLogEntry
      case _: ValidatorRewardTxLogEntry => EntryType.ValidatorRewardTxLogEntry
      case _: SvRewardTxLogEntry => EntryType.SvRewardTxLogEntry
      case _: VoteRequestTxLogEntry => EntryType.VoteRequestTxLogEntry
      case _: TransferCommandTxLogEntry => EntryType.TransferCommandTxLogEntry
      case _: AbortTransferInstructionTxLogEntry => EntryType.AbortTransferInstructionTxLogEntry
      case _ => throw txEncodingFailed()
    }
    val jsonValue = entry match {
      case e: scalapb.GeneratedMessage => JsonFormat.toJsonString(e)
      case _ => throw txEncodingFailed()
    }
    (entryType, jsonValue)
  }
  def decode(entryType: String3, json: String): TxLogEntry = {
    import scalapb.json4s.JsonFormat.fromJsonString as from
    try {
      entryType match {
        case EntryType.ErrorTxLogEntry => from[ErrorTxLogEntry](json)
        case EntryType.BalanceChangeTxLogEntry => from[BalanceChangeTxLogEntry](json)
        case EntryType.ClosedMiningRoundTxLogEntry => from[ClosedMiningRoundTxLogEntry](json)
        case EntryType.ExtraTrafficPurchaseTxLogEntry => from[ExtraTrafficPurchaseTxLogEntry](json)
        case EntryType.OpenMiningRoundTxLogEntry => from[OpenMiningRoundTxLogEntry](json)
        case EntryType.AppRewardTxLogEntry => from[AppRewardTxLogEntry](json)
        case EntryType.MintTxLogEntry => from[MintTxLogEntry](json)
        case EntryType.TapTxLogEntry => from[TapTxLogEntry](json)
        case EntryType.TransferTxLogEntry => from[TransferTxLogEntry](json)
        case EntryType.ValidatorRewardTxLogEntry => from[ValidatorRewardTxLogEntry](json)
        case EntryType.SvRewardTxLogEntry => from[ValidatorRewardTxLogEntry](json)
        case EntryType.VoteRequestTxLogEntry => from[VoteRequestTxLogEntry](json)
        case EntryType.TransferCommandTxLogEntry => from[TransferCommandTxLogEntry](json)
        case EntryType.AbortTransferInstructionTxLogEntry =>
          from[AbortTransferInstructionTxLogEntry](json)
        case _ => throw txLogIsOfWrongType(entryType.str)
      }
    } catch {
      case _: RuntimeException => throw txDecodingFailed()
    }
  }

  trait RewardTxLogEntry extends TxLogEntry {
    def party: PartyId

    def amount: BigDecimal

    def round: Long
  }

  trait TransactionTxLogEntry extends TxLogEntry {
    def date: Option[Instant]
  }

  object Http {

    object TransferCommandStatus {
      val Created = "created"
      val Sent = "sent"
      val Failed = "failed"
    }

    private def toResponse(data: SenderAmount) = httpDef.SenderAmount(
      party = data.party.toProtoPrimitive,
      inputAmuletAmount = Some(Codec.encode(data.inputAmuletAmount)),
      inputAppRewardAmount = Some(Codec.encode(data.inputAppRewardAmount)),
      inputValidatorRewardAmount = Some(Codec.encode(data.inputValidatorRewardAmount)),
      inputSvRewardAmount = Some(Codec.encode(data.inputSvRewardAmount.getOrElse(BigDecimal(0)))),
      inputValidatorFaucetAmount = data.inputValidatorFaucetAmount.map(fa => Codec.encode(fa)),
      senderChangeAmount = Codec.encode(data.senderChangeAmount),
      senderChangeFee = Codec.encode(data.senderChangeFee),
      senderFee = Codec.encode(data.senderFee),
      holdingFees = Codec.encode(data.holdingFees),
    )

    private def toResponse(data: ReceiverAmount) = httpDef.ReceiverAmount(
      party = data.party.toProtoPrimitive,
      amount = Codec.encode(data.amount),
      receiverFee = Codec.encode(data.receiverFee),
    )

    private def toResponse(data: BalanceChange) = httpDef.BalanceChange(
      party = data.party.toProtoPrimitive,
      changeToInitialAmountAsOfRoundZero = Codec.encode(data.changeToInitialAmountAsOfRoundZero),
      changeToHoldingFeesRate = Codec.encode(data.changeToHoldingFeesRate),
    )

    private def toTransferResponseItem(entry: TransferTxLogEntry) =
      httpDef.TransactionHistoryResponseItem(
        transactionType = HttpTransactionType.Transfer,
        eventId = entry.eventId,
        offset = Some(entry.offset),
        domainId = entry.domainId.toProtoPrimitive,
        date = java.time.OffsetDateTime
          .ofInstant(entry.date.getOrElse(throw txMissingField()), ZoneOffset.UTC),
        transfer = Some(
          httpDef.Transfer(
            sender = toResponse(entry.sender.getOrElse(throw txMissingField())),
            receivers = entry.receivers.map(toResponse).toVector,
            balanceChanges = entry.balanceChanges.map(toResponse).toVector,
            description = Some(entry.description).filter(_.nonEmpty),
            transferInstructionReceiver =
              Some(entry.transferInstructionReceiver).filter(_.nonEmpty),
            transferInstructionAmount = entry.transferInstructionAmount.map(Codec.encode(_)),
            transferInstructionCid = Some(entry.transferInstructionCid).filter(_.nonEmpty),
            transferKind = entry.transferKind match {
              case TransferKind.Unrecognized(_) => None
              case TransferKind.TRANSFER_KIND_OTHER => None
              case TransferKind.TRANSFER_KIND_CREATE_TRANSFER_INSTRUCTION =>
                Some(httpDef.Transfer.TransferKind.members.CreateTransferInstruction)
              case TransferKind.TRANSFER_KIND_TRANSFER_INSTRUCTION_ACCEPT =>
                Some(httpDef.Transfer.TransferKind.members.TransferInstructionAccept)
              case TransferKind.TRANSFER_KIND_PREAPPROVAL_SEND =>
                Some(httpDef.Transfer.TransferKind.members.PreapprovalSend)
            },
          )
        ),
        round = Some(entry.round),
        amuletPrice = Some(Codec.encode(entry.amuletPrice)),
      )

    private def toTapResponseItem(entry: TapTxLogEntry) = httpDef.TransactionHistoryResponseItem(
      transactionType = HttpTransactionType.DevnetTap,
      eventId = entry.eventId,
      offset = Some(entry.offset),
      domainId = entry.domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime
        .ofInstant(entry.date.getOrElse(throw txMissingField()), ZoneOffset.UTC),
      tap = Some(
        httpDef.AmuletAmount(
          amuletOwner = entry.amuletOwner.toProtoPrimitive,
          amuletAmount = Codec.encode(entry.amuletAmount),
        )
      ),
      round = Some(entry.round),
      amuletPrice = Some(Codec.encode(entry.amuletPrice)),
    )

    private def toMintResponseItem(entry: MintTxLogEntry) = httpDef.TransactionHistoryResponseItem(
      transactionType = HttpTransactionType.Mint,
      eventId = entry.eventId,
      offset = Some(entry.offset),
      domainId = entry.domainId.toProtoPrimitive,
      date = java.time.OffsetDateTime
        .ofInstant(entry.date.getOrElse(throw txMissingField()), ZoneOffset.UTC),
      mint = Some(
        httpDef.AmuletAmount(
          amuletOwner = entry.amuletOwner.toProtoPrimitive,
          amuletAmount = Codec.encode(entry.amuletAmount),
        )
      ),
    )

    private def toAbortTransferInstructionResponseItem(entry: AbortTransferInstructionTxLogEntry) =
      httpDef.TransactionHistoryResponseItem(
        transactionType = HttpTransactionType.AbortTransferInstruction,
        eventId = entry.eventId,
        offset = Some(entry.offset),
        domainId = entry.domainId.toProtoPrimitive,
        date = java.time.OffsetDateTime
          .ofInstant(entry.date.getOrElse(throw txMissingField()), ZoneOffset.UTC),
        abortTransferInstruction = Some(
          httpDef.AbortTransferInstruction(
            abortKind = entry.transferAbortKind match {
              case TransferAbortKind.Unrecognized(_) =>
                sys.error(s"Unexpected transfer abort kind: ${entry.transferAbortKind}")
              case TransferAbortKind.TRANSFER_ABORT_KIND_RESERVED =>
                sys.error(s"Unexpected transfer abort kind: ${entry.transferAbortKind}")
              case TransferAbortKind.TRANSFER_ABORT_KIND_REJECT =>
                httpDef.AbortTransferInstruction.AbortKind.members.Reject
              case TransferAbortKind.TRANSFER_ABORT_KIND_WITHDRAW =>
                httpDef.AbortTransferInstruction.AbortKind.members.Withdraw
            },
            transferInstructionCid = entry.transferInstructionCid,
          )
        ),
      )

    def toResponseItem(entry: TransactionTxLogEntry): httpDef.TransactionHistoryResponseItem =
      entry match {
        case entry: TransferTxLogEntry => toTransferResponseItem(entry)
        case entry: TapTxLogEntry => toTapResponseItem(entry)
        case entry: MintTxLogEntry => toMintResponseItem(entry)
        case entry: AbortTransferInstructionTxLogEntry =>
          toAbortTransferInstructionResponseItem(entry)
        case _ => throw txLogIsOfWrongType(entry.getClass.getSimpleName)
      }

    def toResponse(
        status: TransferCommandTxLogEntry.Status
    ): httpDef.TransferCommandContractStatus =
      status match {
        case TransferCommandTxLogEntry.Status.Empty => throw txMissingField()
        case _: TransferCommandTxLogEntry.Status.Created =>
          httpDef.TransferCommandCreatedResponse(
            status = TransferCommandStatus.Created
          )
        case _: TransferCommandTxLogEntry.Status.Sent =>
          httpDef.TransferCommandSentResponse(
            status = TransferCommandStatus.Sent
          )
        case _: TransferCommandTxLogEntry.Status.Withdrawn =>
          httpDef.TransferCommandFailedResponse(
            status = TransferCommandStatus.Failed,
            failureKind = httpDef.TransferCommandFailedResponse.FailureKind.Withdrawn,
            reason = "The TransferCommand has been withdrawn by the sender",
          )
        case _: TransferCommandTxLogEntry.Status.Expired =>
          httpDef.TransferCommandFailedResponse(
            status = TransferCommandStatus.Failed,
            failureKind = httpDef.TransferCommandFailedResponse.FailureKind.Expired,
            reason = "The TransferCommand has expired",
          )
        case status: TransferCommandTxLogEntry.Status.Failed =>
          httpDef.TransferCommandFailedResponse(
            status = TransferCommandStatus.Failed,
            failureKind = httpDef.TransferCommandFailedResponse.FailureKind.Failed,
            reason = status.value.reason,
          )
      }
  }

  sealed trait TransactionType
  object TransactionType {
    case object Transfer extends TransactionType
    case object Mint extends TransactionType
    case object Tap extends TransactionType
  }

  def parseSenderAmount(
      arg: splice.amuletrules.AmuletRules_Transfer,
      res: splice.amuletrules.TransferResult,
  ): SenderAmount = {
    val sender = arg.transfer.sender
    val senderFee = parseOutputAmounts(arg, res)
      .map(_.senderFee)
      .sum

    SenderAmount(
      party = PartyId.tryFromProtoPrimitive(sender),
      inputAmuletAmount = res.summary.inputAmuletAmount,
      inputAppRewardAmount = res.summary.inputAppRewardAmount,
      inputValidatorRewardAmount = res.summary.inputValidatorRewardAmount,
      inputSvRewardAmount = Some(res.summary.inputSvRewardAmount),
      inputValidatorFaucetAmount =
        res.summary.inputValidatorFaucetAmount.toScala.map(BigDecimal(_)),
      senderChangeAmount = res.summary.senderChangeAmount,
      senderChangeFee = res.summary.senderChangeFee,
      senderFee = senderFee,
      holdingFees = res.summary.holdingFees,
    )
  }

  def parseReceiverAmounts(
      arg: splice.amuletrules.AmuletRules_Transfer,
      res: splice.amuletrules.TransferResult,
  ): Seq[ReceiverAmount] = {

    // Note: the same receiver party can appear multiple times in the transfer result
    // The code below merges amounts and fees for the same receiver, while preserving
    // the order of receivers.
    parseOutputAmounts(arg, res)
      .map(o =>
        new ReceiverAmount(
          party = PartyId.tryFromProtoPrimitive(o.output.receiver),
          amount = o.output.amount,
          receiverFee = o.receiverFee,
        )
      )
      .foldLeft(immutable.ListMap.empty[PartyId, ReceiverAmount])((acc, receiverAmount) =>
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

  /** Returns the input number modified such that it has the same number of decimal places as a daml decimal */
  private def setDamlDecimalScale(x: BigDecimal): BigDecimal =
    x.setScale(10, RoundingMode.HALF_EVEN)
}
