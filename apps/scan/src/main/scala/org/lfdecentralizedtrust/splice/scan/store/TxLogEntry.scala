// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import org.lfdecentralizedtrust.splice.store.StoreErrors

import org.lfdecentralizedtrust.splice.http.v0.definitions as httpDef
import com.digitalasset.canton.config.CantonRequireTypes.String3

trait TxLogEntry extends Product with Serializable {
  // Scan store uses the eventId for pagination
  def eventId: String
}

object TxLogEntry extends StoreErrors {

  object EntryType {
    val ErrorTxLogEntry = String3.tryCreate("err")
    val ClosedMiningRoundTxLogEntry = String3.tryCreate("cmr")
    val OpenMiningRoundTxLogEntry = String3.tryCreate("omr")
    val VoteRequestTxLogEntry = String3.tryCreate("vot")
    val TransferCommandTxLogEntry = String3.tryCreate("trc")
    // The following entry types correspond to entries that were removed from `scan_tx_log.proto`
    // Those entries might still exist in databases, but we don't produce new ones and we don't read them.
    // The values are only kept for documentation purposes.
    val Unused_SvRewardCollectedTxLogEntry = String3.tryCreate("src")
    val Unused_BalanceChangeTxLogEntry = String3.tryCreate("bac")
    val Unused_ExtraTrafficPurchaseTxLogEntry = String3.tryCreate("etp")
    val Unused_AppRewardTxLogEntry = String3.tryCreate("are")
    val Unused_MintTxLogEntry = String3.tryCreate("min")
    val Unused_TapTxLogEntry = String3.tryCreate("tap")
    val Unused_TransferTxLogEntry = String3.tryCreate("tra")
    val Unused_ValidatorRewardTxLogEntry = String3.tryCreate("vre")
    val Unused_SvRewardTxLogEntry = String3.tryCreate("sre")
    val Unused_AbortTransferInstructionTxLogEntry = String3.tryCreate("ati")
  }

  def encode(entry: TxLogEntry): (String3, String) = {
    import scalapb.json4s.JsonFormat
    val entryType = entry match {
      case _: ErrorTxLogEntry => EntryType.ErrorTxLogEntry
      case _: ClosedMiningRoundTxLogEntry => EntryType.ClosedMiningRoundTxLogEntry
      case _: OpenMiningRoundTxLogEntry => EntryType.OpenMiningRoundTxLogEntry
      case _: VoteRequestTxLogEntry => EntryType.VoteRequestTxLogEntry
      case _: TransferCommandTxLogEntry => EntryType.TransferCommandTxLogEntry
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
        case EntryType.ClosedMiningRoundTxLogEntry => from[ClosedMiningRoundTxLogEntry](json)
        case EntryType.OpenMiningRoundTxLogEntry => from[OpenMiningRoundTxLogEntry](json)
        case EntryType.VoteRequestTxLogEntry => from[VoteRequestTxLogEntry](json)
        case EntryType.TransferCommandTxLogEntry => from[TransferCommandTxLogEntry](json)
        case _ => throw txLogIsOfWrongType(entryType.str)
      }
    } catch {
      case _: RuntimeException => throw txDecodingFailed()
    }
  }

  object Http {

    object TransferCommandStatus {
      val Created = "created"
      val Sent = "sent"
      val Failed = "failed"
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
}
