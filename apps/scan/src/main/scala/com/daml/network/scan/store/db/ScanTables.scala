package com.daml.network.scan.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.scan.store.{
  AppRewardTxLogEntry,
  BalanceChangeTxLogEntry,
  ClosedMiningRoundTxLogEntry,
  ErrorTxLogEntry,
  ExtraTrafficPurchaseTxLogEntry,
  MintTxLogEntry,
  OpenMiningRoundTxLogEntry,
  SvRewardCollectedTxLogEntry,
  TapTxLogEntry,
  TransferTxLogEntry,
  TxLogEntry,
  ValidatorRewardTxLogEntry,
}
import com.daml.network.store.StoreErrors
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue, TxLogRowData}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.{Member, PartyId}
import com.digitalasset.canton.data.CantonTimestamp

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
      memberTrafficMember: Option[Member] = None,
      totalTrafficPurchased: Option[Long] = None,
      validatorLicenseRoundsCollected: Option[Long] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "round" -> round,
      "validator" -> validator,
      "amount" -> amount,
      "import_crate_receiver" -> importCrateReceiver.map(lengthLimited),
      "featured_app_right_provider" -> featuredAppRightProvider,
      "cns_entry_name" -> cnsEntryName.map(lengthLimited),
      "cns_entry_owner" -> cnsEntryOwner,
      "member_traffic_member" -> memberTrafficMember,
      "total_traffic_purchased" -> totalTrafficPurchased,
      "validator_license_rounds_collected" -> validatorLicenseRoundsCollected,
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
      closedRoundEffectiveAt: Option[CantonTimestamp] = None,
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
      "closed_round_effective_at" -> closedRoundEffectiveAt,
    )
  }

  object ScanTxLogRowData extends StoreErrors {

    def fromTxLogEntry(record: TxLogEntry): ScanTxLogRowData = {
      record match {
        case err: ErrorTxLogEntry =>
          ScanTxLogRowData(
            entry = err
          )
        case omr: OpenMiningRoundTxLogEntry =>
          ScanTxLogRowData(
            entry = omr,
            round = Some(omr.round),
          )
        case cmr: ClosedMiningRoundTxLogEntry =>
          ScanTxLogRowData(
            entry = cmr,
            round = Some(cmr.round),
            closedRoundEffectiveAt = cmr.effectiveAt.map(CantonTimestamp.assertFromInstant),
          )
        case are: AppRewardTxLogEntry =>
          ScanTxLogRowData(
            entry = are,
            round = Some(are.round),
            rewardAmount = Some(are.amount),
            rewardedParty = Some(are.party),
          )
        case vre: ValidatorRewardTxLogEntry =>
          ScanTxLogRowData(
            entry = vre,
            round = Some(vre.round),
            rewardAmount = Some(vre.amount),
            rewardedParty = Some(vre.party),
          )
        case etp: ExtraTrafficPurchaseTxLogEntry =>
          ScanTxLogRowData(
            entry = etp,
            round = Some(etp.round),
            extraTrafficValidator = Some(etp.validator),
            extraTrafficPurchaseTrafficPurchase = Some(etp.trafficPurchased),
            extraTrafficPurchaseCcSpent = Some(etp.ccSpent),
          )
        case bac: BalanceChangeTxLogEntry =>
          ScanTxLogRowData(
            entry = bac,
            round = Some(bac.round),
            balanceChangeToInitialAmountAsOfRoundZero =
              Some(bac.changeToInitialAmountAsOfRoundZero),
            balanceChangeChangeToHoldingFeesRate = Some(bac.changeToHoldingFeesRate),
          )
        case rar: TransferTxLogEntry =>
          ScanTxLogRowData(
            entry = rar,
            round = Some(rar.round),
          )
        case entry: TapTxLogEntry =>
          ScanTxLogRowData(
            entry = entry,
            round = Some(entry.round),
          )
        case entry: MintTxLogEntry =>
          ScanTxLogRowData(
            entry = entry,
            round = Some(entry.round),
          )
        case entry: SvRewardCollectedTxLogEntry =>
          ScanTxLogRowData(
            entry = entry,
            round = Some(entry.round),
          )
        case _ =>
          throw txEncodingFailed()
      }
    }

  }

  val acsTableName = "scan_acs_store"
  val txLogTableName = "scan_txlog_store"
}
