// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_AnsEntryContext,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.TransferCommand
import org.lfdecentralizedtrust.splice.scan.store.{
  AbortTransferInstructionTxLogEntry,
  AppRewardTxLogEntry,
  BalanceChangeTxLogEntry,
  ClosedMiningRoundTxLogEntry,
  ErrorTxLogEntry,
  ExtraTrafficPurchaseTxLogEntry,
  MintTxLogEntry,
  OpenMiningRoundTxLogEntry,
  SvRewardTxLogEntry,
  TapTxLogEntry,
  TransferTxLogEntry,
  TxLogEntry,
  ValidatorRewardTxLogEntry,
  VoteRequestTxLogEntry,
  TransferCommandTxLogEntry,
}
import org.lfdecentralizedtrust.splice.store.{Accepted, StoreErrors, VoteRequestOutcome}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsRowData,
  AcsTables,
  IndexColumnValue,
  TxLogRowData,
}
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.topology.{SynchronizerId, Member, PartyId}
import com.digitalasset.canton.data.CantonTimestamp
import io.circe.Json

object ScanTables extends AcsTables {

  case class ScanAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      round: Option[Long] = None,
      validator: Option[PartyId] = None,
      amount: Option[BigDecimal] = None,
      featuredAppRightProvider: Option[PartyId] = None,
      ansEntryName: Option[String] = None,
      ansEntryOwner: Option[PartyId] = None,
      memberTrafficMember: Option[Member] = None,
      memberTrafficDomain: Option[SynchronizerId] = None,
      totalTrafficPurchased: Option[Long] = None,
      validatorLicenseRoundsCollected: Option[Long] = None,
      svParty: Option[PartyId] = None,
      voteActionRequiringConfirmation: Option[Json] = None,
      voteRequesterName: Option[String] = None,
      voteRequestTrackingCid: Option[VoteRequest.ContractId] = None,
      transferPreapprovalReceiver: Option[PartyId] = None,
      transferPreapprovalValidFrom: Option[Timestamp] = None,
      walletParty: Option[PartyId] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      ScanAcsStoreRowData.IndexColumns.round -> round,
      ScanAcsStoreRowData.IndexColumns.validator -> validator,
      ScanAcsStoreRowData.IndexColumns.amount -> amount,
      ScanAcsStoreRowData.IndexColumns.featured_app_right_provider -> featuredAppRightProvider,
      ScanAcsStoreRowData.IndexColumns.ans_entry_name -> ansEntryName.map(lengthLimited),
      ScanAcsStoreRowData.IndexColumns.ans_entry_owner -> ansEntryOwner,
      ScanAcsStoreRowData.IndexColumns.member_traffic_member -> memberTrafficMember,
      ScanAcsStoreRowData.IndexColumns.member_traffic_domain -> memberTrafficDomain,
      ScanAcsStoreRowData.IndexColumns.total_traffic_purchased -> totalTrafficPurchased,
      ScanAcsStoreRowData.IndexColumns.validator_license_rounds_collected -> validatorLicenseRoundsCollected,
      ScanAcsStoreRowData.IndexColumns.sv_party -> svParty,
      ScanAcsStoreRowData.IndexColumns.vote_action_requiring_confirmation -> voteActionRequiringConfirmation,
      ScanAcsStoreRowData.IndexColumns.vote_requester_name -> voteRequesterName.map(lengthLimited),
      ScanAcsStoreRowData.IndexColumns.vote_request_tracking_cid -> voteRequestTrackingCid,
      ScanAcsStoreRowData.IndexColumns.transfer_preapproval_receiver -> transferPreapprovalReceiver,
      ScanAcsStoreRowData.IndexColumns.transfer_preapproval_valid_from -> transferPreapprovalValidFrom,
      ScanAcsStoreRowData.IndexColumns.wallet_party -> walletParty,
    )
  }

  object ScanAcsStoreRowData {
    implicit val hasIndexColumns: AcsRowData.HasIndexColumns[ScanAcsStoreRowData] =
      new AcsRowData.HasIndexColumns[ScanAcsStoreRowData] {
        override val indexColumnNames: Seq[String] = IndexColumns.All
      }
    private object IndexColumns {
      val round = "round"
      val validator = "validator"
      val amount = "amount"
      val featured_app_right_provider = "featured_app_right_provider"
      val ans_entry_name = "ans_entry_name"
      val ans_entry_owner = "ans_entry_owner"
      val member_traffic_member = "member_traffic_member"
      val member_traffic_domain = "member_traffic_domain"
      val total_traffic_purchased = "total_traffic_purchased"
      val validator_license_rounds_collected = "validator_license_rounds_collected"
      val sv_party = "sv_party"
      val vote_action_requiring_confirmation = "vote_action_requiring_confirmation"
      val vote_requester_name = "vote_requester_name"
      val vote_request_tracking_cid = "vote_request_tracking_cid"
      val transfer_preapproval_receiver = "transfer_preapproval_receiver"
      val transfer_preapproval_valid_from = "transfer_preapproval_valid_from"
      val wallet_party = "wallet_party"
      val All = Seq(
        round,
        validator,
        amount,
        featured_app_right_provider,
        ans_entry_name,
        ans_entry_owner,
        member_traffic_member,
        member_traffic_domain,
        total_traffic_purchased,
        validator_license_rounds_collected,
        sv_party,
        vote_action_requiring_confirmation,
        vote_requester_name,
        vote_request_tracking_cid,
        transfer_preapproval_receiver,
        transfer_preapproval_valid_from,
        wallet_party,
      )
    }
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
      voteActionName: Option[String] = None,
      voteAccepted: Option[Boolean] = None,
      voteRequesterName: Option[String] = None,
      voteEffectiveAt: Option[String] = None,
      transferCommandContractId: Option[TransferCommand.ContractId] = None,
      transferCommandSender: Option[PartyId] = None,
      transferCommandNonce: Option[Long] = None,
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
      "vote_action_name" -> voteActionName.map(lengthLimited),
      "vote_accepted" -> voteAccepted,
      "vote_requester_name" -> voteRequesterName.map(lengthLimited),
      "vote_effective_at" -> voteEffectiveAt.map(lengthLimited),
      "transfer_command_contract_id" -> transferCommandContractId,
      "transfer_command_sender" -> transferCommandSender,
      "transfer_command_nonce" -> transferCommandNonce,
    )
  }

  object ScanTxLogRowData extends StoreErrors {

    def fromTxLogEntry(record: TxLogEntry): Option[ScanTxLogRowData] = {
      def fromEntry(record: TxLogEntry): ScanTxLogRowData = {
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
          case sre: SvRewardTxLogEntry =>
            ScanTxLogRowData(
              entry = sre,
              round = Some(sre.round),
              rewardAmount = Some(sre.amount),
              rewardedParty = Some(sre.party),
            )
          case etp: ExtraTrafficPurchaseTxLogEntry =>
            ScanTxLogRowData(
              entry = etp,
              round = Some(etp.round),
              extraTrafficValidator = Some(etp.validator),
              extraTrafficPurchaseTrafficPurchase = Some(etp.trafficPurchased),
              extraTrafficPurchaseCcSpent = Some(etp.ccSpent),
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
          case vr: VoteRequestTxLogEntry =>
            val result = vr.result.getOrElse(throw txMissingField())
            val parsedOutcome = VoteRequestOutcome.parse(result.outcome)
            ScanTxLogRowData(
              entry = vr,
              voteActionName = Some(mapActionName(result.request.action)),
              voteAccepted = Some(parsedOutcome match {
                case _: Accepted => true
                case _ => false
              }),
              voteRequesterName = Some(result.request.requester),
              voteEffectiveAt = parsedOutcome.effectiveAt match {
                case Some(effectiveAt) => Some(effectiveAt.toString)
                case None => None
              },
            )
          case entry: TransferCommandTxLogEntry =>
            ScanTxLogRowData(
              entry = entry,
              transferCommandContractId = Some(
                new TransferCommand.ContractId(
                  entry.contractId
                )
              ),
              transferCommandSender = Some(
                entry.sender
              ),
              transferCommandNonce = Some(
                entry.nonce
              ),
            )
          case entry: AbortTransferInstructionTxLogEntry =>
            ScanTxLogRowData(
              entry = entry
            )
          case _ =>
            throw txEncodingFailed()
        }
      }

      record match {
        case _: BalanceChangeTxLogEntry =>
          // the balance changes are no longer indexed, or written, to the tx log table,
          // See https://github.com/hyperledger-labs/splice/pull/3734
          None
        case entry => Some(fromEntry(entry))
      }
    }

    private def mapActionName(
        action: ActionRequiringConfirmation
    ): String = {
      action match {
        case arcDsoRules: ARC_DsoRules =>
          arcDsoRules.dsoAction.getClass.getSimpleName
        case arcAmuletRules: ARC_AmuletRules =>
          arcAmuletRules.amuletRulesAction.getClass.getSimpleName
        case arcAnsEntryContext: ARC_AnsEntryContext =>
          arcAnsEntryContext.ansEntryContextAction.getClass.getSimpleName
        case _ => ""
      }
    }

  }

  val acsTableName = "scan_acs_store"
  val txLogTableName = "scan_txlog_store"
}
