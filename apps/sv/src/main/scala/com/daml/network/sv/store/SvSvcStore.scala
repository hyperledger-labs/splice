package com.daml.network.sv.store

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data as javab
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.codegen.java.cc.v1test as v1testcc
import com.daml.network.codegen.java.cc.coin.{CoinRules_MiningRound_Archive, UnclaimedReward}
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.codegen.java.cc.validatorlicense as vl
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRules_ConfirmSvOnboarding,
}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{CNNodeAppStoreWithoutHistory, MultiDomainAcsStore}
import com.daml.network.store.MultiDomainAcsStore.{QueryResult, ReadyContract}
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.memory.InMemorySvSvcStore
import com.daml.network.util.{CNNodeUtil, Contract}
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/* Store used by the SV app for filtering contracts visible to the SVC party. */
trait SvSvcStore extends CNNodeAppStoreWithoutHistory {

  def key: SvStore.Key

  protected[this] def appConfig: SvAppBackendConfig

  override final def defaultAcsDomain = appConfig.domains.global.alias

  private def defaultAcsDomainIdF(implicit tc: TraceContext) =
    domains.signalWhenConnected(defaultAcsDomain)

  def lookupSvcRulesWithOffset(
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
    ]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .findContractOnDomainWithOffset(cn.svcrules.SvcRules.COMPANION)(_, (_: Any) => true)
    )

  def lookupSvcRules()(implicit
      tc: TraceContext
  ): Future[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]] =
    lookupSvcRulesWithOffset().map(_.value)

  def getSvcRules(
  )(implicit
      tc: TraceContext
  ): Future[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]] =
    lookupSvcRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active SvcRules contract")
        )
      )
    )

  def svIsLeader()(implicit tc: TraceContext): Future[Boolean] =
    getSvcRules().map(_.payload.leader == key.svParty.toProtoPrimitive)

  def lookupCoinRulesWithOffset(
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]
    ]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .findContractOnDomainWithOffset(cc.coin.CoinRules.COMPANION)(_, (_: Any) => true)
    )

  def lookupCoinRulesV1TestWithOffset(
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[v1testcc.coin.CoinRulesV1Test.ContractId, v1testcc.coin.CoinRulesV1Test]
    ]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .findContractOnDomainWithOffset(v1testcc.coin.CoinRulesV1Test.COMPANION)(
          _,
          (_: Any) => true,
        )
    )

  def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    lookupCoinRulesWithOffset().map(_.value)

  def getCoinRules()(implicit
      tc: TraceContext
  ): Future[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]] =
    lookupCoinRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active CoinRules contract")
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
        case Seq(oldest, middle, newest) =>
          Some(SvSvcStore.OpenMiningRoundTriple(oldest = oldest, middle = middle, newest = newest))
        case _ => None
      }
    } yield result

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

  def listConfirmations(
      action: cn.svcrules.ActionRequiringConfirmation
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]] = {
    for {
      domain <- defaultAcsDomainIdF
      confirmations <- multiDomainAcsStore.listContractsOnDomain(
        cn.svcrules.Confirmation.COMPANION,
        domain,
      )
    } yield confirmations.filter(_.payload.action.toValue == action.toValue)
  }

  def listAppRewardCoupons(
      round: Long,
      limit: Option[Long] = None,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(
        cc.coin.AppRewardCoupon.COMPANION,
        _,
        (co: Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]) =>
          co.payload.round.number == round,
        limit,
      )
    )

  def listAppRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Option[Long],
  )(implicit tc: TraceContext): Future[Seq[Seq[cc.coin.AppRewardCoupon.ContractId]]] = {
    for {
      appRewards <- listAppRewardCoupons(round, totalCouponsLimit)
      providerToCoupons = appRewards.foldLeft(
        Map[String, Seq[cc.coin.AppRewardCoupon.ContractId]]()
      ) { (m, r) =>
        m +
          (r.payload.provider -> (Seq(r.contractId) ++ m.getOrElse(
            r.payload.provider,
            Seq[cc.coin.AppRewardCoupon.ContractId](),
          )))
      }
      appRewardCouponsGrouped = providerToCoupons.toSeq.map { case (_, coupons) => coupons }
    } yield appRewardCouponsGrouped
  }

  def listValidatorRewardCoupons(
      round: Long,
      limit: Option[Long] = None,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(
        cc.coin.ValidatorRewardCoupon.COMPANION,
        _,
        (co: Contract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]) =>
          co.payload.round.number == round,
        limit,
      )
    )

  def listValidatorRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Option[Long],
  )(implicit tc: TraceContext): Future[Seq[Seq[cc.coin.ValidatorRewardCoupon.ContractId]]] = {
    for {
      validatorRewards <- listValidatorRewardCoupons(round, totalCouponsLimit)
      validatorToCoupons = validatorRewards.foldLeft(
        Map[String, Seq[cc.coin.ValidatorRewardCoupon.ContractId]]()
      ) { (m, r) =>
        m +
          (r.payload.user -> (Seq(r.contractId) ++ m.getOrElse(
            r.payload.user,
            Seq[cc.coin.ValidatorRewardCoupon.ContractId](),
          )))
      }
      validatorRewardCouponsGrouped = validatorToCoupons.toSeq.map { case (_, coupons) => coupons }
    } yield validatorRewardCouponsGrouped
  }

  def lookupOldestClosedMiningRound()(implicit tc: TraceContext): Future[
    Option[Contract[cc.round.ClosedMiningRound.ContractId, cc.round.ClosedMiningRound]]
  ] =
    for {
      domain <- defaultAcsDomainIdF
      rounds <- multiDomainAcsStore.listContractsOnDomain(
        cc.round.ClosedMiningRound.COMPANION,
        domain,
      )
    } yield rounds.sortBy(_.payload.round.number).headOption

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
  ] = {
    for {
      domain <- defaultAcsDomainIdF
      closedRounds <- multiDomainAcsStore.listContractsOnDomain(
        cc.round.ClosedMiningRound.COMPANION,
        domain,
      )
      archivableClosedRounds <- closedRounds.traverse(round => {
        for {
          appRewardCoupons <- listAppRewardCoupons(round.payload.round.number, Some(1))
          validatorRewardCoupons <- listValidatorRewardCoupons(
            round.payload.round.number,
            Some(1),
          )
          coinRules <- getCoinRules()
          action = new ARC_CoinRules(
            coinRules.contractId,
            new CRARC_MiningRound_Archive(
              new CoinRules_MiningRound_Archive(
                round.contractId
              )
            ),
          )
          confirmationQueryResult <- lookupConfirmationByActionWithOffset(key.svParty, action)
        } yield {
          (
            // archivable if ...
            if (
              // ... there are no unclaimed rewards left in this round
              appRewardCoupons.isEmpty && validatorRewardCoupons.isEmpty &&
              // ... and a confirmation to archive is not already created by this SV
              confirmationQueryResult.value.isEmpty
            ) Some(QueryResult(confirmationQueryResult.offsets, round))
            else None
          )
        }
      })
    } yield archivableClosedRounds.flatten
  }

  def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]
    ]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(cn.svcrules.Confirmation.COMPANION)(
        _,
        co =>
          co.payload.confirmer == confirmer.toProtoPrimitive && co.payload.action.toValue == action.toValue,
      )
    )

  def lookupSvOnboardingRequestByTokenWithOffset(
      token: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingRequest.COMPANION)(
        _,
        co => co.payload.token == token,
      )
    )

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
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]] =
    for {
      domain <- defaultAcsDomainIdF
      svOnboardings <- multiDomainAcsStore.listContractsOnDomain(
        so.SvOnboardingRequest.COMPANION,
        domain,
        (co: Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]) =>
          svcRules.payload.members.asScala
            .get(co.payload.candidateParty)
            .exists(_.name == co.payload.candidateName),
      )
    } yield svOnboardings

  private[this] def listExpiredRoundBased[Id <: javab.codegen.ContractId[T], T <: javab.Template](
      companion: TemplateCompanion[Id, T]
  )(coin: T => cc.coin.Coin): ListExpiredContracts[Id, T] = (_, limit) =>
    implicit tc =>
      for {
        maybeLatestOpenMiningRound <- lookupLatestActiveOpenMiningRound()
        result <- maybeLatestOpenMiningRound.fold(
          Future.successful(Seq.empty[ReadyContract[Id, T]])
        ) { latest =>
          for {
            domainId <- defaultAcsDomainIdF
            allExpired <- multiDomainAcsStore
              .listContractsOnDomain(
                companion,
                domainId,
                (e: Contract[Id, T]) =>
                  CNNodeUtil
                    .coinExpiresAt(coin(e.payload))
                    .number <= latest.payload.round.number - 2,
              )
          } yield allExpired.view.take(limit).map(ReadyContract(_, domainId)).toSeq
        }
      } yield result

  def listUnclaimedRewards(
      limit: Long
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[UnclaimedReward.ContractId, cc.coin.UnclaimedReward]]] =
    for {
      domain <- defaultAcsDomainIdF
      svOnboardings <- multiDomainAcsStore.listContractsOnDomain(
        cc.coin.UnclaimedReward.COMPANION,
        domain,
        (_: Contract[cc.coin.UnclaimedReward.ContractId, cc.coin.UnclaimedReward]) => true,
        Some(limit),
      )
    } yield svOnboardings

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

  /** List the current coin price votes by the SVC members. */
  def listMemberCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[cn.svc.coinprice.CoinPriceVote.ContractId, cn.svc.coinprice.CoinPriceVote]]
  ] =
    for {
      domain <- defaultAcsDomainIdF
      svcRules <- getSvcRules()
      votes <- multiDomainAcsStore.listContractsOnDomain(
        cn.svc.coinprice.CoinPriceVote.COMPANION,
        domain,
      )
    } yield {
      // Only use votes cast by current members, and thereof pick only one
      val eligibleVotes = votes.iterator.collect {
        case vote if svcRules.payload.members.containsKey(vote.payload.sv) =>
          (vote.payload.sv, vote)
      }
      val deduplicatedVotes = scala.collection.immutable.Map.from(eligibleVotes)
      deduplicatedVotes.values.toSeq
    }

  private def lookupSvOnboardingRequestByCandidatePartyWithOffset(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingRequest.COMPANION)(
        _,
        co => co.payload.candidateParty == candidateParty.toProtoPrimitive,
      )
    )

  /** List all ValidatorLicenses */
  def listValidatorLicenses()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[vl.ValidatorLicense.ContractId, vl.ValidatorLicense]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.listContractsOnDomain(vl.ValidatorLicense.COMPANION, _)
    )

  private def lookupSvOnboardingRequestByCandidateNameWithOffset(
      candidateName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingRequest.COMPANION)(
        _,
        co => co.payload.candidateName == candidateName,
      )
    )

  private def lookupSvOnboardingConfirmedByPartyWithOffset(
      svParty: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingConfirmed.COMPANION)(
        _,
        co => co.payload.svParty == svParty.toProtoPrimitive,
      )
    )

  private def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingConfirmed.COMPANION)(
        _,
        co => co.payload.svName == svName,
      )
    )

  def listElectionRequests(
      svcRules: Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.svcrules.ElectionRequest.ContractId, cn.svcrules.ElectionRequest]]
  ] =
    defaultAcsDomainIdF
      .flatMap(
        multiDomainAcsStore.listContractsOnDomain(
          cn.svcrules.ElectionRequest.COMPANION,
          _,
          {
            c: Contract[
              cn.svcrules.ElectionRequest.ContractId,
              cn.svcrules.ElectionRequest,
            ] =>
              svcRules.payload.members.keySet
                .contains(c.payload.requester) && c.payload.epoch == svcRules.payload.epoch
          },
        )
      )
      .map(
        _.foldLeft(
          Map[String, Contract[
            cn.svcrules.ElectionRequest.ContractId,
            cn.svcrules.ElectionRequest,
          ]]()
        ) { (acc, contract) =>
          if (acc.contains(contract.payload.requester)) acc
          else acc + (contract.payload.requester -> contract)
        }
      )
      .map(dict => dict.values.toSeq)
}

object SvSvcStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      config: SvAppBackendConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
      retryProvider: RetryProvider,
  )(implicit ec: ExecutionContext): SvSvcStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySvSvcStore(
          key,
          config,
          loggerFactory,
          futureSupervisor,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(
      svcParty: PartyId,
      svParty: PartyId,
      appConfig: SvAppBackendConfig,
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
        mkFilter(cn.svcrules.SvcRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.SvReward.COMPANION)(co =>
          co.payload.svc == svc && co.payload.sv == sv
        ),
        mkFilter(so.SvOnboardingRequest.COMPANION)(co => co.payload.svc == svc),
        mkFilter(so.SvOnboardingConfirmed.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.Coin.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.LockedCoin.COMPANION)(co => co.payload.coin.svc == svc),
        mkFilter(cc.coin.SvcReward.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.UnclaimedReward.COMPANION)(co => co.payload.svc == svc),
        mkFilter(vl.ValidatorLicense.COMPANION)(vl => vl.payload.svc == svc),
        // TODO(M3-46): copy more of the filter over from SvcStore, as we merge more triggers and console commands
      ) ++
        (if (appConfig.enableCoinRulesUpgrade)
           Map(mkFilter(v1testcc.coin.CoinRulesV1Test.COMPANION)(co => co.payload.svc == svc))
         else Map.empty),
    )
  }

  type OpenMiningRoundContract =
    Contract[cc.round.OpenMiningRound.ContractId, cc.round.OpenMiningRound]

  case class OpenMiningRoundTriple(
      oldest: OpenMiningRoundContract,
      middle: OpenMiningRoundContract,
      newest: OpenMiningRoundContract,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("oldest", _.oldest), param("middle", _.middle), param("newest", _.newest))

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
}
