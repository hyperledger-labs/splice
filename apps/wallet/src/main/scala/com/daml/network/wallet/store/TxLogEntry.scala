// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.store

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.Identifier
import com.daml.network.history.{
  AnsRules_CollectEntryRenewalPayment,
  AnsRules_CollectInitialEntryPayment,
  AmuletExpire,
  AmuletRules_BuyMemberTraffic,
  LockedAmuletExpireAmulet,
  LockedAmuletOwnerExpireLock,
  LockedAmuletUnlock,
}
import com.daml.network.http.v0.definitions as httpDef
import com.daml.network.http.v0.definitions.{
  GetBuyTrafficRequestStatusResponse,
  GetTransferOfferStatusResponse,
}
import com.daml.network.store.StoreErrors
import com.daml.network.util.{Codec, ExerciseNodeCompanion}
import com.digitalasset.canton.config.CantonRequireTypes.String3

import java.time.{Instant, ZoneOffset}

trait TxLogEntry

object TxLogEntry extends StoreErrors {
  def encode(entry: TxLogEntry): (String3, String) = {
    import scalapb.json4s.JsonFormat
    val entryType = entry match {
      case _: UnknownTxLogEntry => EntryType.UnknownTxLogEntry
      case _: BalanceChangeTxLogEntry => EntryType.BalanceChangeTxLogEntry
      case _: TransferTxLogEntry => EntryType.TransferTxLogEntry
      case _: NotificationTxLogEntry => EntryType.NotificationTxLogEntry
      case _: TransferOfferTxLogEntry => EntryType.TransferOfferTxLogEntry
      case _: BuyTrafficRequestTxLogEntry => EntryType.BuyTrafficRequestTxLogEntry
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
        case EntryType.UnknownTxLogEntry => from[UnknownTxLogEntry](json)
        case EntryType.BalanceChangeTxLogEntry => from[BalanceChangeTxLogEntry](json)
        case EntryType.TransferTxLogEntry => from[TransferTxLogEntry](json)
        case EntryType.NotificationTxLogEntry => from[NotificationTxLogEntry](json)
        case EntryType.TransferOfferTxLogEntry => from[TransferOfferTxLogEntry](json)
        case EntryType.BuyTrafficRequestTxLogEntry => from[BuyTrafficRequestTxLogEntry](json)
        case _ => throw txDecodingFailed()
      }
    } catch {
      case _: RuntimeException => throw txDecodingFailed()
    }
  }

  /** TxLogEntries that are part of the transaction history in the UI */
  trait TransactionHistoryTxLogEntry extends TxLogEntry {
    // The UI uses the event ID for pagination, see WalletServiceContext.tsx#listTransactions
    def eventId: String
  }

  object LogId {
    val TransactionHistoryTxLog: String3 = String3.tryCreate("txh")
    val TransferOfferTxLog: String3 = String3.tryCreate("tof")
    val BuyTrafficRequestTxLog: String3 = String3.tryCreate("btr")
  }

  object EntryType {
    val UnknownTxLogEntry: String3 = String3.tryCreate("unk")
    val TransferOfferTxLogEntry: String3 = String3.tryCreate("tof")
    val BuyTrafficRequestTxLogEntry: String3 = String3.tryCreate("btr")
    val TransferTxLogEntry: String3 = String3.tryCreate("tra")
    val BalanceChangeTxLogEntry: String3 = String3.tryCreate("bal")
    val NotificationTxLogEntry: String3 = String3.tryCreate("not")
  }

  object Http {

    object TransactionType {
      val Unknown = "unknown"
      val Transfer = "transfer"
      val BalanceChange = "balance_change"
      val Notification = "notification"
    }

    object TransferOfferStatus {
      val Created = "created"
      val Accepted = "accepted"
      val Completed = "completed"
      val Failed = "failed"
    }

    object BuyTrafficRequestStatus {
      val Created = "created"
      val Completed = "completed"
      val Failed = "failed"
    }

    // Note: deserialization is only needed for the Canton console
    def fromResponseItem(
        item: httpDef.ListTransactionsResponseItem
    ): Either[String, TransactionHistoryTxLogEntry] = {
      import httpDef.ListTransactionsResponseItem.members as members
      item match {
        case members.TransferResponseItem(transfer) =>
          transferFromResponseItem(transfer)
        case members.BalanceChangeResponseItem(balanceChange) =>
          balanceChangeFromResponseItem(balanceChange)
        case members.NotificationResponseItem(notification) =>
          notificationFromResponseItem(notification)
        case members.UnknownResponseItem(unknown) =>
          unknownFromResponseItem(unknown)
        case _ => Left(s"Unknown item $item")
      }
    }

    def toResponseItem(entry: TransactionHistoryTxLogEntry): httpDef.ListTransactionsResponseItem =
      entry match {
        case e: UnknownTxLogEntry => toUnknownResponseItem(e)
        case e: TransferTxLogEntry => toTransferResponseItem(e)
        case e: BalanceChangeTxLogEntry => toBalanceChangeResponseItem(e)
        case e: NotificationTxLogEntry => toNotificationResponseItem(e)
        case _ => throw txEncodingFailed()
      }

    private def toUnknownResponseItem(
        entry: UnknownTxLogEntry
    ): httpDef.ListTransactionsResponseItem =
      httpDef.UnknownResponseItem(
        transactionType = TransactionType.Unknown,
        transactionSubtype = httpDef.TransactionSubtype("unknown", "unknown"),
        eventId = entry.eventId,
        date = java.time.OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC),
      )

    private def unknownFromResponseItem(
        item: httpDef.UnknownResponseItem
    ): Either[String, UnknownTxLogEntry] = {
      Right(
        UnknownTxLogEntry(
          eventId = item.eventId
        )
      )
    }

    private def toTransferResponseItem(
        entry: TransferTxLogEntry
    ): httpDef.ListTransactionsResponseItem = {
      val subtype = entry.subtype.getOrElse(throw txMissingField())
      val date = entry.date.getOrElse(throw txMissingField())
      val sender = entry.sender.getOrElse(throw txMissingField())

      httpDef.TransferResponseItem(
        transactionType = TransactionType.Transfer,
        transactionSubtype = toSubtypeReponseItem(subtype),
        eventId = entry.eventId,
        date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
        provider = entry.provider,
        sender = httpDef.PartyAndAmount(sender.party, Codec.encode(sender.amount)),
        receivers = entry.receivers
          .map(r => httpDef.PartyAndAmount(r.party, Codec.encode(r.amount)))
          .toVector,
        holdingFees = Codec.encode(entry.senderHoldingFees),
        amuletPrice = Codec.encode(entry.amuletPrice),
        appRewardsUsed = Codec.encode(entry.appRewardsUsed),
        validatorRewardsUsed = Codec.encode(entry.validatorRewardsUsed),
        svRewardsUsed = Codec.encode(entry.svRewardsUsed.getOrElse(BigDecimal(0))),
      )
    }

    private def transferFromResponseItem(
        item: httpDef.TransferResponseItem
    ): Either[String, TransferTxLogEntry] = {
      for {
        subtype <- subtypeFromResponseItem(item.transactionSubtype)
        sender = item.sender
        senderAmount <- Codec.decode(Codec.BigDecimal)(sender.amount)
        receivers <- item.receivers.traverse(r =>
          for {
            amount <- Codec.decode(Codec.BigDecimal)(r.amount)
          } yield PartyAndAmount(r.party, amount)
        )
        senderHoldingFees <- Codec.decode(Codec.BigDecimal)(item.holdingFees)
        amuletPrice <- Codec.decode(Codec.BigDecimal)(item.amuletPrice)
        appRewardsUsed <- Codec.decode(Codec.BigDecimal)(item.appRewardsUsed)
        validatorRewardsUsed <- Codec.decode(Codec.BigDecimal)(item.validatorRewardsUsed)
        svRewardsUsed <- Codec.decode(Codec.BigDecimal)(item.svRewardsUsed)
      } yield TransferTxLogEntry(
        eventId = item.eventId,
        subtype = Some(subtype),
        date = Some(item.date.toInstant),
        provider = item.provider,
        sender = Some(PartyAndAmount(sender.party, senderAmount)),
        receivers = receivers,
        senderHoldingFees = senderHoldingFees,
        amuletPrice = amuletPrice,
        appRewardsUsed = appRewardsUsed,
        validatorRewardsUsed = validatorRewardsUsed,
        svRewardsUsed = Some(svRewardsUsed),
      )
    }

    private def toBalanceChangeResponseItem(
        entry: BalanceChangeTxLogEntry
    ): httpDef.ListTransactionsResponseItem = {
      val subtype = entry.subtype.getOrElse(throw txMissingField())
      val date = entry.date.getOrElse(throw txMissingField())

      httpDef.BalanceChangeResponseItem(
        transactionType = TransactionType.BalanceChange,
        transactionSubtype = toSubtypeReponseItem(subtype),
        eventId = entry.eventId,
        date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
        receivers = Vector(
          httpDef.PartyAndAmount(entry.receiver, Codec.encode(entry.amount))
        ),
        amuletPrice = Codec.encode(entry.amuletPrice),
      )
    }

    private def balanceChangeFromResponseItem(
        item: httpDef.BalanceChangeResponseItem
    ): Either[String, BalanceChangeTxLogEntry] = {
      for {
        receiverAndAmount <- item.receivers.headOption.toRight("No receivers")
        amuletPrice <- Codec.decode(Codec.BigDecimal)(item.amuletPrice)
        subtype <- subtypeFromResponseItem(item.transactionSubtype)
      } yield BalanceChangeTxLogEntry(
        subtype = Some(subtype),
        eventId = item.eventId,
        date = Some(item.date.toInstant),
        receiver = receiverAndAmount.party,
        amount = Codec.tryDecode(Codec.BigDecimal)(receiverAndAmount.amount),
        amuletPrice = amuletPrice,
      )
    }

    private def toNotificationResponseItem(
        entry: NotificationTxLogEntry
    ): httpDef.ListTransactionsResponseItem = {
      val subtype = entry.subtype.getOrElse(throw txMissingField())
      val date = entry.date.getOrElse(throw txMissingField())

      httpDef.NotificationResponseItem(
        transactionType = TransactionType.Notification,
        transactionSubtype = toSubtypeReponseItem(subtype),
        eventId = entry.eventId,
        date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
        details = entry.details,
      )
    }

    private def notificationFromResponseItem(
        item: httpDef.NotificationResponseItem
    ): Either[String, NotificationTxLogEntry] = {
      for {
        subtype <- subtypeFromResponseItem(item.transactionSubtype)
      } yield NotificationTxLogEntry(
        subtype = Some(subtype),
        eventId = item.eventId,
        date = Some(item.date.toInstant),
        details = item.details,
      )
    }

    private def toSubtypeReponseItem(
        subtype: TransactionSubtype
    ): httpDef.TransactionSubtype =
      httpDef.TransactionSubtype(
        templateId = s"${subtype.packageId}:${subtype.moduleName}:${subtype.entityName}",
        choice = subtype.choice,
        amuletOperation =
          if (subtype.amuletOperation.isEmpty) None else Some(subtype.amuletOperation),
      )

    private def subtypeFromResponseItem(
        item: httpDef.TransactionSubtype
    ): Either[String, TransactionSubtype] = for {
      templateId <- item.templateId.split(":") match {
        case Array(packageId, moduleName, entityName) =>
          Right((packageId, moduleName, entityName))
        case _ => Left("Invalid templateId")
      }
    } yield TransactionSubtype(
      packageId = templateId._1,
      moduleName = templateId._2,
      entityName = templateId._3,
      choice = item.choice,
      amuletOperation = item.amuletOperation.getOrElse(""),
    )

    def toStatusResponse(
        status: TransferOfferTxLogEntry.Status
    ): GetTransferOfferStatusResponse = status match {
      case TransferOfferTxLogEntry.Status.Empty => throw txMissingField()
      case status: TransferOfferTxLogEntry.Status.Created => toTOSCreatedResponse(status.value)
      case status: TransferOfferTxLogEntry.Status.Accepted => toTOSAcceptedResponse(status.value)
      case status: TransferOfferTxLogEntry.Status.Completed => toTOSCompletedResponse(status.value)
      case _: TransferOfferTxLogEntry.Status.Rejected => toTOSRejectedResponse
      case status: TransferOfferTxLogEntry.Status.Withdrawn => toTOSWithdrawnResponse(status.value)
      case _: TransferOfferTxLogEntry.Status.Expired => toTOSExpiredResponse
    }

    private def toTOSCreatedResponse(
        status: TransferOfferStatusCreated
    ): GetTransferOfferStatusResponse =
      httpDef.TransferOfferCreatedResponse(
        status = TransferOfferStatus.Created,
        transactionId = status.transactionId,
        contractId = status.contractId,
      )

    private def toTOSAcceptedResponse(
        status: TransferOfferStatusAccepted
    ): GetTransferOfferStatusResponse =
      httpDef.TransferOfferAcceptedResponse(
        status = TransferOfferStatus.Accepted,
        transactionId = status.transactionId,
        contractId = status.contractId,
      )

    private def toTOSCompletedResponse(
        status: TransferOfferStatusCompleted
    ): GetTransferOfferStatusResponse =
      httpDef.TransferOfferCompletedResponse(
        status = TransferOfferStatus.Completed,
        transactionId = status.transactionId,
        contractId = status.contractId,
      )

    private def toTOSRejectedResponse: GetTransferOfferStatusResponse =
      httpDef.TransferOfferFailedResponse(
        status = TransferOfferStatus.Failed,
        failureKind = httpDef.TransferOfferFailedResponse.FailureKind.Rejected,
      )

    private def toTOSWithdrawnResponse(
        status: TransferOfferStatusWithdrawn
    ): GetTransferOfferStatusResponse =
      httpDef.TransferOfferFailedResponse(
        status = TransferOfferStatus.Failed,
        failureKind = httpDef.TransferOfferFailedResponse.FailureKind.Withdrawn,
        withdrawnReason = Some(status.reason),
      )

    private def toTOSExpiredResponse: GetTransferOfferStatusResponse =
      httpDef.TransferOfferFailedResponse(
        status = TransferOfferStatus.Failed,
        failureKind = httpDef.TransferOfferFailedResponse.FailureKind.Expired,
      )

    def toStatusResponse(
        status: BuyTrafficRequestTxLogEntry.Status
    ): GetBuyTrafficRequestStatusResponse = status match {
      case BuyTrafficRequestTxLogEntry.Status.Empty => throw txMissingField()
      case _: BuyTrafficRequestTxLogEntry.Status.Created => toBTRCreatedResponse
      case status: BuyTrafficRequestTxLogEntry.Status.Completed =>
        toBTRCompletedResponse(status.value)
      case status: BuyTrafficRequestTxLogEntry.Status.Rejected =>
        toBTRRejectedResponse(status.value)
      case _: BuyTrafficRequestTxLogEntry.Status.Expired => toBTRExpiredResponse
    }

    private def toBTRCreatedResponse: httpDef.GetBuyTrafficRequestStatusResponse = {
      httpDef.BuyTrafficRequestCreatedResponse(
        status = BuyTrafficRequestStatus.Created
      )
    }
    private def toBTRCompletedResponse(
        status: BuyTrafficRequestStatusCompleted
    ): httpDef.GetBuyTrafficRequestStatusResponse =
      httpDef.BuyTrafficRequestCompletedResponse(
        status = BuyTrafficRequestStatus.Completed,
        transactionId = status.transactionId,
      )

    private def toBTRRejectedResponse(
        status: BuyTrafficRequestStatusRejected
    ): httpDef.GetBuyTrafficRequestStatusResponse =
      httpDef.BuyTrafficRequestFailedResponse(
        status = BuyTrafficRequestStatus.Failed,
        failureReason = httpDef.BuyTrafficRequestFailedResponse.FailureReason.Rejected,
        rejectionReason = Some(status.reason),
      )

    private def toBTRExpiredResponse: httpDef.GetBuyTrafficRequestStatusResponse = {
      httpDef.BuyTrafficRequestFailedResponse(
        status = BuyTrafficRequestStatus.Failed,
        failureReason = httpDef.BuyTrafficRequestFailedResponse.FailureReason.Expired,
      )
    }
  }

  sealed abstract class TransactionSubtypeDef(
      val companion: ExerciseNodeCompanion,
      val amuletOperation: Option[String],
  ) {
    val templateId: Identifier = companion.template.TEMPLATE_ID
    val choice: String = companion.choice.name

    def toProto: TransactionSubtype =
      new TransactionSubtype(
        packageId = templateId.getPackageId,
        moduleName = templateId.getModuleName,
        entityName = templateId.getEntityName,
        choice = choice,
        amuletOperation = amuletOperation.getOrElse(""),
      )
  }

  sealed abstract class TransferTransactionSubtype(
      companion: ExerciseNodeCompanion
  ) extends TransactionSubtypeDef(companion, None)

  object TransferTransactionSubtype {
    case object P2PPaymentCompleted
        extends TransferTransactionSubtype(AcceptedTransferOffer_Complete)
    case object AppPaymentAccepted extends TransferTransactionSubtype(AppPaymentRequest_Accept)
    case object AppPaymentCollected extends TransferTransactionSubtype(AcceptedAppPayment_Collect)
    case object SubscriptionInitialPaymentAccepted
        extends TransferTransactionSubtype(SubscriptionRequest_AcceptAndMakePayment)
    case object SubscriptionInitialPaymentCollected
        extends TransferTransactionSubtype(SubscriptionInitialPayment_Collect)
    case object SubscriptionPaymentAccepted
        extends TransferTransactionSubtype(SubscriptionIdleState_MakePayment)
    case object SubscriptionPaymentCollected
        extends TransferTransactionSubtype(SubscriptionPayment_Collect)
    case object WalletAutomation extends TransferTransactionSubtype(WalletAppInstall_ExecuteBatch)
    case object ExtraTrafficPurchase
        extends TransferTransactionSubtype(AmuletRules_BuyMemberTraffic)
    case object InitialEntryPaymentCollection
        extends TransferTransactionSubtype(AnsRules_CollectInitialEntryPayment)
    case object EntryRenewalPaymentCollection
        extends TransferTransactionSubtype(AnsRules_CollectEntryRenewalPayment)
    case object Transfer extends TransferTransactionSubtype(com.daml.network.history.Transfer)

    val values: Map[String, TransferTransactionSubtype] = Set[TransferTransactionSubtype](
      P2PPaymentCompleted,
      AppPaymentAccepted,
      AppPaymentCollected,
      SubscriptionInitialPaymentAccepted,
      SubscriptionInitialPaymentCollected,
      SubscriptionPaymentAccepted,
      SubscriptionPaymentCollected,
      WalletAutomation,
      ExtraTrafficPurchase,
      InitialEntryPaymentCollection,
      EntryRenewalPaymentCollection,
      Transfer,
    ).map(txSubtype => txSubtype.choice -> txSubtype).toMap

    def find(choiceName: String): Option[TransferTransactionSubtype] =
      values.get(choiceName)
  }

  sealed abstract class BalanceChangeTransactionSubtype(
      companion: ExerciseNodeCompanion
  ) extends TransactionSubtypeDef(companion, None)

  object BalanceChangeTransactionSubtype {

    case object Tap extends BalanceChangeTransactionSubtype(com.daml.network.history.Tap)
    case object Mint extends BalanceChangeTransactionSubtype(com.daml.network.history.Mint)
    case object AppPaymentRejected
        extends BalanceChangeTransactionSubtype(AcceptedAppPayment_Reject)
    case object AppPaymentExpired extends BalanceChangeTransactionSubtype(AcceptedAppPayment_Expire)
    case object SubscriptionInitialPaymentRejected
        extends BalanceChangeTransactionSubtype(SubscriptionInitialPayment_Reject)
    case object SubscriptionInitialPaymentExpired
        extends BalanceChangeTransactionSubtype(SubscriptionInitialPayment_Expire)
    case object SubscriptionPaymentRejected
        extends BalanceChangeTransactionSubtype(SubscriptionPayment_Reject)
    case object SubscriptionPaymentExpired
        extends BalanceChangeTransactionSubtype(SubscriptionPayment_Expire)
    case object LockedAmuletUnlocked extends BalanceChangeTransactionSubtype(LockedAmuletUnlock)
    case object LockedAmuletOwnerExpired
        extends BalanceChangeTransactionSubtype(LockedAmuletOwnerExpireLock)
    case object LockedAmuletExpired
        extends BalanceChangeTransactionSubtype(LockedAmuletExpireAmulet)
    case object AmuletExpired extends BalanceChangeTransactionSubtype(AmuletExpire)

    val values: Map[String, BalanceChangeTransactionSubtype] =
      Set[BalanceChangeTransactionSubtype](
        Tap,
        Mint,
        AppPaymentRejected,
        AppPaymentExpired,
        SubscriptionInitialPaymentRejected,
        SubscriptionInitialPaymentExpired,
        SubscriptionPaymentRejected,
        SubscriptionPaymentExpired,
        LockedAmuletUnlocked,
        LockedAmuletOwnerExpired,
        LockedAmuletExpired,
        AmuletExpired,
      ).map(txSubtype => txSubtype.choice -> txSubtype).toMap

    def find(choiceName: String): Option[BalanceChangeTransactionSubtype] =
      values.get(choiceName)
  }

  sealed abstract class NotificationTransactionSubtype(
      companion: ExerciseNodeCompanion,
      amuletOperation: Option[String],
  ) extends TransactionSubtypeDef(companion, amuletOperation)
  object NotificationTransactionSubtype {
    case object DirectTransferFailed
        extends NotificationTransactionSubtype(
          WalletAppInstall_ExecuteBatch,
          Some("CO_CompleteAcceptedTransfer"),
        )
    case object SubscriptionPaymentFailed
        extends NotificationTransactionSubtype(
          WalletAppInstall_ExecuteBatch,
          Some("CO_SubscriptionMakePayment"),
        )
    case object SubscriptionExpired
        extends NotificationTransactionSubtype(SubscriptionIdleState_ExpireSubscription, None)

    val values: Map[(String, Option[String]), NotificationTransactionSubtype] =
      Set[NotificationTransactionSubtype](
        DirectTransferFailed,
        SubscriptionPaymentFailed,
        SubscriptionExpired,
      ).map(txSubtype =>
        (
          txSubtype.choice,
          txSubtype.amuletOperation,
        ) -> txSubtype
      ).toMap
    def find(
        choiceName: String,
        amuletOperationConstructor: Option[String],
    ): Option[NotificationTransactionSubtype] =
      values.get((choiceName, amuletOperationConstructor))
  }

}
