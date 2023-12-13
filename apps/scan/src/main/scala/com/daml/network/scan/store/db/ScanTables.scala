package com.daml.network.scan.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.scan.store.TxLogEntry
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue, TxLogRowData}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId

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
      entry: TxLogEntry,
      round: Option[Long] = None,
      rewardAmount: Option[BigDecimal] = None,
      rewardedParty: Option[PartyId] = None,
      balanceChangeToInitialAmountAsOfRoundZero: Option[BigDecimal] = None,
      balanceChangeChangeToHoldingFeesRate: Option[BigDecimal] = None,
      extraTrafficValidator: Option[PartyId] = None,
      extraTrafficPurchaseTrafficPurchase: Option[Long] = None,
      extraTrafficPurchaseCcSpent: Option[BigDecimal] = None,
  ) extends TxLogRowData {

    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "event_id" -> Some(lengthLimited(entry.eventId)),
      "round" -> round,
      "reward_amount" -> rewardAmount,
      "rewarded_party" -> rewardedParty,
      "balance_change_change_to_initial_amount_as_of_round_zero" -> balanceChangeToInitialAmountAsOfRoundZero,
      "balance_change_change_to_holding_fees_rate" -> balanceChangeChangeToHoldingFeesRate,
      "extra_traffic_validator" -> extraTrafficValidator,
      "extra_traffic_purchase_traffic_purchased" -> extraTrafficPurchaseTrafficPurchase,
      "extra_traffic_purchase_cc_spent" -> extraTrafficPurchaseCcSpent,
    )
  }

  object ScanTxLogRowData {

    def fromTxLogEntry(record: TxLogEntry): ScanTxLogRowData = {
      record match {
        case err: TxLogEntry.ErrorLogEntry =>
          ScanTxLogRowData(
            entry = err
          )
        case omr: TxLogEntry.OpenMiningRoundLogEntry =>
          ScanTxLogRowData(
            entry = omr,
            round = Some(omr.round),
          )
        case cmr: TxLogEntry.ClosedMiningRoundLogEntry =>
          ScanTxLogRowData(
            entry = cmr,
            round = Some(cmr.round),
          )
        case are: TxLogEntry.AppRewardLogEntry =>
          ScanTxLogRowData(
            entry = are,
            round = Some(are.round),
            rewardAmount = Some(are.amount),
            rewardedParty = Some(are.party),
          )
        case vre: TxLogEntry.ValidatorRewardLogEntry =>
          ScanTxLogRowData(
            entry = vre,
            round = Some(vre.round),
            rewardAmount = Some(vre.amount),
            rewardedParty = Some(vre.party),
          )
        case etp: TxLogEntry.ExtraTrafficPurchaseLogEntry =>
          ScanTxLogRowData(
            entry = etp,
            round = Some(etp.round),
            extraTrafficValidator = Some(etp.validator),
            extraTrafficPurchaseTrafficPurchase = Some(etp.trafficPurchased),
            extraTrafficPurchaseCcSpent = Some(etp.ccSpent),
          )
        case bac: TxLogEntry.BalanceChangeLogEntry =>
          ScanTxLogRowData(
            entry = bac,
            round = Some(bac.round),
            balanceChangeToInitialAmountAsOfRoundZero =
              Some(bac.changeToInitialAmountAsOfRoundZero),
            balanceChangeChangeToHoldingFeesRate = Some(bac.changeToHoldingFeesRate),
          )
        case rar: TxLogEntry.TransferLogEntry =>
          ScanTxLogRowData(
            entry = rar,
            round = Some(rar.round),
          )
        case entry: TxLogEntry.TapLogEntry =>
          ScanTxLogRowData(
            entry = entry,
            round = Some(entry.round),
          )
        case entry: TxLogEntry.MintLogEntry =>
          ScanTxLogRowData(
            entry = entry,
            round = Some(entry.round),
          )
        case entry: TxLogEntry.SvRewardCollectedLogEntry =>
          ScanTxLogRowData(
            entry = entry,
            round = Some(entry.round),
          )
      }
    }

  }

  val acsTableName = "scan_acs_store"
  val txLogTableName = "scan_txlog_store"
}
