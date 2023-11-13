package com.daml.network.scan.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.scan.store.TxLogIndexRecord
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.Contract
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.{DomainId, PartyId}

object ScanTables extends AcsTables {

  case class ScanAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      round: Option[Long] = None,
      validator: Option[PartyId] = None,
      amount: Option[BigDecimal] = None,
      importCrateReceiver: Option[String] = None,
      featuredAppRightProvider: Option[PartyId] = None,
      cnsEntryName: Option[String] = None,
      cnsEntryOwner: Option[PartyId] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "round" -> round,
      "validator" -> validator,
      "amount" -> amount,
      "import_crate_receiver" -> importCrateReceiver.map(lengthLimited),
      "featured_app_right_provider" -> featuredAppRightProvider,
      "cns_entry_name" -> cnsEntryName.map(lengthLimited),
      "cns_entry_owner" -> cnsEntryOwner,
    )
  }

  case class ScanTxLogRowData(
      eventId: String,
      offset: Option[String],
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      indexRecordType: String3,
      round: Option[Long],
      rewardAmount: Option[BigDecimal],
      rewardedParty: Option[PartyId],
      balanceChangeToInitialAmountAsOfRoundZero: Option[BigDecimal],
      balanceChangeChangeToHoldingFeesRate: Option[BigDecimal],
      extraTrafficValidator: Option[PartyId],
      extraTrafficPurchaseTrafficPurchase: Option[Long],
      extraTrafficPurchaseCcSpent: Option[BigDecimal],
  )

  object ScanTxLogRowData {

    def fromTxLogIndexRecord(record: TxLogIndexRecord): ScanTxLogRowData = {
      record match {
        case err @ TxLogIndexRecord.ErrorIndexRecord(offset, eventId, domainId) =>
          ScanTxLogRowData(
            eventId = eventId,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            indexRecordType = err.companion.dbType,
            round = None,
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case omr @ TxLogIndexRecord.OpenMiningRoundIndexRecord(
              offset,
              eventId,
              domainId,
              round,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = omr.companion.dbType,
            domainId = domainId,
            acsContractId = None,
            offset = Some(offset),
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case cmr @ TxLogIndexRecord.ClosedMiningRoundIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              _,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = cmr.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case are @ TxLogIndexRecord.AppRewardIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              party,
              amount,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = are.companion.dbType,
            domainId = domainId,
            offset = Some(offset),
            acsContractId = None,
            round = Some(round),
            rewardAmount = Some(amount),
            rewardedParty = Some(party),
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case vre @ TxLogIndexRecord.ValidatorRewardIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              party,
              amount,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = vre.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = Some(round),
            rewardAmount = Some(amount),
            rewardedParty = Some(party),
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case etp @ TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              validator,
              trafficPurchased,
              ccSpent,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = etp.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = Some(validator),
            extraTrafficPurchaseTrafficPurchase = Some(trafficPurchased),
            extraTrafficPurchaseCcSpent = Some(ccSpent),
          )
        case bac @ TxLogIndexRecord.BalanceChangeIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate,
              acsContractId,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = bac.companion.dbType,
            offset = offset,
            domainId = domainId,
            acsContractId = acsContractId,
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = Some(changeToInitialAmountAsOfRoundZero),
            balanceChangeChangeToHoldingFeesRate = Some(changeToHoldingFeesRate),
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case rar @ TxLogIndexRecord.TransactionIndexRecord(
              offset,
              eventId,
              domainId,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = rar.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = None,
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
      }
    }

  }

  val acsTableName = "scan_acs_store"
  val txLogTableName = "scan_txlog_store"
}
