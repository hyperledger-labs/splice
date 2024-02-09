package com.daml.network.sv.store.memory

import cats.implicits.toTraverseOps
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cc.validatorlicense.{ValidatorFaucetCoupon, ValidatorLicense}
import com.daml.network.codegen.java.cn.svc.coinprice as cp
import com.daml.network.codegen.java.cn.svc.coinprice.CoinPriceVote
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CnsEntryContext
import com.daml.network.codegen.java.cn.svcrules.cnsentrycontext_actionrequiringconfirmation.{
  CNSRARC_CollectInitialEntryPayment,
  CNSRARC_RejectEntryInitialPayment,
}
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.codegen.java.cn.svonboarding.{SvOnboardingConfirmed, SvOnboardingRequest}
import com.daml.network.codegen.java.cn.wallet.subscriptions.{
  SubscriptionIdleState,
  SubscriptionInitialPayment,
  SubscriptionRequest,
}
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.RetryProvider
import com.daml.network.store.*
import MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.TxLogEntry.mapActionName
import com.daml.network.sv.store.{DefiniteVoteTxLogEntry, SvStore, SvSvcStore, TxLogEntry}
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.daml.network.util.{
  AssignedContract,
  CNNodeUtil,
  Contract,
  ContractWithState,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class InMemorySvSvcStore(
    override val key: SvStore.Key,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    override protected val templateJsonDecoder: TemplateJsonDecoder,
) extends InMemoryCNNodeAppStore[TxLogEntry]
    with SvSvcStore
    with LimitHelpers {
  import InMemorySvSvcStore.*

  override def listVoteResults(
      actionName: Option[String],
      executed: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[VoteResult]] = {
    for {
      entries <- multiDomainAcsStore
        .collectTxLogIndicesType[DefiniteVoteTxLogEntry]
      results = entries.map(_.result.getOrElse(throw txMissingField()))
      ind = actionName match {
        case Some(actionName) => results.filter(e => mapActionName(e.action).contains(actionName))
        case None => results
      }
      ind2 = executed match {
        case Some(executed) => ind.filter(_.executed == executed)
        case None => ind
      }
      ind3 = requester match {
        case Some(requester) => ind2.filter(_.requester.contains(requester))
        case None => ind2
      }
      records = ind3.flatMap { entry =>
        val effectiveAt = entry.effectiveAt
        (effectiveFrom, effectiveTo) match {
          case (Some(effectiveFromDate), Some(effectiveToDate))
              if effectiveAt.isAfter(Instant.parse(effectiveFromDate)) && effectiveAt.isBefore(
                Instant.parse(effectiveToDate)
              ) =>
            Some(entry)
          case (Some(effectiveFromDate), None)
              if effectiveAt.isAfter(Instant.parse(effectiveFromDate)) =>
            Some(entry)
          case (None, Some(effectiveToDate))
              if effectiveAt.isBefore(Instant.parse(effectiveToDate)) =>
            Some(entry)
          case (None, None) =>
            Some(entry)
          case _ => None
        }
      }
    } yield applyLimit("listVoteResults", limit, records)
  }

  override protected def listExpiredRoundBased[Id <: ContractId[T], T <: Template](
      companion: TemplateCompanion[Id, T]
  )(coin: T => Coin): ListExpiredContracts[Id, T] = (_, limit) =>
    implicit tc =>
      for {
        maybeLatestOpenMiningRound <- lookupLatestActiveOpenMiningRound()
        result <- maybeLatestOpenMiningRound.fold(
          Future.successful(Seq.empty[AssignedContract[Id, T]])
        ) { latest =>
          for {
            domainId <- getSvcRules().map(_.domain)
            allExpired <- multiDomainAcsStore
              .filterContractsOnDomain(
                companion,
                domainId,
                (e: Contract[Id, T]) =>
                  CNNodeUtil
                    .coinExpiresAt(coin(e.payload))
                    .number <= latest.payload.round.number - 2,
              )
          } yield allExpired.view.take(limit.limit).map(AssignedContract(_, domainId)).toSeq
        }
      } yield result

  override def listConfirmations(
      action: ActionRequiringConfirmation,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] =
    for {
      confirmations <- multiDomainAcsStore.filterContracts(
        cn.svcrules.Confirmation.COMPANION,
        (c: Contract[?, Confirmation]) => c.payload.action.toValue == action.toValue,
        limit,
      )
    } yield confirmations map (_.contract)

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

  override def listAppRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Seq[AppRewardCoupon.ContractId]]] = {
    for {
      appRewards <- listAppRewardCouponsOnDomain(
        roundNumber,
        roundDomain,
        totalCouponsLimit,
      )
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
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Seq[ValidatorRewardCoupon.ContractId]]] = {
    for {
      validatorRewards <- listValidatorRewardCouponsOnDomain(
        roundNumber,
        roundDomain,
        totalCouponsLimit,
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

  override def listValidatorFaucetCouponsOnDomain(round: Long, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[ValidatorFaucetCoupon.ContractId, ValidatorFaucetCoupon]]] = {
    multiDomainAcsStore.filterContractsOnDomain(
      cc.validatorlicense.ValidatorFaucetCoupon.COMPANION,
      domainId,
      (co: Contract[ValidatorFaucetCoupon.ContractId, ValidatorFaucetCoupon]) =>
        co.payload.round.number == round,
      limit,
    )
  }

  override def listValidatorFaucetCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Seq[ValidatorFaucetCoupon.ContractId]]] = {
    for {
      validatorFaucets <- listValidatorFaucetCouponsOnDomain(
        roundNumber,
        roundDomain,
        totalCouponsLimit,
      )
      validatorToCoupons = validatorFaucets.foldLeft(
        Map[String, Seq[ValidatorFaucetCoupon.ContractId]]()
      ) { (m, r) =>
        m +
          (r.payload.validator -> (Seq(r.contractId) ++ m.getOrElse(
            r.payload.validator,
            Seq[ValidatorFaucetCoupon.ContractId](),
          )))
      }
    } yield validatorToCoupons.toSeq.map { case (_, coupons) => coupons }
  }

  override protected def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[ClosedMiningRound.ContractId, ClosedMiningRound]]] = for {
    domain <- getSvcRules().map(_.domain)
    rounds <- multiDomainAcsStore.listContractsOnDomain(
      cc.round.ClosedMiningRound.COMPANION,
      domain,
    )
  } yield rounds.minByOption(_.payload.round.number).map(AssignedContract(_, domain))

  override def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]] =
    multiDomainAcsStore
      .findContractWithOffset(cn.svcrules.Confirmation.COMPANION) {
        (co: Contract[?, cn.svcrules.Confirmation]) =>
          co.payload.confirmer == confirmer.toProtoPrimitive && co.payload.action.toValue == action.toValue
      }
      .map(_ map (_ map (_.contract)))

  override def lookupCnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]] =
    lookupCnsConfirmation(confirmer) { case a: CNSRARC_CollectInitialEntryPayment =>
      a.cnsEntryContext_CollectInitialEntryPaymentValue.paymentCid == paymentId
    }

  override def lookupCnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]] =
    lookupCnsConfirmation(confirmer) { case a: CNSRARC_RejectEntryInitialPayment =>
      a.cnsEntryContext_RejectEntryInitialPaymentValue.paymentCid == paymentId
    }

  override def lookupCnsInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]] =
    lookupCnsConfirmation(confirmer) {
      case a: CNSRARC_CollectInitialEntryPayment =>
        a.cnsEntryContext_CollectInitialEntryPaymentValue.paymentCid == paymentId
      case a: CNSRARC_RejectEntryInitialPayment =>
        a.cnsEntryContext_RejectEntryInitialPaymentValue.paymentCid == paymentId
    }

  override def listInitialPaymentConfirmationByCnsName(
      confirmer: PartyId,
      name: String,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]
  ] = {
    for {
      cnsContextCids <- listCnsEntryContextByCnsName(name).map(_.map(_.contractId).toSet)
      result <- multiDomainAcsStore.filterContracts(
        cn.svcrules.Confirmation.COMPANION,
        confirmingCnsEntry(confirmer) { arcCnsEntryContext =>
          cnsContextCids.contains(arcCnsEntryContext.cnsEntryContextCid)
        },
        limit,
      )
    } yield result map (_.contract)
  }

  override def lookupSvOnboardingRequestByTokenWithOffset(token: String)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]]] =
    for {
      cws <- multiDomainAcsStore.findContractWithOffset(so.SvOnboardingRequest.COMPANION) {
        (co: Contract[?, so.SvOnboardingRequest]) => co.payload.token == token
      }
    } yield cws map (_ map (_.contract))

  override def listSvOnboardingRequestsBySvcMembers(
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]] = for {
    svOnboardings <- multiDomainAcsStore.filterContracts(
      so.SvOnboardingRequest.COMPANION,
      (co: Contract[?, so.SvOnboardingRequest]) =>
        svcRules.payload.members.asScala
          .get(co.payload.candidateParty)
          .exists(_.name == co.payload.candidateName),
      limit,
    )
  } yield svOnboardings map (_.contract)

  override def listMemberTrafficContracts(memberId: Member, domainId: DomainId, limit: Limit)(
      implicit tc: TraceContext
  ): Future[Seq[Contract[MemberTraffic.ContractId, MemberTraffic]]] = for {
    memberTraffics <- multiDomainAcsStore.filterContractsOnDomain(
      cc.globaldomain.MemberTraffic.COMPANION,
      domainId,
      (co: Contract[cc.globaldomain.MemberTraffic.ContractId, cc.globaldomain.MemberTraffic]) =>
        co.payload.memberId == memberId.toProtoPrimitive,
      limit,
    )
  } yield memberTraffics

  override def listMemberCoinPriceVotes(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] = for {
    svcRules <- getSvcRules()
    votes <- listAllCoinPriceVotes(limit)
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
    multiDomainAcsStore
      .findContractWithOffset(so.SvOnboardingRequest.COMPANION) {
        (co: Contract[?, so.SvOnboardingRequest]) =>
          co.payload.candidateParty == candidateParty.toProtoPrimitive
      }
      .map(_ map (_ map (_.contract)))

  override def lookupValidatorLicenseWithOffset(validator: PartyId)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[ValidatorLicense.ContractId, ValidatorLicense]]]] = for {
    cws <- multiDomainAcsStore.findContractWithOffset(ValidatorLicense.COMPANION) {
      (co: Contract[?, ValidatorLicense]) => co.payload.validator == validator.toProtoPrimitive
    }
  } yield cws map (_ map (_.contract))

  override def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long] = {
    multiDomainAcsStore
      .listContractsOnDomain(MemberTraffic.COMPANION, domainId)
      .map(
        _.filter(_.payload.memberId == memberId.toProtoPrimitive)
          .map(_.payload.totalPurchased.toLong)
          .sum
      )
  }

  override def listVotesByVoteRequests(
      voteRequestCids: Seq[VoteRequest.ContractId],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[Vote.ContractId, Vote]]] = {
    val cidSet = voteRequestCids.toSet
    multiDomainAcsStore
      .filterContracts(
        cn.svcrules.Vote.COMPANION,
        { (co: Contract[?, Vote]) => cidSet.contains(co.payload.requestCid) },
        limit,
      )
      .map(_ map (_.contract))
  }

  override def lookupVoteByThisSvAndVoteRequestWithOffset(voteRequestCid: VoteRequest.ContractId)(
      implicit tc: TraceContext
  ): Future[QueryResult[Option[Contract[Vote.ContractId, Vote]]]] =
    multiDomainAcsStore
      .findContractWithOffset(cn.svcrules.Vote.COMPANION) { (co: Contract[?, Vote]) =>
        co.payload.requestCid == voteRequestCid && co.payload.voter == key.svParty.toProtoPrimitive
      }
      .map(_ map (_ map (_.contract)))

  override def lookupVoteRequestByThisSvAndActionWithOffset(
      action: ActionRequiringConfirmation
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[VoteRequest.ContractId, VoteRequest]]]
  ] = for {
    ct <- multiDomainAcsStore.findContractWithOffset(cn.svcrules.VoteRequest.COMPANION) {
      (co: Contract[?, cn.svcrules.VoteRequest]) =>
        co.payload.requester == key.svParty.toProtoPrimitive && co.payload.action.toValue == action.toValue
    }
  } yield ct map (_ map (_.contract))

  /** List the votes that are eligible to determine the outcome of a vote request;
    * - the vote must refer to that request
    * - the vote must be cast by one of the given members
    * - there must not be any votes cast by the same member
    */
  override def listEligibleVotes(
      voteRequestId: VoteRequest.ContractId,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[Vote.ContractId, Vote]]] = for {
    votes <- multiDomainAcsStore.filterContracts(
      cn.svcrules.Vote.COMPANION,
      (c: Contract[?, Vote]) => c.payload.requestCid == voteRequestId,
      limit,
    )
  } yield votes.distinctBy(_.payload.voter).map(_.contract)

  override def lookupCoinPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[CoinPriceVote.ContractId, CoinPriceVote]]] =
    multiDomainAcsStore
      .filterContracts(
        cp.CoinPriceVote.COMPANION,
        { (co: Contract[?, cp.CoinPriceVote]) => co.payload.sv == key.svParty.toProtoPrimitive },
      )
      .map(_.map(_.contract).headOption)

  override protected def lookupSvOnboardingRequestByCandidateNameWithOffset(candidateName: String)(
      implicit tc: TraceContext
  ): Future[QueryResult[Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]]] =
    multiDomainAcsStore
      .findContractWithOffset(so.SvOnboardingRequest.COMPANION) {
        (co: Contract[?, so.SvOnboardingRequest]) => co.payload.candidateName == candidateName
      }
      .map(_ map (_ map (_.contract)))

  override def listExpiredCnsSubscriptions(
      now: CantonTimestamp,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[SvSvcStore.IdleCnsSubscription]] = for {
    dueSubscriptions <- multiDomainAcsStore.filterContracts(
      SubscriptionIdleState.COMPANION,
      filter = { (e: Contract[?, SubscriptionIdleState]) =>
        now.toInstant.isAfter(e.payload.nextPaymentDueAt)
      },
    )
    // Join with the CnsEntryContexts
    subscriptionsWithContext <- dueSubscriptions.toList
      .traverse { subscription =>
        lookupCnsEntryContext(subscription.payload.reference).map(context =>
          (subscription, context)
        )
      }
    // Only deliver the ones referencing an active cns entry context
    result = applyLimit(
      "listExpiredCnsSubscriptions",
      limit,
      subscriptionsWithContext
        .sortBy(_._1.payload.nextPaymentDueAt)
        .iterator
        .collect { case (subscription, Some(context)) =>
          SvSvcStore.IdleCnsSubscription(subscription.contract, context.contract)
        }
        .toSeq,
    )
  } yield result

  override def listSvOnboardingConfirmed(limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]] = {
    multiDomainAcsStore
      .listContracts(so.SvOnboardingConfirmed.COMPANION, limit)
      .map(_.map(_.contract))
  }

  override def lookupSvOnboardingConfirmedByParty(svParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]] =
    multiDomainAcsStore
      .findContractWithOffset(so.SvOnboardingConfirmed.COMPANION)(
        (co: Contract[?, SvOnboardingConfirmed]) => co.payload.svParty == svParty.toProtoPrimitive
      )
      .map(_.value map (_.contract))

  override def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]]
  ] =
    multiDomainAcsStore
      .findContractWithOffset(so.SvOnboardingConfirmed.COMPANION) {
        (_: Contract[?, so.SvOnboardingConfirmed]).payload.svName == svName
      }
      .map(_ map (_ map (_.contract)))

  override def listElectionRequests(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ElectionRequest.ContractId, ElectionRequest]]] =
    multiDomainAcsStore
      .filterContractsOnDomain(
        ElectionRequest.COMPANION,
        svcRules.domain,
        { (c: Contract[?, ElectionRequest]) =>
          svcRules.payload.members.keySet
            .contains(c.payload.requester) && c.payload.epoch == svcRules.payload.epoch
        },
        limit,
      )
      .map(
        _.foldLeft(
          Map[String, Contract[
            ElectionRequest.ContractId,
            ElectionRequest,
          ]]()
        ) { (acc, contract) =>
          if (acc.contains(contract.payload.requester)) acc
          else acc + (contract.payload.requester -> contract)
        }
      )
      .map(dict => dict.values.toSeq)

  override def lookupElectionRequestByRequesterWithOffset(requester: PartyId, epoch: Long)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[ElectionRequest.ContractId, ElectionRequest]]]] = for {
    req <- multiDomainAcsStore.findContractWithOffset(ElectionRequest.COMPANION)(
      { (co: Contract[?, ElectionRequest]) =>
        co.payload.epoch == epoch && co.payload.requester == requester.toProtoPrimitive
      }
    )
  } yield req map (_ map (_.contract))

  override def listExpiredElectionRequests(
      epoch: Long,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[
    ElectionRequest.ContractId,
    ElectionRequest,
  ]]] =
    for {
      contracts <- multiDomainAcsStore.filterContracts(
        ElectionRequest.COMPANION,
        { (co: Contract[?, ElectionRequest]) =>
          co.payload.epoch < epoch
        },
        limit,
      )
    } yield contracts map (_.contract)

  override def getImportShipmentFor(
      receiver: PartyId
  )(implicit tc: TraceContext): Future[AcsStoreDump.ImportShipment] = for {
    openRound <- this.getLatestActiveOpenMiningRound()
    // Listing all crates is OK, as we assume the number of crates is small.
    allCrates <- multiDomainAcsStore.listContracts(cc.coinimport.ImportCrate.COMPANION)
    cratesForReceiver = allCrates.filter(crate =>
      crate.payload.receiver == receiver.toProtoPrimitive
    )
  } yield AcsStoreDump.ImportShipment(
    openRound,
    cratesForReceiver,
  )

  override def lookupCnsEntryByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[AssignedContract[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]]
  ] = multiDomainAcsStore
    .findContractWithOffset(cn.cns.CnsEntry.COMPANION)(
      (c: Contract[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]) => c.payload.name == name
    )
    .map(_.map(_.flatMap(_.toAssignedContract)))

  private def listCnsEntryContextByCnsName(
      name: String
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.cns.CnsEntryContext.ContractId, cn.cns.CnsEntryContext]]
  ] = for {
    contexts <- multiDomainAcsStore.filterContracts(
      cn.cns.CnsEntryContext.COMPANION,
      { (co: Contract[cn.cns.CnsEntryContext.ContractId, cn.cns.CnsEntryContext]) =>
        co.payload.name == name
      },
    )
  } yield contexts.map(_.contract)

  override def lookupSubscriptionInitialPaymentWithOffset(
      paymentCid: SubscriptionInitialPayment.ContractId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      AssignedContract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment]
    ]]
  ] = for {
    result <- multiDomainAcsStore
      .findContractWithOffset(SubscriptionInitialPayment.COMPANION)(
        (co: Contract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment]) =>
          co.contractId == paymentCid
      )
  } yield result map (_ flatMap (_.toAssignedContract))

  override def lookupFeaturedAppRightWithOffset(
      providerPartyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[AssignedContract[FeaturedAppRight.ContractId, FeaturedAppRight]]
    ]
  ] = for {
    result <- multiDomainAcsStore
      .findContractWithOffset(FeaturedAppRight.COMPANION)(
        (co: Contract[FeaturedAppRight.ContractId, FeaturedAppRight]) =>
          co.payload.provider == providerPartyId.toProtoPrimitive
      )
  } yield result map (_ flatMap (_.toAssignedContract))

  private[this] def lookupCnsConfirmation(
      confirmer: PartyId
  )(
      actionPredicate: CnsEntryContext_ActionRequiringConfirmation PartialFunction Boolean
  ): Future[QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]] =
    for {
      result <- multiDomainAcsStore.findContractWithOffset(
        cn.svcrules.Confirmation.COMPANION
      )(confirmingCnsEntry(confirmer) { arcCnsEntryContext =>
        actionPredicate.applyOrElse(arcCnsEntryContext.cnsEntryContextAction, Function const false)
      })
    } yield result map (_ map (_.contract))

  override def lookupCnsEntryContext(reference: SubscriptionRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.cns.CnsEntryContext.ContractId, cn.cns.CnsEntryContext]]] =
    multiDomainAcsStore.findContract(cn.cns.CnsEntryContext.COMPANION)(c =>
      c.payload.reference == reference
    )
}

private[memory] object InMemorySvSvcStore {
  private def confirmingCnsEntry(
      confirmer: PartyId
  )(entryPredicate: ARC_CnsEntryContext => Boolean)(co: Contract[?, Confirmation]): Boolean =
    co.payload.confirmer == confirmer.toProtoPrimitive
      && (co.payload.action match {
        case arcCnsEntryContext: ARC_CnsEntryContext =>
          entryPredicate(arcCnsEntryContext)
        case _ => false
      })
}
