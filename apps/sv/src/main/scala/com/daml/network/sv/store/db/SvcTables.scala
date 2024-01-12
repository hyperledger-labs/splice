package com.daml.network.sv.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.wallet.subscriptions as sub
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue, TxLogRowData}
import com.daml.network.sv.store.SvcTxLogParser
import com.daml.network.sv.store.SvcTxLogParser.TxLogEntry
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{Member, PartyId}
import spray.json.JsValue

object SvcTables extends AcsTables with NamedLogging {

  final val CnsActionTypeCollectInitialEntryPayment = "CNSRARC_CollectInitialEntryPayment"
  final val CnsActionTypeRejectEntryInitialPayment = "CNSRARC_RejectEntryInitialPayment"

  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  case class SvcAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      coinRoundOfExpiry: Option[Long] = None,
      rewardRound: Option[Long] = None,
      rewardParty: Option[PartyId] = None,
      miningRound: Option[Long] = None,
      actionRequiringConfirmation: Option[JsValue] = None,
      confirmer: Option[PartyId] = None,
      svOnboardingToken: Option[String] = None,
      svCandidateParty: Option[PartyId] = None,
      svCandidateName: Option[String] = None,
      validator: Option[PartyId] = None,
      totalTrafficPurchased: Option[Long] = None,
      voter: Option[PartyId] = None,
      voteRequestCid: Option[cn.svcrules.VoteRequest.ContractId] = None,
      requester: Option[PartyId] = None,
      electionRequestEpoch: Option[Long] = None,
      importCrateReceiver: Option[PartyId] = None,
      memberTrafficMember: Option[Member] = None,
      cnsEntryName: Option[String] = None,
      actionCnsEntryContextCid: Option[cn.cns.CnsEntryContext.ContractId] = None,
      actionCnsEntryContextPaymentId: Option[sub.SubscriptionInitialPayment.ContractId] = None,
      actionCnsEntryContextArcType: Option[String] = None,
      subscriptionReferenceContractId: Option[sub.SubscriptionRequest.ContractId] = None,
      subscriptionNextPaymentDueAt: Option[Timestamp] = None,
      featuredAppRightProvider: Option[PartyId] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "coin_round_of_expiry" -> coinRoundOfExpiry,
      "reward_round" -> rewardRound,
      "reward_party" -> rewardParty,
      "mining_round" -> miningRound,
      "action_requiring_confirmation" -> actionRequiringConfirmation,
      "confirmer" -> confirmer,
      "sv_onboarding_token" -> svOnboardingToken.map(lengthLimited),
      "sv_candidate_party" -> svCandidateParty,
      "sv_candidate_name" -> svCandidateName.map(lengthLimited),
      "validator" -> validator,
      "total_traffic_purchased" -> totalTrafficPurchased,
      "voter" -> voter,
      "vote_request_cid" -> voteRequestCid,
      "requester" -> requester,
      "election_request_epoch" -> electionRequestEpoch,
      "import_crate_receiver" -> importCrateReceiver,
      "member_traffic_member" -> memberTrafficMember,
      "cns_entry_name" -> cnsEntryName.map(lengthLimited),
      "action_cns_entry_context_cid" -> actionCnsEntryContextCid,
      "action_cns_entry_context_payment_id" -> actionCnsEntryContextPaymentId,
      "action_cns_entry_context_arc_type" -> actionCnsEntryContextArcType.map(lengthLimited),
      "subscription_reference_contract_id" -> subscriptionReferenceContractId,
      "subscription_next_payment_due_at" -> subscriptionNextPaymentDueAt,
      "featured_app_right_provider" -> featuredAppRightProvider,
    )
  }

  case class SvcTxLogRowData(
      entry: TxLogEntry,
      actionName: Option[String],
      executed: Option[Boolean],
      requester: Option[String],
      effectiveAt: Option[String],
      votedAt: Option[String],
  ) extends TxLogRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "action_name" -> actionName.map(lengthLimited),
      "executed" -> executed,
      "requester" -> requester.map(lengthLimited),
      "effective_at" -> effectiveAt.map(lengthLimited),
      "voted_at" -> votedAt.map(lengthLimited),
    )
  }

  object SvcTxLogRowData {

    def fromTxLogEntry(record: SvcTxLogParser.TxLogEntry): SvcTxLogRowData = {
      record match {
        case err: SvcTxLogParser.TxLogEntry.ErrorTxLogEntry =>
          SvcTxLogRowData(
            entry = err,
            actionName = None,
            executed = None,
            requester = None,
            effectiveAt = None,
            votedAt = None,
          )
        case dv: SvcTxLogParser.TxLogEntry.DefiniteVoteTxLogEntry =>
          SvcTxLogRowData(
            entry = dv,
            actionName = Some(dv.actionName),
            executed = Some(dv.executed),
            requester = Some(dv.requester),
            effectiveAt = Some(dv.effectiveAt),
            votedAt = Some(dv.votedAt),
          )
      }
    }
  }

  val acsTableName = "svc_acs_store"
  val txLogTableName = "svc_txlog_store"
}
