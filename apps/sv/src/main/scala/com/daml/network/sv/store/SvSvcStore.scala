package com.daml.network.sv.store

import com.daml.ledger.javaapi.data as javab
import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.automation.TransferFollowTrigger.Task as FollowTask
import com.daml.network.codegen.java.cc.coin.UnclaimedReward
import com.daml.network.codegen.java.cc.{v1test as v1testcc, validatorlicense as vl}
import com.daml.network.codegen.java.cn.svc.coinprice as cp
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRules_ConfirmSvOnboarding,
  VoteRequest,
}
import com.daml.network.codegen.java.cn.wallet.subscriptions as sub
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.{ContractCompanion, JsonAcsSnapshot, QueryResult}
import com.daml.network.store.*
import com.daml.network.sv.config.{SvAppBackendConfig, SvDomainConfig}
import com.daml.network.sv.store.SvSvcStore.ignoredContractsForAcsDump
import com.daml.network.sv.store.db.DbSvSvcStore
import com.daml.network.sv.store.memory.InMemorySvSvcStore
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.daml.network.util.{AssignedContract, CNNodeUtil, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

/* Store used by the SV app for filtering contracts visible to the SVC party. */
trait SvSvcStore extends CNNodeAppStoreWithoutHistory with ConfiguredDefaultDomain {

  protected val outerLoggerFactory: NamedLoggerFactory

  override protected lazy val loggerFactory: NamedLoggerFactory =
    outerLoggerFactory.append("store", "svcParty")

  override lazy val acsContractFilter =
    SvSvcStore.contractFilter(key.svcParty, key.svParty, enableCoinRulesUpgrade)

  def key: SvStore.Key

  protected[this] def domainConfig: SvDomainConfig
  protected[this] def enableCoinRulesUpgrade: Boolean

  override final def defaultAcsDomain = domainConfig.global.alias

  def lookupSvcRulesWithOffset(
  ): Future[
    QueryResult[Option[
      AssignedContract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
    ]]
  ]

  def lookupSvcRules()
      : Future[Option[AssignedContract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]] =
    lookupSvcRulesWithOffset().map(_.value)

  def getSvcRules(
  ): Future[AssignedContract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]] =
    lookupSvcRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active SvcRules contract")
        )
      )
    )

  def svIsLeader(): Future[Boolean] =
    getSvcRules().map(_.payload.leader == key.svParty.toProtoPrimitive)

  protected def lookupCoinRulesWithOffset(): Future[
    QueryResult[Option[
      AssignedContract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]
    ]]
  ]

  def lookupCoinRulesV1TestWithOffset(
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[v1testcc.coin.CoinRulesV1Test.ContractId, v1testcc.coin.CoinRulesV1Test]
    ]]
  ]

  def lookupCoinRules()
      : Future[Option[AssignedContract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    lookupCoinRulesWithOffset().map(_.value)

  def getCoinRules(): Future[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]] =
    lookupCoinRules().map(
      _.map(_.contract).getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active CoinRules contract")
        )
      )
    )

  protected def lookupCnsRulesWithOffset()
      : Future[QueryResult[Option[AssignedContract[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]]]]

  def lookupCnsRules()
      : Future[Option[AssignedContract[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]]] =
    lookupCnsRulesWithOffset().map(_.value)

  def getCnsRules(): Future[Contract[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]] =
    lookupCnsRules().map(
      _.map(_.contract).getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active CnsRules contract")
        )
      )
    )

  /** Lookup the triple of open mining rounds that should always be present after boostrapping. */
  def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[SvSvcStore.OpenMiningRoundTriple]] =
    for {
      domain <- defaultAcsDomainIdF
      openMiningRounds <- multiDomainAcsStore.listContractsOnDomain(
        cc.round.OpenMiningRound.COMPANION,
        domain,
      )
      result = openMiningRounds.sortBy(contract => contract.payload.round.number) match {
        case Seq(oldest, middle, newest)
            if oldest.payload.round.number + 1 == middle.payload.round.number &&
              newest.payload.round.number - 1 == middle.payload.round.number =>
          Some(
            SvSvcStore.OpenMiningRoundTriple(
              oldest = oldest,
              middle = middle,
              newest = newest,
              domain = domain,
            )
          )
        case _ => None
      }
    } yield result

  /** Get the triple of open mining rounds that should always be present after boostrapping. */
  def getOpenMiningRoundTriple()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[SvSvcStore.OpenMiningRoundTriple] =
    lookupOpenMiningRoundTriple().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No triple of OpenMiningRound contracts")
        )
      )
    )

  def lookupLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[SvSvcStore.OpenMiningRoundContract]] =
    lookupOpenMiningRoundTriple().map(_.map(_.newest))

  /** get the latest active open mining round contract, which should always be present after bootstrapping. */
  def getLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[SvSvcStore.OpenMiningRoundContract] = lookupLatestActiveOpenMiningRound().map(
    _.getOrElse(
      throw new StatusRuntimeException(
        Status.NOT_FOUND.withDescription("No active OpenMiningRound contract")
      )
    )
  )

  /** List coins that are expired and can never be used as transfer input. */
  def listExpiredCoins: ListExpiredContracts[cc.coin.Coin.ContractId, cc.coin.Coin] =
    listExpiredRoundBased(cc.coin.Coin.COMPANION)(identity)

  /** List locked coins that are expired and can never be used as transfer input. */
  def listLockedExpiredCoins
      : ListExpiredContracts[cc.coin.LockedCoin.ContractId, cc.coin.LockedCoin] =
    listExpiredRoundBased(cc.coin.LockedCoin.COMPANION)(_.coin)

  def listExpiredVoteRequests(): ListExpiredContracts[VoteRequest.ContractId, VoteRequest] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(VoteRequest.COMPANION)(_.expiresAt)

  def listConfirmations(
      action: cn.svcrules.ActionRequiringConfirmation
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]]

  def listAppRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]]]

  def listAppRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Long,
  )(implicit tc: TraceContext): Future[Seq[Seq[cc.coin.AppRewardCoupon.ContractId]]]

  def listValidatorRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]]
  ]

  def listValidatorRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Long,
  )(implicit tc: TraceContext): Future[Seq[Seq[cc.coin.ValidatorRewardCoupon.ContractId]]]

  def getExpiredRewardsForOldestClosedMiningRound(totalCouponsLimit: Long = 100L)(implicit
      tc: TraceContext
  ): Future[Seq[ExpiredRewardCouponsBatch]]

  def lookupOldestClosedMiningRound()(implicit tc: TraceContext): Future[
    Option[Contract[cc.round.ClosedMiningRound.ContractId, cc.round.ClosedMiningRound]]
  ]

  /** All `ClosedMiningRound` contracts that should be confirmed to be archived.
    *
    * These are all `ClosedMiningRound` contracts for which
    * 1. there are no left-over reward coupon contracts and
    * 2. there does not yet exist a ready-to-be-archived confirmation by this SV.
    *
    * Note: The QueryResult in the return value is composed of the closed mining round contract
    * and the offset from the query for the confirmation contract.
    */
  def listArchivableClosedMiningRounds()(implicit tc: TraceContext): Future[
    Seq[QueryResult[
      Contract[cc.round.ClosedMiningRound.ContractId, cc.round.ClosedMiningRound]
    ]]
  ]

  def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]
    ]]
  ]

  def lookupCnsInitialPaymentConfirmationByCnsNameWithOffset(
      confirmer: PartyId,
      name: String,
  )(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]]
  ]

  def lookupSvOnboardingRequestByTokenWithOffset(
      token: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ]

  def lookupSvOnboardingRequestByCandidateParty(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]
  ] = lookupSvOnboardingRequestByCandidatePartyWithOffset(candidateParty).map(_.value)

  def lookupSvOnboardingRequestByCandidateName(
      candidateName: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]
  ] = lookupSvOnboardingRequestByCandidateNameWithOffset(candidateName).map(_.value)

  def listExpiredSvOnboardingRequests
      : ListExpiredContracts[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(so.SvOnboardingRequest.COMPANION)(
      _.expiresAt
    )

  def listExpiredSvOnboardingConfirmed
      : ListExpiredContracts[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(so.SvOnboardingConfirmed.COMPANION)(
      _.expiresAt
    )

  def lookupSvOnboardingConfirmedByParty(
      svParty: PartyId
  )(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ] =
    lookupSvOnboardingConfirmedByPartyWithOffset(svParty).map(_.value)

  def lookupSvOnboardingConfirmedByPartyOnDomain(
      svParty: PartyId,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]]
  ]

  def lookupSvOnboardingConfirmedByName(
      svName: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ] =
    lookupSvOnboardingConfirmedByNameWithOffset(svName).map(_.value)

  def listSvOnboardingConfirmations(
      svOnboarding: Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]] = {
    val expectedAction = new ARC_SvcRules(
      new SRARC_ConfirmSvOnboarding(
        new SvcRules_ConfirmSvOnboarding(
          svOnboarding.payload.candidateParty,
          svOnboarding.payload.candidateName,
          svOnboarding.payload.token,
        )
      )
    )
    listConfirmations(expectedAction)
  }

  def listSvOnboardingRequestsBySvcMembers(
      svcRules: Contract.Has[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]

  protected def listExpiredRoundBased[Id <: javab.codegen.ContractId[T], T <: javab.Template](
      companion: TemplateCompanion[Id, T]
  )(coin: T => cc.coin.Coin): ListExpiredContracts[Id, T]

  def listUnclaimedRewards(
      limit: Long
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[UnclaimedReward.ContractId, cc.coin.UnclaimedReward]]]

  /** List issuing mining rounds past their targetClosesAt */
  def listExpiredIssuingMiningRounds
      : ListExpiredContracts[cc.round.IssuingMiningRound.ContractId, cc.round.IssuingMiningRound] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(cc.round.IssuingMiningRound.COMPANION)(
      _.targetClosesAt
    )

  /** List stale confirmations past their expiresAt */
  def listStaleConfirmations
      : ListExpiredContracts[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(cn.svcrules.Confirmation.COMPANION)(
      _.expiresAt
    )

  /** List all the current coin price votes. */
  def listAllCoinPriceVotes()(implicit tc: TraceContext): Future[
    Seq[Contract[cn.svc.coinprice.CoinPriceVote.ContractId, cn.svc.coinprice.CoinPriceVote]]
  ]

  /** List the current coin price votes by the SVC members. */
  def listMemberCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[cn.svc.coinprice.CoinPriceVote.ContractId, cn.svc.coinprice.CoinPriceVote]]
  ]

  protected def lookupSvOnboardingRequestByCandidatePartyWithOffset(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ]

  def lookupValidatorLicenseWithOffset(validator: PartyId)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[vl.ValidatorLicense.ContractId, vl.ValidatorLicense]]]]

  /** List all ValidatorLicenses */
  def listValidatorLicenses()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[vl.ValidatorLicense.ContractId, vl.ValidatorLicense]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(
        vl.ValidatorLicense.COMPANION,
        _,
      )
    )

  def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long]

  // TODO(#7146): Remove once we have completely switched over to MemberTraffic contracts
  def listDuplicateValidatorTrafficContracts(
      validator: PartyId,
      domainId: DomainId,
      limit: Int,
  )(implicit
      traceContext: TraceContext
  ): Future[Option[SvSvcStore.DuplicateValidatorTrafficContracts]]

  def listCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[cn.svc.coinprice.CoinPriceVote.ContractId, cn.svc.coinprice.CoinPriceVote]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(
        cn.svc.coinprice.CoinPriceVote.COMPANION,
        _,
      )
    )

  def listVoteRequests()(implicit tc: TraceContext): Future[
    Seq[Contract[cn.svcrules.VoteRequest.ContractId, cn.svcrules.VoteRequest]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(
        cn.svcrules.VoteRequest.COMPANION,
        _,
      )
    )

  def lookupVoteRequest(contractId: cn.svcrules.VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[cn.svcrules.VoteRequest.ContractId, cn.svcrules.VoteRequest]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .lookupContractByIdOnDomain(cn.svcrules.VoteRequest.COMPANION)(_, contractId)
    )

  def listVotesByVoteRequests(voteRequestCids: Seq[cn.svcrules.VoteRequest.ContractId])(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[cn.svcrules.Vote.ContractId, cn.svcrules.Vote]]
  ]

  def lookupVoteByThisSvAndVoteRequestWithOffset(
      voteRequestCid: cn.svcrules.VoteRequest.ContractId
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[cn.svcrules.Vote.ContractId, cn.svcrules.Vote]]]]

  def lookupVoteById(voteCid: cn.svcrules.Vote.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[cn.svcrules.Vote.ContractId, cn.svcrules.Vote]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.lookupContractByIdOnDomain(cn.svcrules.Vote.COMPANION)(_, voteCid)
    )

  def lookupVoteRequestByThisSvAndActionWithOffset(action: ActionRequiringConfirmation)(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[Contract[cn.svcrules.VoteRequest.ContractId, cn.svcrules.VoteRequest]]]
  ]

  /** List the votes that are eligible to determine the outcome of a vote request;
    * - the vote must refer to that request
    * - the vote must be cast by one of the given members
    * - there must not be any votes cast by the same member
    */
  def listEligibleVotes(voteRequestId: VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cn.svcrules.Vote.ContractId, cn.svcrules.Vote]]]

  def lookupCoinPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[cp.CoinPriceVote.ContractId, cp.CoinPriceVote]]]

  protected def lookupSvOnboardingRequestByCandidateNameWithOffset(
      candidateName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ]

  protected def lookupSvOnboardingConfirmedByPartyWithOffset(
      svParty: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]]
  ] = defaultAcsDomainIdF.flatMap(
    lookupSvOnboardingConfirmedByPartyOnDomain(svParty, _)
  )

  protected def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]]
  ]

  def listElectionRequests(
      svcRules: AssignedContract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.svcrules.ElectionRequest.ContractId, cn.svcrules.ElectionRequest]]
  ]

  def lookupElectionRequestByRequesterWithOffset(
      requester: PartyId,
      epoch: Long,
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[cn.svcrules.ElectionRequest.ContractId, cn.svcrules.ElectionRequest]
    ]]
  ]

  def listExpiredElectionRequests(
      epoch: Long
  )(implicit tc: TraceContext): Future[Seq[Contract[
    cn.svcrules.ElectionRequest.ContractId,
    cn.svcrules.ElectionRequest,
  ]]]

  def getJsonAcsSnapshot(): Future[JsonAcsSnapshot] =
    multiDomainAcsStore.getJsonAcsSnapshot(ignoredContractsForAcsDump)

  def getImportShipmentFor(
      receiver: PartyId
  )(implicit tc: TraceContext): Future[AcsStoreDump.ImportShipment]

  protected[this] def listAssignedContractsNotOnDomain[C, I <: ContractId[?], P](
      excludedDomain: DomainId,
      c: C,
  )(implicit
      tc: TraceContext,
      companion: ContractCompanion[C, I, P],
  ): Future[Seq[AssignedContract[I, P]]]

  def listSvcRulesTransferFollowers()(implicit
      tc: TraceContext
  ): Future[Seq[FollowTask[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules, ?, ?]]]

  def listCoinRulesTransferFollowers()(implicit
      tc: TraceContext
  ): Future[Seq[FollowTask[cc.coin.CoinRules.ContractId, cc.coin.CoinRules, ?, ?]]]

  protected def lookupCnsEntryByNameWithOffset(name: String): Future[
    QueryResult[Option[AssignedContract[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]]
  ]

  def lookupCnsEntryByName(
      name: String
  ): Future[Option[AssignedContract[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]] =
    lookupCnsEntryByNameWithOffset(name).map(_.value)

  def lookupCnsEntryContext(contractId: cn.cns.CnsEntryContext.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[cn.cns.CnsEntryContext.ContractId, cn.cns.CnsEntryContext]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .lookupContractByIdOnDomain(cn.cns.CnsEntryContext.COMPANION)(_, contractId)
    )

  protected def listCnsEntryContextByCnsName(name: String)(implicit tc: TraceContext): Future[
    Seq[Contract[cn.cns.CnsEntryContext.ContractId, cn.cns.CnsEntryContext]]
  ]

  def listCnsInitialPaymentConfirmationByCnsName(
      confirmer: PartyId,
      name: String,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]
  ]

  def lookupFeaturedAppRightWithOffset(
      providerPartyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[AssignedContract[cc.coin.FeaturedAppRight.ContractId, cc.coin.FeaturedAppRight]]
    ]
  ]

  def lookupFeaturedAppRight(
      providerPartyId: PartyId
  )(implicit
      tc: TraceContext
  ): Future[
    Option[AssignedContract[cc.coin.FeaturedAppRight.ContractId, cc.coin.FeaturedAppRight]]
  ] =
    lookupFeaturedAppRightWithOffset(providerPartyId).map(_.value)

  def getSvcTransferContextForRound(round: cc.api.v1.round.Round)(implicit
      tc: TraceContext
  ): Future[Option[cc.api.v1.coin.AppTransferContext]] = {
    for {
      triple <- getOpenMiningRoundTriple()
      openRounds = triple.toSeq
      featured <- lookupFeaturedAppRight(key.svcParty)
      coinRules <- getCoinRules()
    } yield {
      openRounds.find(_.payload.round == round) map { openMiningRound =>
        new cc.api.v1.coin.AppTransferContext(
          coinRules.contractId.toInterface(cc.api.v1.coin.CoinRules.INTERFACE),
          openMiningRound.contractId
            .toInterface(cc.api.v1.round.OpenMiningRound.INTERFACE),
          featured
            .map(_.contractId.toInterface(cc.api.v1.coin.FeaturedAppRight.INTERFACE))
            .toJava,
        )
      }
    }
  }
}

object SvSvcStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      config: SvAppBackendConfig,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvcStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySvSvcStore(
          key,
          config.domains,
          config.enableCoinRulesUpgrade,
          loggerFactory,
          retryProvider,
        )
      case db: DbStorage =>
        new DbSvSvcStore(
          key,
          db,
          config.domains,
          config.enableCoinRulesUpgrade,
          loggerFactory,
          retryProvider,
        )
    }

  val ignoredContractsForAcsDump: Set[Identifier] = Set(
    // Note: these three kinds of contracts are not included in an ACS dump due to the ExpireUnclaimedRewards trigger
    // being disabled, which leads to a too high growth of the ACS export per hour.
    cc.coin.AppRewardCoupon.COMPANION.TEMPLATE_ID,
    cc.coin.ValidatorRewardCoupon.COMPANION.TEMPLATE_ID,
    cc.round.ClosedMiningRound.COMPANION.TEMPLATE_ID,
  )

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(
      svcParty: PartyId,
      svParty: PartyId,
      enableCoinRulesUpgrade: Boolean,
  ): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive
    val sv = svParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cn.svc.coinprice.CoinPriceVote.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.Confirmation.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.ElectionRequest.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.VoteRequest.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.Vote.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.SvcRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.SvReward.COMPANION)(co =>
          co.payload.svc == svc && co.payload.sv == sv
        ),
        mkFilter(so.SvOnboardingRequest.COMPANION)(co => co.payload.svc == svc),
        mkFilter(so.SvOnboardingConfirmed.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.Coin.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.FeaturedAppRight.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.LockedCoin.COMPANION)(co => co.payload.coin.svc == svc),
        mkFilter(cc.coinimport.ImportCrate.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.SvcReward.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.UnclaimedReward.COMPANION)(co => co.payload.svc == svc),
        mkFilter(vl.ValidatorLicense.COMPANION)(vl => vl.payload.svc == svc),
        mkFilter(cc.globaldomain.ValidatorTraffic.COMPANION)(vt => vt.payload.svc == svc),
        mkFilter(cc.globaldomain.MemberTraffic.COMPANION)(vt => vt.payload.svc == svc),
        mkFilter(cn.cns.CnsRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.cns.CnsEntry.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.cns.CnsEntryContext.COMPANION)(co => co.payload.svc == svc),
        mkFilter(sub.SubscriptionInitialPayment.COMPANION)(co =>
          co.payload.subscriptionData.svc == svc && co.payload.subscriptionData.provider == svc
        ),
      ) ++
        (if (enableCoinRulesUpgrade)
           Map(mkFilter(v1testcc.coin.CoinRulesV1Test.COMPANION)(co => co.payload.svc == svc))
         else Map.empty) ++
        DirectoryStore.directoryTemplateFilters(svcParty),
    )
  }

  type OpenMiningRoundContract =
    Contract[cc.round.OpenMiningRound.ContractId, cc.round.OpenMiningRound]

  case class OpenMiningRoundTriple(
      oldest: OpenMiningRoundContract,
      middle: OpenMiningRoundContract,
      newest: OpenMiningRoundContract,
      domain: DomainId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("oldest", _.oldest),
        param("middle", _.middle),
        param("newest", _.newest),
        param("domain", _.domain),
      )

    /** The time after which these can be advanced at assuming the given tick duration. */
    def readyToAdvanceAt: Instant = {
      val middleTickDuration = CNNodeUtil.relTimeToDuration(
        middle.payload.tickDuration
      )
      Ordering[Instant].max(
        oldest.payload.targetClosesAt,
        Ordering[Instant].max(
          // TODO(M3-07): when changing CoinConfigs it will make sense to store tickDuration on the rounds and express targetClosesAt as 2 * tickDuration
          middle.payload.opensAt.plus(middleTickDuration),
          newest.payload.opensAt,
        ),
      )
    }

    def toSeq: Seq[OpenMiningRoundContract] = Seq(oldest, middle, newest)
  }

  type ValidatorTrafficContract =
    Contract[cc.globaldomain.ValidatorTraffic.ContractId, cc.globaldomain.ValidatorTraffic]

  case class DuplicateValidatorTrafficContracts(
      reference: ValidatorTrafficContract,
      duplicates: Seq[ValidatorTrafficContract],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("reference", _.reference),
        param("duplicates", _.duplicates),
      )
  }
}

case class ExpiredRewardCouponsBatch(
    closedRound: Contract[cc.round.ClosedMiningRound.ContractId, cc.round.ClosedMiningRound],
    validatorCoupons: Seq[cc.coin.ValidatorRewardCoupon.ContractId],
    appCoupons: Seq[cc.coin.AppRewardCoupon.ContractId],
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("closedRound", _.closedRound),
      customParam(inst => s"validatorCoupons: ${inst.validatorCoupons}"),
      customParam(inst => s"appCoupons: ${inst.appCoupons}"),
    )
}
