package com.daml.network.wallet.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue, TxLogRowData}
import com.daml.network.util.Contract
import com.daml.network.wallet.store.TxLogEntry
import com.digitalasset.canton.config.CantonRequireTypes.{LengthLimitedString, String3}

object WalletTables extends AcsTables {

  case class UserWalletAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      rewardCouponRound: Option[Long] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "reward_coupon_round" -> IndexColumnValue(rewardCouponRound)
    )
  }

  case class UserWalletTxLogStoreRowData(
      entry: TxLogEntry,
      txLogId: String3,
      eventId: Option[String] = None,
      trackingId: Option[String] = None,
  ) extends TxLogRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "tx_log_id" -> IndexColumnValue[LengthLimitedString](txLogId),
      "event_id" -> IndexColumnValue(eventId.map(lengthLimited)),
      "tracking_id" -> IndexColumnValue(trackingId.map(lengthLimited)),
    )
  }

  object UserWalletTxLogStoreRowData {
    def fromTxLogEntry(entry: TxLogEntry): UserWalletTxLogStoreRowData =
      entry match {
        case e: TxLogEntry.TransactionHistoryTxLogEntry =>
          UserWalletTxLogStoreRowData(
            entry,
            TxLogEntry.TransactionHistoryTxLogEntry.txLogId,
            eventId = Some(e.eventId),
          )
        case e: TxLogEntry.BuyTrafficRequest =>
          UserWalletTxLogStoreRowData(
            entry,
            TxLogEntry.BuyTrafficRequest.txLogId,
            trackingId = Some(e.trackingId),
          )
        case e: TxLogEntry.TransferOffer =>
          UserWalletTxLogStoreRowData(
            entry,
            TxLogEntry.TransferOffer.txLogId,
            trackingId = Some(e.trackingId),
          )
      }
  }

  val acsTableName: String = "user_wallet_acs_store"
  val txLogTableName: String = "user_wallet_txlog_store"
}
