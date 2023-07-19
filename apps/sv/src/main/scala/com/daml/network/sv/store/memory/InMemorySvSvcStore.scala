package com.daml.network.sv.store.memory

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.automation.TransferFollowTrigger.Task as FollowTask
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.codegen.java.cc.{v1test as v1testcc, validatorlicense as vl}
import com.daml.network.codegen.java.cn.svc.coinprice as cp
import com.daml.network.codegen.java.cn.svc.coinprice.CoinPriceVote
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.{ContractCompanion, ContractState, QueryResult}
import com.daml.network.store.*
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.SvSvcStore.DuplicateValidatorTrafficContracts
import com.daml.network.sv.store.{ExpiredRewardCouponsBatch, SvStore, SvSvcStore}
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.daml.network.util.{CNNodeUtil, Contract, ContractWithState, ReadyContract}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class InMemorySvSvcStore(
    override val key: SvStore.Key,
    override protected[this] val appConfig: SvAppBackendConfig,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val
    ec: ExecutionContext
) extends InMemoryCNNodeAppStoreWithoutHistory
    with SvSvcStore {

  protected[this] override def listReadyContractsNotOnDomain[C, I <: ContractId[?], P](
      excludedDomain: DomainId,
      c: C,
  )(implicit
      tc: TraceContext,
      companion: ContractCompanion[C, I, P],
  ): Future[Seq[ReadyContract[I, P]]] =
    multiDomainAcsStore
      .listReadyContracts(c)
      .map(_.filterNot(_.domain == excludedDomain))

  override def lookupSvcRulesWithOffset(): Future[
    MultiDomainAcsStore.QueryResult[Option[ReadyContract[SvcRules.ContractId, SvcRules]]]
  ] = for {
    c <- multiDomainAcsStore.findContractWithOffset(cn.svcrules.SvcRules.COMPANION)()
  } yield c.map(_ flatMap (_.toReadyContract))

  override protected def lookupCoinRulesWithOffset()
      : Future[QueryResult[Option[ReadyContract[CoinRules.ContractId, CoinRules]]]] = for {
    result <- multiDomainAcsStore
      .findContractWithOffset(cc.coin.CoinRules.COMPANION)((_: Any) => true)
  } yield result map (_ flatMap (_.toReadyContract))

  override def lookupCoinRulesV1TestWithOffset()(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[CoinRulesV1Test.ContractId, CoinRulesV1Test]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .findContractOnDomainWithOffset(v1testcc.coin.CoinRulesV1Test.COMPANION)(
          _,
          (_: Any) => true,
        )
    )

  override protected def listExpiredRoundBased[Id <: ContractId[T], T <: Template](
      companion: TemplateCompanion[Id, T]
  )(coin: T => Coin): ListExpiredContracts[Id, T] = (_, limit) =>
    implicit tc =>
      for {
        maybeLatestOpenMiningRound <- lookupLatestActiveOpenMiningRound()
        result <- maybeLatestOpenMiningRound.fold(
          Future.successful(Seq.empty[ReadyContract[Id, T]])
        ) { latest =>
          for {
            domainId <- defaultAcsDomainIdF
            allExpired <- multiDomainAcsStore
              .filterContractsOnDomain(
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

  override def listConfirmations(
      action: ActionRequiringConfirmation
  )(implicit tc: TraceContext): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = {
    for {
      domain <- defaultAcsDomainIdF
      confirmations <- multiDomainAcsStore.filterContractsOnDomain(
        cn.svcrules.Confirmation.COMPANION,
        domain,
        (c: Contract[Confirmation.ContractId, Confirmation]) =>
          c.payload.action.toValue == action.toValue,
      )
    } yield confirmations
  }

  override def listAppRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]]] =
    multiDomainAcsStore.filterContractsOnDomain(
      cc.coin.AppRewardCoupon.COMPANION,
      domainId,
      (co: Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]) =>
        co.payload.round.number == round,
      limit = limit,
    )

  override def listAppRewardCouponsGroupedByCounterparty(round: Long, totalCouponsLimit: Long)(
      implicit tc: TraceContext
  ): Future[Seq[Seq[AppRewardCoupon.ContractId]]] = {
    for {
      defaultDomain <- defaultAcsDomainIdF
      appRewards <- listAppRewardCouponsOnDomain(round, defaultDomain, PageLimit(totalCouponsLimit))
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

  override def listValidatorRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]]] =
    multiDomainAcsStore.filterContractsOnDomain(
      cc.coin.ValidatorRewardCoupon.COMPANION,
      domainId,
      (co: Contract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]) =>
        co.payload.round.number == round,
      limit,
    )

  override def listValidatorRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Long,
  )(implicit tc: TraceContext): Future[Seq[Seq[ValidatorRewardCoupon.ContractId]]] = {
    for {
      defaultDomain <- defaultAcsDomainIdF
      validatorRewards <- listValidatorRewardCouponsOnDomain(
        round,
        defaultDomain,
        PageLimit(totalCouponsLimit),
      )
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

  override def getExpiredRewardsForOldestClosedMiningRound(
      totalCouponsLimit: Long
  )(implicit tc: TraceContext): Future[Seq[ExpiredRewardCouponsBatch]] = {
    lookupOldestClosedMiningRound()
      .flatMap {
        case Some(closedRound) =>
          for {
            appRewardGroups <- listAppRewardCouponsGroupedByCounterparty(
              closedRound.payload.round.number,
              totalCouponsLimit = totalCouponsLimit,
            )
            validatorRewardGroups <- listValidatorRewardCouponsGroupedByCounterparty(
              closedRound.payload.round.number,
              totalCouponsLimit = totalCouponsLimit,
            )
          } yield appRewardGroups.map(group =>
            ExpiredRewardCouponsBatch(closedRound, Seq.empty, group)
          ) ++
            validatorRewardGroups.map(group =>
              ExpiredRewardCouponsBatch(closedRound, group, Seq.empty)
            )
        case None => Future(Seq())
      }
  }

  override def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[Option[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]] = for {
    domain <- defaultAcsDomainIdF
    rounds <- multiDomainAcsStore.listContractsOnDomain(
      cc.round.ClosedMiningRound.COMPANION,
      domain,
    )
  } yield rounds.sortBy(_.payload.round.number).headOption

  override def listArchivableClosedMiningRounds()(implicit
      tc: TraceContext
  ): Future[Seq[QueryResult[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]]] = {
    for {
      domain <- defaultAcsDomainIdF
      closedRounds <- multiDomainAcsStore.listContractsOnDomain(
        cc.round.ClosedMiningRound.COMPANION,
        domain,
      )
      archivableClosedRounds <- closedRounds.traverse(round => {
        for {
          appRewardCoupons <- listAppRewardCouponsOnDomain(
            round.payload.round.number,
            domain,
            PageLimit(1L),
          )
          validatorRewardCoupons <- listValidatorRewardCouponsOnDomain(
            round.payload.round.number,
            domain,
            PageLimit(1L),
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
            ) Some(QueryResult(confirmationQueryResult.offset, round))
            else None
          )
        }
      })
    } yield archivableClosedRounds.flatten
  }

  override def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(cn.svcrules.Confirmation.COMPANION)(
        _,
        co =>
          co.payload.confirmer == confirmer.toProtoPrimitive && co.payload.action.toValue == action.toValue,
      )
    )

  override def lookupSvOnboardingRequestByTokenWithOffset(token: String)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingRequest.COMPANION)(
        _,
        co => co.payload.token == token,
      )
    )

  override def listSvOnboardingRequestsBySvcMembers(
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules]
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]] = for {
    domain <- defaultAcsDomainIdF
    svOnboardings <- multiDomainAcsStore.filterContractsOnDomain(
      so.SvOnboardingRequest.COMPANION,
      domain,
      (co: Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]) =>
        svcRules.payload.members.asScala
          .get(co.payload.candidateParty)
          .exists(_.name == co.payload.candidateName),
    )
  } yield svOnboardings

  override def listUnclaimedRewards(limit: Long)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]] = for {
    domain <- defaultAcsDomainIdF
    svOnboardings <- multiDomainAcsStore.filterContractsOnDomain(
      cc.coin.UnclaimedReward.COMPANION,
      domain,
      (_: Contract[cc.coin.UnclaimedReward.ContractId, cc.coin.UnclaimedReward]) => true,
      limit = PageLimit(limit),
    )
  } yield svOnboardings

  override def listAllCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = for {
    domain <- defaultAcsDomainIdF
    votes <- multiDomainAcsStore.listContractsOnDomain(
      cn.svc.coinprice.CoinPriceVote.COMPANION,
      domain,
    )
  } yield votes

  override def listMemberCoinPriceVotes()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = for {
    svcRules <- getSvcRules()
    votes <- listAllCoinPriceVotes()
  } yield {
    // Only use votes cast by current members, and thereof pick only one
    val eligibleVotes = votes.iterator.collect {
      case vote if svcRules.payload.members.containsKey(vote.payload.sv) =>
        (vote.payload.sv, vote)
    }
    val deduplicatedVotes = scala.collection.immutable.Map.from(eligibleVotes)
    deduplicatedVotes.values.toSeq
  }

  override protected def lookupSvOnboardingRequestByCandidatePartyWithOffset(
      candidateParty: PartyId
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingRequest.COMPANION)(
        _,
        co => co.payload.candidateParty == candidateParty.toProtoPrimitive,
      )
    )

  override def lookupValidatorLicenseWithOffset(validator: PartyId)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[ValidatorLicense.ContractId, ValidatorLicense]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(vl.ValidatorLicense.COMPANION)(
        _,
        co => co.payload.validator == validator.toProtoPrimitive,
      )
    )

  override def listDuplicateValidatorTrafficContracts(
      validator: PartyId,
      domainId: DomainId,
      limit: Int,
  )(implicit traceContext: TraceContext): Future[Option[DuplicateValidatorTrafficContracts]] = {
    multiDomainAcsStore
      .filterContractsOnDomain(
        cc.globaldomain.ValidatorTraffic.COMPANION,
        domainId,
        (vt: SvSvcStore.ValidatorTrafficContract) =>
          vt.payload.validator == validator.toProtoPrimitive && vt.payload.domainId == domainId.toProtoPrimitive,
      )
      // sort the contracts by total purchased traffic, and ...
      .map(_.sortWith(_.payload.totalPurchased > _.payload.totalPurchased))
      // ... pick the one with the highest traffic as the reference contract and consider the rest duplicates
      // upto a max of `limit` contracts
      .map(vts => vts.headOption.map(DuplicateValidatorTrafficContracts(_, vts.slice(1, limit))))
  }

  override def listVotesByVoteRequests(
      voteRequestCids: Seq[VoteRequest.ContractId]
  )(implicit tc: TraceContext): Future[Seq[Contract[Vote.ContractId, Vote]]] = {
    val cidSet = voteRequestCids.toSet
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.filterContractsOnDomain(
        cn.svcrules.Vote.COMPANION,
        _,
        co => cidSet.contains(co.payload.requestCid),
      )
    )
  }

  override def lookupVoteByThisSvAndVoteRequestWithOffset(voteRequestCid: VoteRequest.ContractId)(
      implicit tc: TraceContext
  ): Future[QueryResult[Option[Contract[Vote.ContractId, Vote]]]] = defaultAcsDomainIdF.flatMap(
    multiDomainAcsStore.findContractOnDomainWithOffset(cn.svcrules.Vote.COMPANION)(
      _,
      co =>
        co.payload.requestCid == voteRequestCid && co.payload.voter == key.svParty.toProtoPrimitive,
    )
  )

  override def lookupVoteRequestByThisSvAndActionWithOffset(
      action: ActionRequiringConfirmation
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[VoteRequest.ContractId, VoteRequest]]]
  ] = defaultAcsDomainIdF.flatMap(
    multiDomainAcsStore.findContractOnDomainWithOffset(cn.svcrules.VoteRequest.COMPANION)(
      _,
      co =>
        co.payload.requester == key.svParty.toProtoPrimitive && co.payload.action.toValue == action.toValue,
    )
  )

  /** List the votes that are eligible to determine the outcome of a vote request;
    * - the vote must refer to that request
    * - the vote must be cast by one of the given members
    * - there must not be any votes cast by the same member
    */
  override def listEligibleVotes(
      voteRequestId: VoteRequest.ContractId
  )(implicit tc: TraceContext): Future[Seq[Contract[Vote.ContractId, Vote]]] = for {
    domain <- defaultAcsDomainIdF
    votes <- multiDomainAcsStore.filterContractsOnDomain(
      cn.svcrules.Vote.COMPANION,
      domain,
      (c: Contract[Vote.ContractId, Vote]) => c.payload.requestCid == voteRequestId,
    )
  } yield votes.distinctBy(_.payload.voter)

  override def lookupCoinPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(cp.CoinPriceVote.COMPANION)(
        _,
        co => co.payload.sv == key.svParty.toProtoPrimitive,
      )
    )

  override protected def lookupSvOnboardingRequestByCandidateNameWithOffset(candidateName: String)(
      implicit tc: TraceContext
  ): Future[QueryResult[Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingRequest.COMPANION)(
        _,
        co => co.payload.candidateName == candidateName,
      )
    )

  override def lookupSvOnboardingConfirmedByPartyOnDomain(svParty: PartyId, domainId: DomainId)(
      implicit tc: TraceContext
  ): Future[
    QueryResult[Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]]
  ] = {
    multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingConfirmed.COMPANION)(
      domainId,
      (co: Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]) =>
        co.payload.svParty == svParty.toProtoPrimitive,
    )
  }

  override protected def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]]
  ] = defaultAcsDomainIdF.flatMap(
    multiDomainAcsStore.findContractOnDomainWithOffset(so.SvOnboardingConfirmed.COMPANION)(
      _,
      co => co.payload.svName == svName,
    )
  )

  override def listElectionRequests(svcRules: Contract[SvcRules.ContractId, SvcRules])(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ElectionRequest.ContractId, ElectionRequest]]] = defaultAcsDomainIdF
    .flatMap(
      multiDomainAcsStore.filterContractsOnDomain(
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

  override def lookupElectionRequestByRequesterWithOffset(requester: PartyId, epoch: Long)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[ElectionRequest.ContractId, ElectionRequest]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(cn.svcrules.ElectionRequest.COMPANION)(
        _,
        co => co.payload.epoch == epoch && co.payload.requester == requester.toProtoPrimitive,
      )
    )

  override def getImportShipmentFor(
      receiver: PartyId
  )(implicit tc: TraceContext): Future[AcsStoreDump.ImportShipment] = for {
    domainId <- this.defaultAcsDomainIdF
    openRound <- this.getLatestActiveOpenMiningRound()
    // Listing all crates is OK, as we assume the number of crates is small.
    allCrates <- multiDomainAcsStore.listContracts(cc.coinimport.ImportCrate.COMPANION)
    cratesForReceiver = allCrates.filter(crate =>
      crate.payload.receiver == receiver.toProtoPrimitive
    )
  } yield AcsStoreDump.ImportShipment(
    // It would be better if we received the ContractWithState directly from the underlying store.
    // This is OK though, as eventually the defaulAcsDomainIdF and the domain of the openRound should align.
    ContractWithState(openRound, ContractState.Assigned(domainId)),
    domainId,
    cratesForReceiver,
  )

  private[this] def listLaggingSvcRulesFollowers(targetDomain: DomainId)(implicit
      tc: TraceContext
  ): Future[Seq[ReadyContract[?, ?]]] = {
    import com.daml.network.codegen.java.cn.svcrules as svcr
    def c[C, I <: ContractId[?], P](c: C)(implicit companion: ContractCompanion[C, I, P]) =
      listReadyContractsNotOnDomain(targetDomain, c)

    for {
      coinRulesO <- lookupCoinRules()
      otherContracts <- Future sequence Seq(
        c(svcr.Vote.COMPANION),
        c(svcr.VoteRequest.COMPANION),
        c(svcr.Confirmation.COMPANION),
        c(svcr.SvReward.COMPANION),
        c(svcr.ElectionRequest.COMPANION),
        c(so.SvOnboardingRequest.COMPANION),
        c(so.SvOnboardingConfirmed.COMPANION),
      )
    } yield (otherContracts.view.flatten ++ coinRulesO
      .filterNot(_.domain == targetDomain)
      .toList).toSeq
  }

  override final def listSvcRulesTransferFollowers()(implicit
      tc: TraceContext
  ): Future[Seq[FollowTask[SvcRules.ContractId, SvcRules, _, _]]] = {
    lookupSvcRules().flatMap(_.map { svcRules =>
      listLaggingSvcRulesFollowers(svcRules.domain)
        .map(_ map (FollowTask(svcRules, _)))
    }.getOrElse(Future successful Seq.empty))
  }
}
