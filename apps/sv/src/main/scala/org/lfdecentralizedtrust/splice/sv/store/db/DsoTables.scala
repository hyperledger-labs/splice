// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as sub
import org.lfdecentralizedtrust.splice.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{Member, PartyId, SynchronizerId}
import io.circe.Json
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns

object DsoTables extends AcsTables with NamedLogging {

  final val AnsActionTypeCollectInitialEntryPayment = "ANSRARC_CollectInitialEntryPayment"
  final val AnsActionTypeRejectEntryInitialPayment = "ANSRARC_RejectEntryInitialPayment"

  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  case class DsoAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      amuletRoundOfExpiry: Option[Long] = None,
      rewardRound: Option[Long] = None,
      rewardParty: Option[PartyId] = None,
      rewardAmount: Option[BigDecimal] = None,
      rewardWeight: Option[Long] = None,
      appRewardIsFeatured: Option[Boolean] = None,
      miningRound: Option[Long] = None,
      actionRequiringConfirmation: Option[Json] = None,
      confirmer: Option[PartyId] = None,
      svOnboardingToken: Option[String] = None,
      svCandidateParty: Option[PartyId] = None,
      svCandidateName: Option[String] = None,
      validator: Option[PartyId] = None,
      totalTrafficPurchased: Option[Long] = None,
      voter: Option[PartyId] = None,
      voteRequestTrackingCid: Option[VoteRequest.ContractId] = None,
      requester: Option[PartyId] = None,
      requesterName: Option[String] = None,
      memberTrafficMember: Option[Member] = None,
      memberTrafficDomain: Option[SynchronizerId] = None,
      ansEntryName: Option[String] = None,
      actionAnsEntryContextCid: Option[splice.ans.AnsEntryContext.ContractId] = None,
      actionAnsEntryContextPaymentId: Option[sub.SubscriptionInitialPayment.ContractId] = None,
      actionAnsEntryContextArcType: Option[String] = None,
      subscriptionReferenceContractId: Option[sub.SubscriptionRequest.ContractId] = None,
      subscriptionNextPaymentDueAt: Option[Timestamp] = None,
      featuredAppRightProvider: Option[PartyId] = None,
      svParty: Option[PartyId] = None,
      svName: Option[String] = None,
      walletParty: Option[PartyId] = None,
      conversionRateFeedPublisher: Option[PartyId] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      DsoAcsStoreRowData.IndexColumns.amulet_round_of_expiry -> amuletRoundOfExpiry,
      DsoAcsStoreRowData.IndexColumns.reward_round -> rewardRound,
      DsoAcsStoreRowData.IndexColumns.reward_party -> rewardParty,
      DsoAcsStoreRowData.IndexColumns.reward_amount -> rewardAmount,
      DsoAcsStoreRowData.IndexColumns.reward_weight -> rewardWeight,
      DsoAcsStoreRowData.IndexColumns.app_reward_is_featured -> appRewardIsFeatured,
      DsoAcsStoreRowData.IndexColumns.mining_round -> miningRound,
      DsoAcsStoreRowData.IndexColumns.action_requiring_confirmation -> actionRequiringConfirmation,
      DsoAcsStoreRowData.IndexColumns.confirmer -> confirmer,
      DsoAcsStoreRowData.IndexColumns.sv_onboarding_token -> svOnboardingToken.map(lengthLimited),
      DsoAcsStoreRowData.IndexColumns.sv_candidate_party -> svCandidateParty,
      DsoAcsStoreRowData.IndexColumns.sv_candidate_name -> svCandidateName.map(lengthLimited),
      DsoAcsStoreRowData.IndexColumns.validator -> validator,
      DsoAcsStoreRowData.IndexColumns.total_traffic_purchased -> totalTrafficPurchased,
      DsoAcsStoreRowData.IndexColumns.voter -> voter,
      DsoAcsStoreRowData.IndexColumns.vote_request_tracking_cid -> voteRequestTrackingCid,
      DsoAcsStoreRowData.IndexColumns.requester -> requester,
      DsoAcsStoreRowData.IndexColumns.requester_name -> requesterName.map(lengthLimited),
      DsoAcsStoreRowData.IndexColumns.member_traffic_member -> memberTrafficMember,
      DsoAcsStoreRowData.IndexColumns.member_traffic_domain -> memberTrafficDomain,
      DsoAcsStoreRowData.IndexColumns.ans_entry_name -> ansEntryName.map(lengthLimited),
      DsoAcsStoreRowData.IndexColumns.action_ans_entry_context_cid -> actionAnsEntryContextCid,
      DsoAcsStoreRowData.IndexColumns.action_ans_entry_context_payment_id -> actionAnsEntryContextPaymentId,
      DsoAcsStoreRowData.IndexColumns.action_ans_entry_context_arc_type -> actionAnsEntryContextArcType
        .map(lengthLimited),
      DsoAcsStoreRowData.IndexColumns.subscription_reference_contract_id -> subscriptionReferenceContractId,
      DsoAcsStoreRowData.IndexColumns.subscription_next_payment_due_at -> subscriptionNextPaymentDueAt,
      DsoAcsStoreRowData.IndexColumns.featured_app_right_provider -> featuredAppRightProvider,
      DsoAcsStoreRowData.IndexColumns.sv_party -> svParty,
      DsoAcsStoreRowData.IndexColumns.sv_name -> svName.map(lengthLimited),
      DsoAcsStoreRowData.IndexColumns.wallet_party -> walletParty,
      DsoAcsStoreRowData.IndexColumns.conversion_rate_feed_publisher -> conversionRateFeedPublisher,
    )
  }
  object DsoAcsStoreRowData {
    implicit val hasIndexColumns: HasIndexColumns[DsoAcsStoreRowData] =
      new HasIndexColumns[DsoAcsStoreRowData] {
        override def indexColumnNames: Seq[String] =
          IndexColumns.All
      }

    private object IndexColumns {
      val amulet_round_of_expiry = "amulet_round_of_expiry"
      val reward_round = "reward_round"
      val reward_party = "reward_party"
      val reward_amount = "reward_amount"
      val reward_weight = "reward_weight"
      val app_reward_is_featured = "app_reward_is_featured"
      val mining_round = "mining_round"
      val action_requiring_confirmation = "action_requiring_confirmation"
      val confirmer = "confirmer"
      val sv_onboarding_token = "sv_onboarding_token"
      val sv_candidate_party = "sv_candidate_party"
      val sv_candidate_name = "sv_candidate_name"
      val validator = "validator"
      val total_traffic_purchased = "total_traffic_purchased"
      val voter = "voter"
      val vote_request_tracking_cid = "vote_request_tracking_cid"
      val requester = "requester"
      val requester_name = "requester_name"
      val member_traffic_member = "member_traffic_member"
      val member_traffic_domain = "member_traffic_domain"
      val ans_entry_name = "ans_entry_name"
      val action_ans_entry_context_cid = "action_ans_entry_context_cid"
      val action_ans_entry_context_payment_id = "action_ans_entry_context_payment_id"
      val action_ans_entry_context_arc_type = "action_ans_entry_context_arc_type"
      val subscription_reference_contract_id = "subscription_reference_contract_id"
      val subscription_next_payment_due_at = "subscription_next_payment_due_at"
      val featured_app_right_provider = "featured_app_right_provider"
      val sv_party = "sv_party"
      val sv_name = "sv_name"
      val wallet_party = "wallet_party"
      val conversion_rate_feed_publisher = "conversion_rate_feed_publisher"
      val All = Seq(
        amulet_round_of_expiry,
        reward_round,
        reward_party,
        reward_amount,
        reward_weight,
        app_reward_is_featured,
        mining_round,
        action_requiring_confirmation,
        confirmer,
        sv_onboarding_token,
        sv_candidate_party,
        sv_candidate_name,
        validator,
        total_traffic_purchased,
        voter,
        vote_request_tracking_cid,
        requester,
        requester_name,
        member_traffic_member,
        member_traffic_domain,
        ans_entry_name,
        action_ans_entry_context_cid,
        action_ans_entry_context_payment_id,
        action_ans_entry_context_arc_type,
        subscription_reference_contract_id,
        subscription_next_payment_due_at,
        featured_app_right_provider,
        sv_party,
        sv_name,
        wallet_party,
        conversion_rate_feed_publisher,
      )
    }
  }

  val acsTableName = "dso_acs_store"
}
