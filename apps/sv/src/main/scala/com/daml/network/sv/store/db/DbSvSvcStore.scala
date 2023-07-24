package com.daml.network.sv.store.db

import com.daml.ledger.javaapi.data as javab
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.automation.TransferFollowTrigger
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.codegen.java.cn.svc.coinprice.CoinPriceVote
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import com.daml.network.store.{AcsStoreDump, Limit, MultiDomainAcsStore}
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.db.SvcTables.SvcAcsStoreRowData
import com.daml.network.sv.store.{ExpiredRewardCouponsBatch, SvStore, SvSvcStore}
import com.daml.network.util.Contract.Companion.Template
import com.daml.network.util.{Contract, ReadyContract, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.dbio.DBIO
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbSvSvcStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected[this] val domainConfig: SvDomainConfig,
    override protected[this] val enableCoinRulesUpgrade: Boolean,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage,
      DbSvSvcStore.tableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "name" -> Json.fromString("DbSvSvcStore"),
        "version" -> Json.fromInt(1),
        "svParty" -> Json.fromString(key.svParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
    )
    with SvSvcStore
    with AcsTables
    with AcsQueries
    with NamedLogging {

  import storage.DbStorageConverters.setParameterByteArray

  def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(createdEvent: javab.CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = {
    SvcAcsStoreRowData.fromCreatedEvent(createdEvent).map {
      case SvcAcsStoreRowData(
            contract,
            contractExpiresAt,
            coinRoundOfExpiry,
            rewardRound,
            rewardParty,
            miningRound,
            actionRequiringConfirmation,
            confirmer,
            svOnboardingToken,
            svCandidateParty,
            svCandidateName,
            validator,
            totalTrafficPurchased,
            voter,
            voteRequestCid,
            requester,
            electionRequestEpoch,
            importCrateReceiver,
          ) =>
        val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
        val templateId = contract.identifier
        val createArguments = payloadJsonFromContract(contract.payload)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        val safeSvOnboardingToken = svOnboardingToken.map(lengthLimited)
        val safeSvCandidateName = svCandidateName.map(lengthLimited)
        sqlu"""
              insert into svc_acs_store(store_id, contract_id, template_id, create_arguments, contract_metadata_created_at,
                                        contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
                                        coin_round_of_expiry, reward_round, reward_party, mining_round, action_requiring_confirmation,
                                        confirmer, sv_onboarding_token, sv_candidate_party, sv_candidate_name, validator,
                                        total_traffic_purchased, voter, vote_request_cid, requester, election_request_epoch,
                                        import_crate_receiver)
              values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
                      $coinRoundOfExpiry, $rewardRound, $rewardParty, $miningRound, $actionRequiringConfirmation,
                      $confirmer, $safeSvOnboardingToken, $svCandidateParty, $safeSvCandidateName, $validator,
                      $totalTrafficPurchased, $voter, $voteRequestCid, $requester, $electionRequestEpoch,
                      $importCrateReceiver)
              on conflict do nothing
            """
    }
  }

  override def lookupSvcRulesWithOffset(): Future[MultiDomainAcsStore.QueryResult[
    Option[ReadyContract[SvcRules.ContractId, SvcRules]]
  ]] = ???

  override protected def lookupCoinRulesWithOffset(): Future[MultiDomainAcsStore.QueryResult[
    Option[ReadyContract[CoinRules.ContractId, CoinRules]]
  ]] = ???

  override def lookupSvOnboardingConfirmedByPartyOnDomain(svParty: PartyId, domainId: DomainId)(
      implicit tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
  ]] = ???

  override def lookupCoinRulesV1TestWithOffset()(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[CoinRulesV1Test.ContractId, CoinRulesV1Test]]]
  ] = ???

  override def listConfirmations(action: ActionRequiringConfirmation)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = ???

  override def listAppRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]]] = ???

  override def listValidatorRewardCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]]] = ???

  override def listAppRewardCouponsGroupedByCounterparty(round: Long, totalCouponsLimit: Long)(
      implicit tc: TraceContext
  ): Future[Seq[Seq[AppRewardCoupon.ContractId]]] = ???

  override def listValidatorRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Long,
  )(implicit tc: TraceContext): Future[Seq[Seq[ValidatorRewardCoupon.ContractId]]] = ???

  override def getExpiredRewardsForOldestClosedMiningRound(totalCouponsLimit: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ExpiredRewardCouponsBatch]] = ???

  override def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[Option[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]] = ???

  override def listArchivableClosedMiningRounds()(implicit tc: TraceContext): Future[
    Seq[MultiDomainAcsStore.QueryResult[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]]
  ] = ???

  override def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]
  ] = ???

  override def lookupSvOnboardingRequestByTokenWithOffset(
      token: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = ???
  override def listSvOnboardingRequestsBySvcMembers(
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]] = ???

  override protected def listExpiredRoundBased[Id <: ContractId[T], T <: javab.Template](
      companion: Template[Id, T]
  )(coin: T => Coin): ListExpiredContracts[Id, T] = ???

  override def listUnclaimedRewards(limit: Long)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]] = ???

  override def listAllCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = ???

  override def listMemberCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = ???

  override protected def lookupSvOnboardingRequestByCandidatePartyWithOffset(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = ???

  override def lookupValidatorLicenseWithOffset(
      validator: PartyId
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[ValidatorLicense.ContractId, ValidatorLicense]]]
  ] = ???

  override def listDuplicateValidatorTrafficContracts(
      validator: PartyId,
      domainId: DomainId,
      limit: Int,
  )(implicit
      traceContext: TraceContext
  ): Future[Option[SvSvcStore.DuplicateValidatorTrafficContracts]] = ???

  override def listVotesByVoteRequests(voteRequestCids: Seq[VoteRequest.ContractId])(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Vote.ContractId, Vote]]] = ???

  override def lookupVoteByThisSvAndVoteRequestWithOffset(voteRequestCid: VoteRequest.ContractId)(
      implicit tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Contract[Vote.ContractId, Vote]]]] = ???

  override def lookupVoteRequestByThisSvAndActionWithOffset(
      action: ActionRequiringConfirmation
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[VoteRequest.ContractId, VoteRequest]]]
  ] = ???

  override def listEligibleVotes(voteRequestId: VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Vote.ContractId, Vote]]] = ???

  override def lookupCoinPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = ???

  override protected def lookupSvOnboardingRequestByCandidateNameWithOffset(
      candidateName: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = ???

  override protected def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
  ]] = ???

  override def listElectionRequests(svcRules: Contract[SvcRules.ContractId, SvcRules])(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ElectionRequest.ContractId, ElectionRequest]]] = ???

  override def lookupElectionRequestByRequesterWithOffset(requester: PartyId, epoch: Long)(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[ElectionRequest.ContractId, ElectionRequest]]]
  ] = ???

  override def getImportShipmentFor(receiver: PartyId)(implicit
      tc: TraceContext
  ): Future[AcsStoreDump.ImportShipment] = ???

  override protected[this] def listReadyContractsNotOnDomain[C, I <: ContractId[_], P](
      excludedDomain: DomainId,
      c: C,
  )(implicit
      tc: TraceContext,
      companion: MultiDomainAcsStore.ContractCompanion[C, I, P],
  ): Future[Seq[ReadyContract[I, P]]] = ???

  override def listSvcRulesTransferFollowers()(implicit
      tc: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[SvcRules.ContractId, SvcRules, _, _]]] = ???
}

object DbSvSvcStore {
  val tableName = "svc_acs_store"
}
