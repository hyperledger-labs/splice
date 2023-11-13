package com.daml.network.sv.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.wallet.subscriptions as sub
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.sv.store.SvcTxLogParser
import com.daml.network.util.Contract
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
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
      eventId: String,
      offset: Option[String],
      domainId: DomainId,
      indexRecordType: String3,
      actionName: Option[String],
      executed: Option[Boolean],
      requester: Option[String],
      effectiveAt: Option[String],
      votedAt: Option[String],
  )

  object SvcTxLogRowData {

    def fromTxLogIndexRecord(record: SvcTxLogParser.TxLogIndexRecord): SvcTxLogRowData = {
      record match {
        case err @ SvcTxLogParser.TxLogIndexRecord.ErrorIndexRecord(offset, eventId, domainId) =>
          SvcTxLogRowData(
            eventId = eventId,
            offset = Some(offset),
            domainId = domainId,
            indexRecordType = err.companion.dbType,
            actionName = None,
            executed = None,
            requester = None,
            effectiveAt = None,
            votedAt = None,
          )
        case dv @ SvcTxLogParser.TxLogIndexRecord.DefiniteVoteIndexRecord(
              offset,
              eventId,
              domainId,
              actionName,
              executed,
              requester,
              effectiveAt,
              votedAt,
            ) =>
          SvcTxLogRowData(
            eventId = eventId,
            offset = Some(offset),
            domainId = domainId,
            indexRecordType = dv.companion.dbType,
            actionName = Some(actionName),
            executed = Some(executed),
            requester = Some(requester),
            effectiveAt = Some(effectiveAt),
            votedAt = Some(votedAt),
          )
      }
    }
  }

}
