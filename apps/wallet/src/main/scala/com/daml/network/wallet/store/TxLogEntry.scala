package com.daml.network.wallet.store

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.Choice
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.wallet.transferoffer as transferCodegen
import com.daml.network.history.{
  CnsRules_CollectEntryRenewalPayment,
  CnsRules_CollectInitialEntryPayment,
  CoinExpire,
  CoinRules_BuyMemberTraffic,
  LockedCoinExpireCoin,
  LockedCoinOwnerExpireLock,
  LockedCoinUnlock,
}
import com.daml.network.http.v0.definitions as httpDef
import com.daml.network.http.v0.definitions.GetTransferOfferStatusResponse
import com.daml.network.store.StoreErrors
import com.daml.network.util.{Codec, ExerciseNodeCompanion}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import spray.json.{
  DefaultJsonProtocol,
  DeserializationException,
  JsNull,
  JsObject,
  JsString,
  JsValue,
  RootJsonFormat,
}

import java.time.{Instant, ZoneOffset}

sealed trait TxLogEntry

sealed trait TxLogEntryCompanion {

  /** A short string that identifies the TxLogEntry type in the database.
    * Must not change, unless the store descriptor changes at the same time.
    */
  def dbType: String3

  def txLogId: String3
}

object TxLogEntry {
  def encode(entry: TxLogEntry): (String3, JsValue) = {
    import spray.json.*
    import JsonProtocol.*
    entry match {
      case e: Unknown => (Unknown.dbType, e.toJson)
      case e: Notification => (Notification.dbType, e.toJson)
      case e: Transfer => (Transfer.dbType, e.toJson)
      case e: BalanceChange => (BalanceChange.dbType, e.toJson)
      case e: TransferOffer => (TransferOffer.dbType, e.toJson)
      case e: BuyTrafficRequest => (BuyTrafficRequest.dbType, e.toJson)
    }
  }
  def decode(dbType: String3, json: JsValue): TxLogEntry = {
    import TxLogEntry.JsonProtocol.*
    try {
      dbType match {
        case Unknown.dbType => json.convertTo[Unknown]
        case Notification.dbType => json.convertTo[Notification]
        case Transfer.dbType => json.convertTo[Transfer]
        case BalanceChange.dbType => json.convertTo[BalanceChange]
        case TransferOffer.dbType => json.convertTo[TransferOffer]
        case BuyTrafficRequest.dbType => json.convertTo[BuyTrafficRequest]
        case _ => throw txDecodingFailed()
      }
    } catch {
      case _: DeserializationException => throw txDecodingFailed()
    }
  }

  object JsonProtocol extends DefaultJsonProtocol with StoreErrors {
    import com.digitalasset.canton.http.json.JsonProtocol.*

    implicit val notificationSubtypeFormat
        : RootJsonFormat[Notification.NotificationTransactionSubtype] =
      new RootJsonFormat[Notification.NotificationTransactionSubtype] {
        override def write(obj: Notification.NotificationTransactionSubtype): JsValue = {
          JsObject(
            "choice" -> JsString(obj.choice.name),
            "coinOperation" -> obj.coinOperation.fold[JsValue](JsNull)(JsString(_)),
          )
        }

        override def read(json: JsValue): Notification.NotificationTransactionSubtype = {
          val fields = json.asJsObject.fields
          val choice = fields("choice").convertTo[String]
          val coinOperation = fields("coinOperation") match {
            case JsNull => None
            case f: JsString => Some(f.value)
            case _ => throw txDecodingFailed()
          }
          Notification.NotificationTransactionSubtype
            .find(choice, coinOperation)
            .getOrElse(throw txDecodingFailed())
        }
      }

    implicit val transferSubtypeFormat: RootJsonFormat[Transfer.TransferTransactionSubtype] =
      new RootJsonFormat[Transfer.TransferTransactionSubtype] {
        override def write(obj: Transfer.TransferTransactionSubtype): JsValue = {
          JsObject(
            "choice" -> JsString(obj.choice.name)
          )
        }

        override def read(json: JsValue): Transfer.TransferTransactionSubtype = {
          val fields = json.asJsObject.fields
          val choice = fields("choice").convertTo[String]
          Transfer.TransferTransactionSubtype
            .find(choice)
            .getOrElse(throw txDecodingFailed())
        }
      }

    implicit val balanceChangeSubtypeFormat
        : RootJsonFormat[BalanceChange.BalanceChangeTransactionSubtype] =
      new RootJsonFormat[BalanceChange.BalanceChangeTransactionSubtype] {
        override def write(obj: BalanceChange.BalanceChangeTransactionSubtype): JsValue = {
          JsObject(
            "choice" -> JsString(obj.choice.name)
          )
        }

        override def read(json: JsValue): BalanceChange.BalanceChangeTransactionSubtype = {
          val fields = json.asJsObject.fields
          val choice = fields("choice").convertTo[String]
          BalanceChange.BalanceChangeTransactionSubtype
            .find(choice)
            .getOrElse(throw txDecodingFailed())
        }
      }

    implicit val transferOfferStatusFormat: RootJsonFormat[TransferOfferStatus] =
      new RootJsonFormat[TransferOfferStatus] {
        override def write(obj: TransferOfferStatus): JsValue = {
          obj match {
            case TransferOfferStatus.Created(contractId, transactionId) =>
              JsObject(
                "status" -> JsString("Created"),
                "transactionId" -> JsString(transactionId),
                "contractId" -> JsString(contractId.contractId),
              )
            case TransferOfferStatus.Accepted(contractId, transactionId) =>
              JsObject(
                "status" -> JsString("Accepted"),
                "transactionId" -> JsString(transactionId),
                "contractId" -> JsString(contractId.contractId),
              )
            case TransferOfferStatus.Completed(contractId, transactionId) =>
              JsObject(
                "status" -> JsString("Completed"),
                "transactionId" -> JsString(transactionId),
                "contractId" -> JsString(contractId.contractId),
              )
            case TransferOfferStatus.Rejected =>
              JsObject(
                "status" -> JsString("Rejected")
              )
            case TransferOfferStatus.Withdrawn(reason) =>
              JsObject(
                "status" -> JsString("Withdrawn"),
                "withdrawnReason" -> JsString(reason),
              )
            case TransferOfferStatus.Expired =>
              JsObject(
                "status" -> JsString("Expired")
              )
          }
        }

        override def read(json: JsValue): TransferOfferStatus = {
          val fields = json.asJsObject.fields
          val status = fields("status").convertTo[String]
          status match {
            case "Created" =>
              val contractId = fields("contractId").convertTo[String]
              val transactionId = fields("transactionId").convertTo[String]
              TransferOfferStatus.Created(
                contractId = new transferCodegen.TransferOffer.ContractId(contractId),
                transactionId = transactionId,
              )
            case "Accepted" =>
              val contractId = fields("contractId").convertTo[String]
              val transactionId = fields("transactionId").convertTo[String]
              TransferOfferStatus.Accepted(
                contractId = new transferCodegen.AcceptedTransferOffer.ContractId(contractId),
                transactionId = transactionId,
              )
            case "Completed" =>
              val contractId = fields("contractId").convertTo[String]
              val transactionId = fields("transactionId").convertTo[String]
              TransferOfferStatus.Completed(
                contractId = new cc.coin.Coin.ContractId(contractId),
                transactionId = transactionId,
              )
            case "Rejected" =>
              TransferOfferStatus.Rejected
            case "Withdrawn" =>
              val reason = fields("withdrawnReason").convertTo[String]
              TransferOfferStatus.Withdrawn(reason)
            case "Expired" =>
              TransferOfferStatus.Expired
          }
        }
      }

    implicit val buyTrafficRequestStatusFormat: RootJsonFormat[BuyTrafficRequestStatus] =
      new RootJsonFormat[BuyTrafficRequestStatus] {
        override def write(obj: BuyTrafficRequestStatus): JsValue = {
          obj match {
            case BuyTrafficRequestStatus.Created =>
              JsObject(
                "status" -> JsString("Created")
              )
            case BuyTrafficRequestStatus.Completed(transactionId) =>
              JsObject(
                "status" -> JsString("Completed"),
                "transactionId" -> JsString(transactionId),
              )
            case BuyTrafficRequestStatus.Rejected(reason) =>
              JsObject(
                "status" -> JsString("Rejected"),
                "rejectionReason" -> JsString(reason),
              )
            case BuyTrafficRequestStatus.Expired =>
              JsObject(
                "status" -> JsString("Expired")
              )
          }
        }

        override def read(json: JsValue): BuyTrafficRequestStatus = {
          val fields = json.asJsObject.fields
          val status = fields("status").convertTo[String]
          status match {
            case "Created" =>
              BuyTrafficRequestStatus.Created
            case "Completed" =>
              val transactionId = fields("transactionId").convertTo[String]
              BuyTrafficRequestStatus.Completed(transactionId)
            case "Rejected" =>
              val reason = fields("rejectionReason").convertTo[String]
              BuyTrafficRequestStatus.Rejected(reason)
            case "Expired" =>
              BuyTrafficRequestStatus.Expired
          }
        }
      }

    implicit val unknownEntryFormat: RootJsonFormat[Unknown] = jsonFormat1(Unknown.apply)
    implicit val notificationEntryFormat: RootJsonFormat[Notification] =
      jsonFormat4(Notification.apply)
    implicit val transferEntryFormat: RootJsonFormat[Transfer] = jsonFormat10(Transfer.apply)
    implicit val balanceChangeEntryFormat: RootJsonFormat[BalanceChange] =
      jsonFormat6(BalanceChange.apply)
    implicit val transferOfferEntryFormat: RootJsonFormat[TransferOffer] =
      jsonFormat4(TransferOffer.apply)
    implicit val buyTrafficRequestEntryFormat: RootJsonFormat[BuyTrafficRequest] =
      jsonFormat3(BuyTrafficRequest.apply)
  }

  /** TxLogEntries that are part of the transaction history in the UI */
  sealed trait TransactionHistoryTxLogEntry extends TxLogEntry {
    // The UI uses the event ID for pagination, see WalletServiceContext.tsx#listTransactions
    def setEventId(eventId: String): TxLogEntry
    def eventId: String

    def toResponseItem: httpDef.ListTransactionsResponseItem
  }
  object TransactionHistoryTxLogEntry {
    val txLogId: String3 = String3.tryCreate("txh")
  }
  sealed trait TransactionHistoryTxLogEntryCompanion extends TxLogEntryCompanion {

    /** The transaction type as used in the wallet API.
      * If you change this, you need to update the wallet UI as well.
      */
    def transactionType: String
  }

  /** TxLogEntries that are NOT part of the transaction history in the UI */
  sealed trait NonTxnHistoryTxLogEntry extends TxLogEntry

  sealed trait TransferOfferTxLogEntry extends NonTxnHistoryTxLogEntry
  sealed trait BuyTrafficRequestTxLogEntry extends NonTxnHistoryTxLogEntry

  sealed abstract class TransactionSubtype(
      val companion: ExerciseNodeCompanion,
      coinOperation: Option[String],
  ) {
    val templateId: Identifier = companion.template.TEMPLATE_ID
    val choice: Choice[companion.Tpl, companion.Arg, companion.Res] = companion.choice

    def toResponseItem: httpDef.TransactionSubtype = httpDef.TransactionSubtype(
      templateId =
        s"${templateId.getPackageId}:${templateId.getModuleName}:${templateId.getEntityName}",
      choice = choice.name,
      coinOperation = coinOperation,
    )
  }

  /* Unknown event, caused the parser failing to parse a transaction tree */
  final case class Unknown(eventId: String) extends TransactionHistoryTxLogEntry {
    override def toResponseItem: httpDef.ListTransactionsResponseItem =
      httpDef.ListTransactionsResponseItem(
        transactionType = Unknown.transactionType,
        transactionSubtype = httpDef.TransactionSubtype("unknown", "unknown"),
        eventId = eventId,
        date = java.time.OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC),
      )

    override def setEventId(eventId: String): TxLogEntry = copy(eventId = eventId)
  }
  object Unknown extends TransactionHistoryTxLogEntryCompanion {
    override val dbType: String3 = String3.tryCreate("unk")
    override def txLogId: String3 = TransactionHistoryTxLogEntry.txLogId
    override val transactionType = "unknown"
  }

  sealed trait TransferOfferStatus {
    def toStatusResponse: httpDef.GetTransferOfferStatusResponse
  }
  object TransferOfferStatus {
    case class Created(
        contractId: transferCodegen.TransferOffer.ContractId,
        transactionId: String,
    ) extends TransferOfferStatus {
      override def toStatusResponse: GetTransferOfferStatusResponse =
        httpDef.GetTransferOfferStatusResponse(
          status = httpDef.GetTransferOfferStatusResponse.Status.Created,
          transactionId = Some(transactionId),
          contractId = Some(contractId.contractId),
        )
    }

    case class Accepted(
        contractId: transferCodegen.AcceptedTransferOffer.ContractId,
        transactionId: String,
    ) extends TransferOfferStatus {
      override def toStatusResponse: GetTransferOfferStatusResponse =
        httpDef.GetTransferOfferStatusResponse(
          status = httpDef.GetTransferOfferStatusResponse.Status.Accepted,
          transactionId = Some(transactionId),
          contractId = Some(contractId.contractId),
        )
    }

    case class Completed(
        contractId: cc.coin.Coin.ContractId,
        transactionId: String,
    ) extends TransferOfferStatus {
      override def toStatusResponse: GetTransferOfferStatusResponse =
        httpDef.GetTransferOfferStatusResponse(
          status = httpDef.GetTransferOfferStatusResponse.Status.Completed,
          transactionId = Some(transactionId),
          contractId = Some(contractId.contractId),
        )
    }

    sealed trait Failed extends TransferOfferStatus

    case object Rejected extends Failed {
      override def toStatusResponse: GetTransferOfferStatusResponse =
        httpDef.GetTransferOfferStatusResponse(
          status = httpDef.GetTransferOfferStatusResponse.Status.Failed,
          failureKind = Some(httpDef.GetTransferOfferStatusResponse.FailureKind.Rejected),
        )
    }

    case class Withdrawn(reason: String) extends Failed {
      override def toStatusResponse: GetTransferOfferStatusResponse =
        httpDef.GetTransferOfferStatusResponse(
          status = httpDef.GetTransferOfferStatusResponse.Status.Failed,
          failureKind = Some(httpDef.GetTransferOfferStatusResponse.FailureKind.Withdrawn),
          withdrawnReason = Some(reason),
        )
    }

    case object Expired extends Failed {
      override def toStatusResponse: GetTransferOfferStatusResponse =
        httpDef.GetTransferOfferStatusResponse(
          status = httpDef.GetTransferOfferStatusResponse.Status.Failed,
          failureKind = Some(httpDef.GetTransferOfferStatusResponse.FailureKind.Expired),
        )
    }

  }

  final case class TransferOffer(
      trackingId: String,
      status: TransferOfferStatus,
      sender: String,
      receiver: String,
  ) extends TransferOfferTxLogEntry
  object TransferOffer extends TxLogEntryCompanion {
    override val txLogId: String3 = String3.tryCreate("tof")
    override val dbType: String3 = String3.tryCreate("tof")
  }

  sealed trait BuyTrafficRequestStatus {
    def toStatusResponse: httpDef.GetBuyTrafficRequestStatusResponse
  }
  object BuyTrafficRequestStatus {
    case object Created extends BuyTrafficRequestStatus {
      override def toStatusResponse: httpDef.GetBuyTrafficRequestStatusResponse =
        httpDef.GetBuyTrafficRequestStatusResponse(
          status = httpDef.GetBuyTrafficRequestStatusResponse.Status.Created
        )
    }
    case class Completed(
        transactionId: String
    ) extends BuyTrafficRequestStatus {
      override def toStatusResponse: httpDef.GetBuyTrafficRequestStatusResponse =
        httpDef.GetBuyTrafficRequestStatusResponse(
          status = httpDef.GetBuyTrafficRequestStatusResponse.Status.Completed,
          transactionId = Some(transactionId),
        )
    }
    sealed trait Failed extends BuyTrafficRequestStatus
    case class Rejected(reason: String) extends Failed {
      override def toStatusResponse: httpDef.GetBuyTrafficRequestStatusResponse =
        httpDef.GetBuyTrafficRequestStatusResponse(
          status = httpDef.GetBuyTrafficRequestStatusResponse.Status.Failed,
          failureReason = Some(httpDef.GetBuyTrafficRequestStatusResponse.FailureReason.Rejected),
          rejectionReason = Some(reason),
        )
    }
    case object Expired extends Failed {
      override def toStatusResponse: httpDef.GetBuyTrafficRequestStatusResponse =
        httpDef.GetBuyTrafficRequestStatusResponse(
          status = httpDef.GetBuyTrafficRequestStatusResponse.Status.Failed,
          failureReason = Some(httpDef.GetBuyTrafficRequestStatusResponse.FailureReason.Expired),
        )
    }
  }

  final case class BuyTrafficRequest(
      trackingId: String,
      status: BuyTrafficRequestStatus,
      buyer: String,
  ) extends BuyTrafficRequestTxLogEntry
  object BuyTrafficRequest extends TxLogEntryCompanion {
    override val txLogId: String3 = String3.tryCreate("btr")
    override val dbType: String3 = String3.tryCreate("btr")
  }

  /** Balance change due to a transfer */
  final case class Transfer(
      eventId: String,
      transactionSubtype: TxLogEntry.Transfer.TransferTransactionSubtype,
      date: Instant,
      provider: String,
      sender: (String, BigDecimal),
      receivers: Seq[(String, BigDecimal)],
      senderHoldingFees: BigDecimal,
      coinPrice: BigDecimal,
      appRewardsUsed: BigDecimal,
      validatorRewardsUsed: BigDecimal,
  ) extends TransactionHistoryTxLogEntry {
    override def toResponseItem: httpDef.ListTransactionsResponseItem =
      httpDef.ListTransactionsResponseItem(
        transactionType = Transfer.transactionType,
        transactionSubtype = transactionSubtype.toResponseItem,
        eventId = eventId,
        date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
        provider = Some(provider),
        sender = Some(httpDef.PartyAndAmount(sender._1, Codec.encode(sender._2))),
        receivers =
          Some(receivers.map(r => httpDef.PartyAndAmount(r._1, Codec.encode(r._2))).toVector),
        holdingFees = Some(Codec.encode(senderHoldingFees)),
        coinPrice = Some(Codec.encode(coinPrice)),
        appRewardsUsed = Some(Codec.encode(appRewardsUsed)),
        validatorRewardsUsed = Some(Codec.encode(validatorRewardsUsed)),
      )

    override def setEventId(eventId: String): TxLogEntry = copy(eventId = eventId)
  }

  object Transfer extends TransactionHistoryTxLogEntryCompanion {
    override val dbType: String3 = String3.tryCreate("tra")
    override def txLogId: String3 = TransactionHistoryTxLogEntry.txLogId
    override val transactionType = "transfer"

    sealed abstract class TransferTransactionSubtype(
        companion: ExerciseNodeCompanion
    ) extends TransactionSubtype(companion, None)

    object TransferTransactionSubtype {
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
      ).map(txSubtype => txSubtype.choice.name -> txSubtype).toMap

      def find(choiceName: String): Option[TransferTransactionSubtype] =
        values.get(choiceName)
    }
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
    case object ExtraTrafficPurchase extends TransferTransactionSubtype(CoinRules_BuyMemberTraffic)
    case object InitialEntryPaymentCollection
        extends TransferTransactionSubtype(CnsRules_CollectInitialEntryPayment)
    case object EntryRenewalPaymentCollection
        extends TransferTransactionSubtype(CnsRules_CollectEntryRenewalPayment)
    case object Transfer extends TransferTransactionSubtype(com.daml.network.history.Transfer)
  }

  /** Balance change not due to a transfer, for example a tap or returning a locked coin to the owner. */
  final case class BalanceChange(
      eventId: String,
      transactionSubtype: BalanceChange.BalanceChangeTransactionSubtype,
      date: Instant,
      receiver: String,
      amount: BigDecimal,
      coinPrice: BigDecimal,
  ) extends TransactionHistoryTxLogEntry {
    override def toResponseItem: httpDef.ListTransactionsResponseItem =
      httpDef.ListTransactionsResponseItem(
        transactionType = BalanceChange.transactionType,
        transactionSubtype = transactionSubtype.toResponseItem,
        eventId = eventId,
        date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
        provider = None,
        sender = None,
        receivers = Some(Vector(httpDef.PartyAndAmount(receiver, Codec.encode(amount)))),
        holdingFees = None,
        coinPrice = Some(Codec.encode(coinPrice)),
      )

    override def setEventId(eventId: String): TxLogEntry = copy(eventId = eventId)
  }

  object BalanceChange extends TransactionHistoryTxLogEntryCompanion {
    override val dbType: String3 = String3.tryCreate("bal")
    override def txLogId: String3 = TransactionHistoryTxLogEntry.txLogId
    override val transactionType = "balance_change"

    sealed abstract class BalanceChangeTransactionSubtype(companion: ExerciseNodeCompanion)
        extends TransactionSubtype(companion, None)

    object BalanceChangeTransactionSubtype {
      val values: Map[String, BalanceChangeTransactionSubtype] =
        Set[BalanceChangeTransactionSubtype](
          Tap,
          Mint,
          SvRewardCollected,
          AppPaymentRejected,
          AppPaymentExpired,
          SubscriptionInitialPaymentRejected,
          SubscriptionInitialPaymentExpired,
          SubscriptionPaymentRejected,
          SubscriptionPaymentExpired,
          LockedCoinUnlocked,
          LockedCoinOwnerExpired,
          LockedCoinExpired,
          CoinExpired,
        ).map(txSubtype => txSubtype.choice.name -> txSubtype).toMap

      def find(choiceName: String): Option[BalanceChangeTransactionSubtype] =
        values.get(choiceName)
    }
    case object Tap extends BalanceChangeTransactionSubtype(com.daml.network.history.Tap)
    case object Mint extends BalanceChangeTransactionSubtype(com.daml.network.history.Mint)
    case object SvRewardCollected extends BalanceChangeTransactionSubtype(SvcRules_CollectSvReward)
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
    case object LockedCoinUnlocked extends BalanceChangeTransactionSubtype(LockedCoinUnlock)
    case object LockedCoinOwnerExpired
        extends BalanceChangeTransactionSubtype(LockedCoinOwnerExpireLock)
    case object LockedCoinExpired extends BalanceChangeTransactionSubtype(LockedCoinExpireCoin)
    case object CoinExpired extends BalanceChangeTransactionSubtype(CoinExpire)
  }

  /** An event that does not change anyone's coin balance. */
  final case class Notification(
      eventId: String,
      transactionSubtype: Notification.NotificationTransactionSubtype,
      date: Instant,
      details: String,
  ) extends TransactionHistoryTxLogEntry {
    override def toResponseItem: httpDef.ListTransactionsResponseItem =
      httpDef.ListTransactionsResponseItem(
        transactionType = Notification.transactionType,
        transactionSubtype = transactionSubtype.toResponseItem,
        eventId = eventId,
        date = java.time.OffsetDateTime.ofInstant(date, ZoneOffset.UTC),
        details = Some(details),
      )

    override def setEventId(eventId: String): TxLogEntry = copy(eventId = eventId)
  }

  object Notification extends TransactionHistoryTxLogEntryCompanion {
    override val dbType: String3 = String3.tryCreate("not")
    override def txLogId: String3 = TransactionHistoryTxLogEntry.txLogId
    override val transactionType = "notification"

    /** @param coinOperation the constructor name of the CoinOperation. Only relevant for WalletAppInstall_ExecuteBatch
      */
    sealed abstract class NotificationTransactionSubtype(
        companion: ExerciseNodeCompanion,
        val coinOperation: Option[String],
    ) extends TransactionSubtype(companion, coinOperation)
    object NotificationTransactionSubtype {
      val values: Map[(String, Option[String]), NotificationTransactionSubtype] =
        Set[NotificationTransactionSubtype](
          DirectTransferFailed,
          SubscriptionPaymentFailed,
          SubscriptionExpired,
        ).map(txSubtype =>
          (
            txSubtype.choice.name,
            txSubtype.coinOperation,
          ) -> txSubtype
        ).toMap
      def find(
          choiceName: String,
          coinOperationConstructor: Option[String],
      ): Option[NotificationTransactionSubtype] =
        values.get((choiceName, coinOperationConstructor))
    }
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
  }

  private def transferFromResponseItem(
      item: httpDef.ListTransactionsResponseItem
  ): Either[String, TxLogEntry.Transfer] = {
    for {
      transactionSubtype <- TxLogEntry.Transfer.TransferTransactionSubtype
        .find(item.transactionSubtype.choice)
        .toRight("TransactionSubtype not found")
      provider <- item.provider.toRight("Provider missing")
      sender <- item.sender.toRight("Sender missing")
      senderAmount <- Codec.decode(Codec.BigDecimal)(sender.amount)
      receivers <- item.receivers.toRight("Receivers missing")
      receivers <- receivers.traverse(r =>
        Codec.decode(Codec.BigDecimal)(r.amount).map(amount => r.party -> amount)
      )
      holdingFees <- item.holdingFees.toRight("Holding fees missing")
      senderHoldingFees <- Codec.decode(Codec.BigDecimal)(holdingFees)
      coinPriceStr <- item.coinPrice.toRight("Coin price missing")
      coinPrice <- Codec.decode(Codec.BigDecimal)(coinPriceStr)
      appRewardsUsedStr <- item.appRewardsUsed.toRight("App rewards missing")
      appRewardsUsed <- Codec.decode(Codec.BigDecimal)(appRewardsUsedStr)
      validatorRewardsUsedStr <- item.validatorRewardsUsed.toRight("Validator rewards missing")
      validatorRewardsUsed <- Codec.decode(Codec.BigDecimal)(validatorRewardsUsedStr)
    } yield TxLogEntry.Transfer(
      eventId = item.eventId,
      transactionSubtype = transactionSubtype,
      date = item.date.toInstant,
      provider = provider,
      sender = sender.party -> senderAmount,
      receivers = receivers,
      senderHoldingFees = senderHoldingFees,
      coinPrice = coinPrice,
      appRewardsUsed = appRewardsUsed,
      validatorRewardsUsed = validatorRewardsUsed,
    )
  }

  private def balanceChangeFromResponseItem(
      item: httpDef.ListTransactionsResponseItem
  ): Either[String, TxLogEntry.BalanceChange] = {
    for {
      transactionSubtype <- TxLogEntry.BalanceChange.BalanceChangeTransactionSubtype
        .find(item.transactionSubtype.choice)
        .toRight("TransactionSubtype not found")
      receiverAndAmount <- item.receivers.flatMap(_.headOption).toRight("No receivers")
      coinPriceStr <- item.coinPrice.toRight("Coin price missing")
      coinPrice <- Codec.decode(Codec.BigDecimal)(coinPriceStr)
    } yield TxLogEntry.BalanceChange(
      transactionSubtype = transactionSubtype,
      eventId = item.eventId,
      date = item.date.toInstant,
      receiver = receiverAndAmount.party,
      amount = Codec.tryDecode(Codec.BigDecimal)(receiverAndAmount.amount),
      coinPrice = coinPrice,
    )
  }

  private def notificationFromResponseItem(
      item: httpDef.ListTransactionsResponseItem
  ): Either[String, TxLogEntry.Notification] = {
    for {
      transactionSubtype <- TxLogEntry.Notification.NotificationTransactionSubtype
        .find(item.transactionSubtype.choice, item.transactionSubtype.coinOperation)
        .toRight("TransactionSubtype not found")
      details <- item.details.toRight("Details missing")
    } yield TxLogEntry.Notification(
      transactionSubtype = transactionSubtype,
      eventId = item.eventId,
      date = item.date.toInstant,
      details = details,
    )
  }

  private def unknownFromResponseItem(
      item: httpDef.ListTransactionsResponseItem
  ): Either[String, TxLogEntry.Unknown] = {
    Right(
      TxLogEntry.Unknown(
        eventId = item.eventId
      )
    )
  }

  // Note: deserialization is only needed for the Canton console
  def fromResponseItem(
      item: httpDef.ListTransactionsResponseItem
  ): Either[String, TransactionHistoryTxLogEntry] = {
    item.transactionType match {
      case TxLogEntry.Transfer.transactionType => transferFromResponseItem(item)
      case TxLogEntry.BalanceChange.transactionType =>
        balanceChangeFromResponseItem(item)
      case TxLogEntry.Notification.transactionType => notificationFromResponseItem(item)
      case TxLogEntry.Unknown.transactionType => unknownFromResponseItem(item)
      case _ => Left(s"Unknown item $item")
    }
  }
}
