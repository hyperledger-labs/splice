// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsRowData,
  AcsTables,
  IndexColumnValue,
  TxLogRowData,
}
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.wallet.store.{
  BuyTrafficRequestTxLogEntry,
  TransferOfferTxLogEntry,
  TxLogEntry,
}
import com.digitalasset.canton.config.CantonRequireTypes.{LengthLimitedString, String3}
import com.digitalasset.canton.topology.PartyId

object WalletTables extends AcsTables {

  case class UserWalletAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      rewardCouponRound: Option[Long] = None,
      rewardCouponWeight: Option[Long] = None,
      transferPreapprovalReceiver: Option[PartyId] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "reward_coupon_round" -> IndexColumnValue(rewardCouponRound),
      "reward_coupon_weight" -> IndexColumnValue(rewardCouponWeight),
      "transfer_preapproval_receiver" -> IndexColumnValue(transferPreapprovalReceiver),
    )
  }

  case class UserWalletAcsInterfaceViewRowData(contract: Contract[?, ?])
      extends AcsInterfaceViewRowData.AcsInterfaceViewRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
  }

  case class ExternalPartyWalletAcsStoreRowData(
      contract: Contract[?, ?],
      rewardCouponRound: Option[Long] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override val contractExpiresAt: Option[Timestamp] = None
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
            TxLogEntry.LogId.TransactionHistoryTxLog,
            eventId = Some(e.eventId),
          )
        case e: BuyTrafficRequestTxLogEntry =>
          UserWalletTxLogStoreRowData(
            entry,
            TxLogEntry.LogId.BuyTrafficRequestTxLog,
            trackingId = Some(e.trackingId),
          )
        case e: TransferOfferTxLogEntry =>
          UserWalletTxLogStoreRowData(
            entry,
            TxLogEntry.LogId.TransferOfferTxLog,
            trackingId = Some(e.trackingId),
          )
        case e => throw new RuntimeException(s"Unknown TxLogEntry $e")
      }
  }

  val acsTableName: String = "user_wallet_acs_store"
  val externalPartyAcsTableName: String = "external_party_wallet_acs_store"
  val txLogTableName: String = "user_wallet_txlog_store"
  val interfaceViewsTableName: String = "user_wallet_acs_interface_views"
}
