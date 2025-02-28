// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as sub
import org.lfdecentralizedtrust.splice.store.{Accepted, StoreErrors, VoteRequestOutcome}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsRowData,
  AcsTables,
  IndexColumnValue,
  TxLogRowData,
}
import org.lfdecentralizedtrust.splice.sv.store.{ErrorTxLogEntry, TxLogEntry, VoteRequestTxLogEntry}
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{SynchronizerId, Member, PartyId}
import io.circe.Json

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
      electionRequestEpoch: Option[Long] = None,
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
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "amulet_round_of_expiry" -> amuletRoundOfExpiry,
      "reward_round" -> rewardRound,
      "reward_party" -> rewardParty,
      "reward_amount" -> rewardAmount,
      "reward_weight" -> rewardWeight,
      "app_reward_is_featured" -> appRewardIsFeatured,
      "mining_round" -> miningRound,
      "action_requiring_confirmation" -> actionRequiringConfirmation,
      "confirmer" -> confirmer,
      "sv_onboarding_token" -> svOnboardingToken.map(lengthLimited),
      "sv_candidate_party" -> svCandidateParty,
      "sv_candidate_name" -> svCandidateName.map(lengthLimited),
      "validator" -> validator,
      "total_traffic_purchased" -> totalTrafficPurchased,
      "voter" -> voter,
      "vote_request_tracking_cid" -> voteRequestTrackingCid,
      "requester" -> requester,
      "requester_name" -> requesterName.map(lengthLimited),
      "election_request_epoch" -> electionRequestEpoch,
      "member_traffic_member" -> memberTrafficMember,
      "member_traffic_domain" -> memberTrafficDomain,
      "ans_entry_name" -> ansEntryName.map(lengthLimited),
      "action_ans_entry_context_cid" -> actionAnsEntryContextCid,
      "action_ans_entry_context_payment_id" -> actionAnsEntryContextPaymentId,
      "action_ans_entry_context_arc_type" -> actionAnsEntryContextArcType.map(lengthLimited),
      "subscription_reference_contract_id" -> subscriptionReferenceContractId,
      "subscription_next_payment_due_at" -> subscriptionNextPaymentDueAt,
      "featured_app_right_provider" -> featuredAppRightProvider,
      "sv_party" -> svParty,
      "sv_name" -> svName.map(lengthLimited),
      "wallet_party" -> walletParty,
    )
  }

  case class DsoTxLogRowData(
      entry: TxLogEntry,
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveAt: Option[String],
      votedAt: Option[String],
  ) extends TxLogRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "action_name" -> actionName.map(lengthLimited),
      "accepted" -> accepted,
      "requester_name" -> requester.map(lengthLimited),
      "effective_at" -> effectiveAt.map(lengthLimited),
      "voted_at" -> votedAt.map(lengthLimited),
    )
  }

  object DsoTxLogRowData extends StoreErrors {

    def fromTxLogEntry(record: TxLogEntry): DsoTxLogRowData = {
      record match {
        case err: ErrorTxLogEntry =>
          DsoTxLogRowData(
            entry = err,
            actionName = None,
            accepted = None,
            requester = None,
            effectiveAt = None,
            votedAt = None,
          )
        case vr: VoteRequestTxLogEntry =>
          val result = vr.result.getOrElse(throw txMissingField())
          DsoTxLogRowData(
            entry = vr,
            actionName = Some(TxLogEntry.mapActionName(result.request.action)),
            accepted = Some(VoteRequestOutcome.parse(result.outcome) match {
              case _: Accepted => true
              case _ => false
            }),
            requester = Some(result.request.requester),
            effectiveAt = VoteRequestOutcome.parse(result.outcome).effectiveAt match {
              case Some(effectiveAt) => Some(effectiveAt.toString)
              case None => None
            },
            votedAt = Some(result.completedAt.toString),
          )
        case _ => throw txLogIsOfWrongType(record.getClass.getSimpleName)
      }
    }
  }

  val acsTableName = "dso_acs_store"
  val txLogTableName = "dso_txlog_store"
}
