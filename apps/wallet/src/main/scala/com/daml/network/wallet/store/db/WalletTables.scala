package com.daml.network.wallet.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.Contract
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.DomainId

object WalletTables extends AcsTables {

  case class UserWalletAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
  }

  // Note: currently the index record is empty, but this is likely to change once we want to support more advanced
  // filtering/sorting of the transaction history.
  case class UserWalletTxLogStoreRowData(
      eventId: String,
      optOffset: Option[String],
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      txLogId: String3,
      trackingId: Option[String],
  )

  object UserWalletTxLogStoreRowData {
    def fromIndexRecord(
        indexRecord: UserWalletTxLogParser.WalletTxLogIndexRecord
    ): Either[String, UserWalletTxLogStoreRowData] =
      Right(
        UserWalletTxLogStoreRowData(
          indexRecord.eventId,
          indexRecord.optOffset,
          indexRecord.domainId,
          indexRecord.acsContractId,
          indexRecord.txLogId,
          indexRecord match {
            case to: UserWalletTxLogParser.TransferOfferStatusTxLogIndexRecord =>
              Some(to.trackingId)
            case btr: UserWalletTxLogParser.BuyTrafficRequestStatusTxLogIndexRecord =>
              Some(btr.trackingId)
            case _ => None
          },
        )
      )
  }

  val acsTableName: String = "user_wallet_acs_store"
  val txLogTableName: String = "user_wallet_txlog_store"
}
