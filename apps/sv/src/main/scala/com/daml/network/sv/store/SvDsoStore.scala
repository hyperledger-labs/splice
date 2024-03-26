package com.daml.network.sv.store

import cats.implicits.toTraverseOps
import com.daml.ledger.javaapi.data as javab
import com.daml.lf.data.Time.Timestamp
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.automation.TransferFollowTrigger.Task as FollowTask
import com.daml.network.codegen.java.cc.amulet.UnclaimedReward
import com.daml.network.codegen.java.cc.amuletrules.{
  AppTransferContext,
  AmuletRules_MiningRound_Archive,
}
import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cc.validatorlicense as vl
import com.daml.network.codegen.java.cn.dso.amuletprice as cp
import com.daml.network.codegen.java.cn.dso.memberstate.MemberRewardState
import com.daml.network.codegen.java.cn.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_DsoRules,
}
import com.daml.network.codegen.java.cn.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.codegen.java.cn.dsorules.dsorules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.codegen.java.cn.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_ConfirmSvOnboarding,
  VoteRequest,
  VoteRequestResult,
}
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.codegen.java.cn.wallet.subscriptions as sub
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.{PackageIdResolver, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection.GetAmuletRulesDomain
import com.daml.network.store.*
import com.daml.network.store.MultiDomainAcsStore.{ConstrainedTemplate, QueryResult, TemplateFilter}
import com.daml.network.store.db.AcsJdbcTypes
import com.daml.network.sv.store.SvDsoStore.noActiveDsoRules
import com.daml.network.sv.store.db.{DbSvDsoStore, DsoTables}
import com.daml.network.sv.store.db.DsoTables.DsoAcsStoreRowData
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.daml.network.util.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, MediatorId, Member, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*

/* Store used by the SV app for filtering contracts visible to the DSO party. */
trait SvDsoStore extends CNNodeAppStore[TxLogEntry] with PackageIdResolver.HasAmuletRules {
  import SvDsoStore.{amuletRulesFollowers, dsoRulesFollowers}

  protected val outerLoggerFactory: NamedLoggerFactory
  protected def templateJsonDecoder: TemplateJsonDecoder

  override protected lazy val loggerFactory: NamedLoggerFactory =
    outerLoggerFactory.append("store", "dsoParty")

  override lazy val acsContractFilter =
    SvDsoStore.contractFilter(key.dsoParty, domainMigrationId)

  override lazy val txLogConfig = new TxLogStore.Config[TxLogEntry] {
    override val parser = new DsoTxLogParser(loggerFactory)
    override def entryToRow = DsoTables.DsoTxLogRowData.fromTxLogEntry
    override def encodeEntry = TxLogEntry.encode
    override def decodeEntry = TxLogEntry.decode
  }

  def key: SvStore.Key

  def domainMigrationId: Long

  def lookupDsoRulesWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[
      AssignedContract[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules]
    ]]
  ] = multiDomainAcsStore
    .findAnyContractWithOffset(cn.dsorules.DsoRules.COMPANION)
    .map(_.map(_.flatMap(_.toAssignedContract)))

  def listVoteRequestResults(
      actionName: Option[String],
      executed: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[VoteRequestResult]]

  def lookupDsoRules()(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules]]] =
    lookupDsoRulesWithOffset().map(_.value)

  def getDsoRulesWithOffset()(implicit tc: TraceContext): Future[QueryResult[
    AssignedContract[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules]
  ]] = lookupDsoRulesWithOffset().map(_.sequence getOrElse (throw noActiveDsoRules))

  def getDsoRules()(implicit
      tc: TraceContext
  ): Future[AssignedContract[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules]] =
    lookupDsoRules().map(_.getOrElse(throw noActiveDsoRules))

  def getDsoRulesWithMemberNodeStates()(implicit
      tc: TraceContext
  ): Future[SvDsoStore.DsoRulesWithMemberNodeStates] = {
    for {
      // Note: at a certain size of the DSO, we'll be better off doing this join in the DB. We'll find out from our logs and performance tests.
      dsoRules <- getDsoRules()
      memberSvParties = dsoRules.payload.members.keySet().asScala.toSeq
      svNodeStates <- Future
        .traverse(memberSvParties) { svPartyStr =>
          val svParty = PartyId.tryFromProtoPrimitive(svPartyStr)
          getSvNodeState(svParty).map(co => svParty -> co.contract)
        }
        .map(_.toMap)
    } yield SvDsoStore.DsoRulesWithMemberNodeStates(dsoRules, svNodeStates)
  }

  def getDsoRulesWithSvNodeState(svParty: PartyId)(implicit
      tc: TraceContext
  ): Future[SvDsoStore.DsoRulesWithSvNodeState] = {
    for {
      dsoRules <- getDsoRules()
      svNodeState <- getSvNodeState(svParty)
    } yield SvDsoStore.DsoRulesWithSvNodeState(dsoRules, svParty, svNodeState.contract)
  }

  def lookupSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[
    AssignedContract[cn.dso.memberstate.SvNodeState.ContractId, cn.dso.memberstate.SvNodeState]
  ]]

  def getSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[
    AssignedContract[cn.dso.memberstate.SvNodeState.ContractId, cn.dso.memberstate.SvNodeState]
  ] =
    lookupSvNodeState(svPartyId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"No SvNodeState found for $svPartyId")
          .asRuntimeException()
      )
    )

  def lookupSvStatusReport(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[
    AssignedContract[
      cn.dso.memberstate.SvStatusReport.ContractId,
      cn.dso.memberstate.SvStatusReport,
    ]
  ]]

  def getSvStatusReport(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[
    AssignedContract[
      cn.dso.memberstate.SvStatusReport.ContractId,
      cn.dso.memberstate.SvStatusReport,
    ]
  ] =
    lookupSvStatusReport(svPartyId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"No SvStatusReport found for $svPartyId")
          .asRuntimeException()
      )
    )

  def lookupMemberRewardState(svName: String)(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[MemberRewardState.ContractId, MemberRewardState]]]

  def lookupAmuletRulesWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[
      AssignedContract[cc.amuletrules.AmuletRules.ContractId, cc.amuletrules.AmuletRules]
    ]]
  ] = multiDomainAcsStore
    .findAnyContractWithOffset(cc.amuletrules.AmuletRules.COMPANION)
    .map(_.map(_.flatMap(_.toAssignedContract)))

  def lookupAmuletRules()(implicit
      tc: TraceContext
  ): Future[
    Option[AssignedContract[cc.amuletrules.AmuletRules.ContractId, cc.amuletrules.AmuletRules]]
  ] =
    lookupAmuletRulesWithOffset().map(_.value)

  def getAmuletRules()(implicit
      tc: TraceContext
  ): Future[Contract[cc.amuletrules.AmuletRules.ContractId, cc.amuletrules.AmuletRules]] =
    lookupAmuletRules().map(
      _.map(_.contract).getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active AmuletRules contract")
          .asRuntimeException()
      )
    )

  def getAmuletRulesDomain: GetAmuletRulesDomain = { () => implicit tc =>
    lookupAmuletRules().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active AmuletRules contract")
          .asRuntimeException()
      ).domain
    )
  }

  def lookupCnsRulesWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[AssignedContract[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]]]
  ] = {
    for {
      result <- multiDomainAcsStore
        .findAnyContractWithOffset(cn.cns.CnsRules.COMPANION)
    } yield result.map(_.flatMap(_.toAssignedContract))
  }

  def lookupCnsRules()(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]]] =
    lookupCnsRulesWithOffset().map(_.value)

  def getCnsRules()(implicit
      tc: TraceContext
  ): Future[Contract[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]] =
    lookupCnsRules().map(
      _.map(_.contract).getOrElse(
        throw Status.NOT_FOUND.withDescription("No active CnsRules contract").asRuntimeException()
      )
    )

  /** Lookup the triple of open mining rounds that should always be present
    * after bootstrapping.
    */
  final def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[SvDsoStore.OpenMiningRoundTriple]] =
    for {
      openMiningRounds <- multiDomainAcsStore.listAssignedContracts(
        cc.round.OpenMiningRound.COMPANION
      )
    } yield for {
      newestOverallRound <- openMiningRounds.maxByOption(_.payload.round.number)
      // all rounds are signed by dso; pick the domain with the highest round#
      domain = newestOverallRound.domain
      Seq(oldest, middle, newest) <- Some(
        openMiningRounds
          .filter(_.domain == domain)
          .sortBy(_.payload.round.number)
      )
      if oldest.payload.round.number + 1 == middle.payload.round.number &&
        newest.payload.round.number - 1 == middle.payload.round.number
    } yield SvDsoStore.OpenMiningRoundTriple(
      oldest = oldest.contract,
      middle = middle.contract,
      newest = newest.contract,
      domain = domain,
    )

  /** Get the triple of open mining rounds that should always be present after boostrapping. */
  final def getOpenMiningRoundTriple()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[SvDsoStore.OpenMiningRoundTriple] =
    lookupOpenMiningRoundTriple().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No triple of OpenMiningRound contracts")
          .asRuntimeException()
      )
    )

  final def lookupLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[SvDsoStore.OpenMiningRound[AssignedContract]]] =
    lookupOpenMiningRoundTriple().map(_.map { triple =>
      AssignedContract(triple.newest, triple.domain)
    })

  /** get the latest active open mining round contract, which should always be present after bootstrapping. */
  def getLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[SvDsoStore.OpenMiningRound[AssignedContract]] = lookupLatestActiveOpenMiningRound().map(
    _.getOrElse(
      throw Status.NOT_FOUND
        .withDescription("No active OpenMiningRound contract")
        .asRuntimeException()
    )
  )

  /** List amulets that are expired and can never be used as transfer input. */
  final def listExpiredAmulets
      : ListExpiredContracts[cc.amulet.Amulet.ContractId, cc.amulet.Amulet] =
    listExpiredRoundBased(cc.amulet.Amulet.COMPANION)(identity)

  /** List locked amulets that are expired and can never be used as transfer input. */
  final def listLockedExpiredAmulets
      : ListExpiredContracts[cc.amulet.LockedAmulet.ContractId, cc.amulet.LockedAmulet] =
    listExpiredRoundBased(cc.amulet.LockedAmulet.COMPANION)(_.amulet)

  def listExpiredVoteRequests(): ListExpiredContracts[VoteRequest.ContractId, VoteRequest] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(VoteRequest.COMPANION)(_.voteBefore)

  def listConfirmations(
      action: cn.dsorules.ActionRequiringConfirmation,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation]]]

  def listAppRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cc.amulet.AppRewardCoupon.ContractId, cc.amulet.AppRewardCoupon]]]

  def sumAppRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit
      tc: TraceContext
  ): Future[AppRewardCouponsSum]

  def listAppRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Seq[cc.amulet.AppRewardCoupon.ContractId]]]

  def listValidatorRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cc.amulet.ValidatorRewardCoupon.ContractId, cc.amulet.ValidatorRewardCoupon]]
  ]

  def sumValidatorRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[BigDecimal]

  def listValidatorRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Seq[cc.amulet.ValidatorRewardCoupon.ContractId]]]

  def listValidatorFaucetCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[
      cc.validatorlicense.ValidatorFaucetCoupon.ContractId,
      cc.validatorlicense.ValidatorFaucetCoupon,
    ]]
  ]

  def countValidatorFaucetCouponsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[Long]

  def listValidatorFaucetCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Seq[cc.validatorlicense.ValidatorFaucetCoupon.ContractId]]]

  def listSvRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[
      cc.amulet.SvRewardCoupon.ContractId,
      cc.amulet.SvRewardCoupon,
    ]]
  ]

  def sumSvRewardCouponWeightsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[Long]

  def listSvRewardCouponsGroupedByCounterparty(
      roundNumber: Long,
      roundDomain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Seq[cc.amulet.SvRewardCoupon.ContractId]]]

  protected[this] def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[
    Option[AssignedContract[
      cc.round.ClosedMiningRound.ContractId,
      cc.round.ClosedMiningRound,
    ]]
  ]

  final def getExpiredRewardsForOldestClosedMiningRound(
      totalCouponsLimit: Limit = PageLimit.tryCreate(100)
  )(implicit
      tc: TraceContext
  ): Future[Seq[ExpiredRewardCouponsBatch]] = {
    // the below restrict by domain because a batch can be operated on only if
    // they share a domain with dsorules and the round
    lookupOldestClosedMiningRound()
      .flatMap {
        case Some(closedRound) =>
          for {
            appRewardGroups <- listAppRewardCouponsGroupedByCounterparty(
              closedRound.payload.round.number,
              closedRound.domain,
              totalCouponsLimit = totalCouponsLimit,
            )
            validatorRewardGroups <- listValidatorRewardCouponsGroupedByCounterparty(
              closedRound.payload.round.number,
              closedRound.domain,
              totalCouponsLimit = totalCouponsLimit,
            )
            validatorFaucetGroups <- listValidatorFaucetCouponsGroupedByCounterparty(
              closedRound.payload.round.number,
              closedRound.domain,
              totalCouponsLimit = totalCouponsLimit,
            )
            svRewardCouponGroups <- listSvRewardCouponsGroupedByCounterparty(
              closedRound.payload.round.number,
              closedRound.domain,
              totalCouponsLimit = totalCouponsLimit,
            )
          } yield appRewardGroups.map(group =>
            ExpiredRewardCouponsBatch(
              closedRoundCid = closedRound.contractId,
              closedRoundNumber = closedRound.contract.payload.round.number,
              validatorCoupons = Seq.empty,
              appCoupons = group,
              svRewardCoupons = Seq.empty,
              validatorFaucets = Seq.empty,
            )
          ) ++
            validatorRewardGroups.map(group =>
              ExpiredRewardCouponsBatch(
                closedRoundCid = closedRound.contractId,
                closedRoundNumber = closedRound.contract.payload.round.number,
                validatorCoupons = group,
                appCoupons = Seq.empty,
                svRewardCoupons = Seq.empty,
                validatorFaucets = Seq.empty,
              )
            ) ++ validatorFaucetGroups.map(group =>
              ExpiredRewardCouponsBatch(
                closedRoundCid = closedRound.contractId,
                closedRoundNumber = closedRound.contract.payload.round.number,
                validatorCoupons = Seq.empty,
                appCoupons = Seq.empty,
                svRewardCoupons = Seq.empty,
                validatorFaucets = group,
              )
            ) ++ svRewardCouponGroups.map(group =>
              ExpiredRewardCouponsBatch(
                closedRoundCid = closedRound.contractId,
                closedRoundNumber = closedRound.contract.payload.round.number,
                validatorCoupons = Seq.empty,
                appCoupons = Seq.empty,
                svRewardCoupons = group,
                validatorFaucets = Seq.empty,
              )
            )
        case None => Future(Seq())
      }
  }

  /** All `ClosedMiningRound` contracts that should be confirmed to be archived.
    *
    * These are all `ClosedMiningRound` contracts for which
    * 1. there are no left-over reward coupon contracts and
    * 2. there does not yet exist a ready-to-be-archived confirmation by this SV.
    *
    * Note: The QueryResult in the return value is composed of the closed mining round contract
    * and the offset from the query for the confirmation contract.
    */
  final def listArchivableClosedMiningRounds(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[
    Seq[QueryResult[
      AssignedContract[
        cc.round.ClosedMiningRound.ContractId,
        cc.round.ClosedMiningRound,
      ]
    ]]
  ] = {
    for {
      domain <- getDsoRules().map(_.domain)
      // we limit to the DsoRules domain because this is used by a polling trigger,
      // which exercises on DsoRules, so all operands must share its domain.
      // There's no harm "missing" closed rounds awaiting reassignment, because
      // they'll be seen on the next poll
      closedRounds <- multiDomainAcsStore.listContractsOnDomain(
        cc.round.ClosedMiningRound.COMPANION,
        domain,
        limit,
      )
      archivableClosedRounds <- closedRounds.traverse(round => {
        for {
          appRewardCoupons <- listAppRewardCouponsOnDomain(
            round.payload.round.number,
            domain,
            PageLimit.tryCreate(1),
          )
          validatorRewardCoupons <- listValidatorRewardCouponsOnDomain(
            round.payload.round.number,
            domain,
            PageLimit.tryCreate(1),
          )
          validatorFaucetCoupons <- listValidatorFaucetCouponsOnDomain(
            round.payload.round.number,
            domain,
            PageLimit.tryCreate(1),
          )
          svRewardCoupons <- listSvRewardCouponsOnDomain(
            round.payload.round.number,
            domain,
            PageLimit.tryCreate(1),
          )
          action = new ARC_AmuletRules(
            new CRARC_MiningRound_Archive(
              new AmuletRules_MiningRound_Archive(
                round.contractId
              )
            )
          )
          confirmationQueryResult <- lookupConfirmationByActionWithOffset(key.svParty, action)
        } yield {
          (
            // archivable if ...
            if (
              // ... there are no unclaimed rewards left in this round
              appRewardCoupons.isEmpty && validatorRewardCoupons.isEmpty && validatorFaucetCoupons.isEmpty && svRewardCoupons.isEmpty &&
              // ... and a confirmation to archive is not already created by this SV
              confirmationQueryResult.value.isEmpty
            ) Some(QueryResult(confirmationQueryResult.offset, AssignedContract(round, domain)))
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
      Contract[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation]
    ]]
  ]

  def lookupCnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: sub.SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[Contract[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation]]]
  ]

  def lookupCnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: sub.SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[Contract[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation]]]
  ]

  def lookupCnsInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: sub.SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[Contract[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation]]]
  ]

  def lookupSvOnboardingRequestByTokenWithOffset(
      token: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ]

  final def lookupSvOnboardingRequestByCandidateParty(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]
  ] = lookupSvOnboardingRequestByCandidatePartyWithOffset(candidateParty).map(_.value)

  final def lookupSvOnboardingRequestByCandidateName(
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

  def listExpiredCnsEntries: ListExpiredContracts[
    cn.cns.CnsEntry.ContractId,
    cn.cns.CnsEntry,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(cn.cns.CnsEntry.COMPANION)(
      _.expiresAt
    )

  def listExpiredCnsSubscriptions(
      now: CantonTimestamp,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[SvDsoStore.IdleCnsSubscription]]

  def listSvOnboardingConfirmed(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[
    Seq[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ]

  def lookupSvOnboardingConfirmedByParty(
      svParty: PartyId
  )(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ]

  def lookupSvOnboardingConfirmedByName(
      svName: String
  )(implicit tc: TraceContext): Future[
    Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]
  ] =
    lookupSvOnboardingConfirmedByNameWithOffset(svName).map(_.value)

  def listSvOnboardingConfirmations(
      svOnboarding: Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest],
      weight: Long,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation]]] = {
    val expectedAction = new ARC_DsoRules(
      new SRARC_ConfirmSvOnboarding(
        new DsoRules_ConfirmSvOnboarding(
          svOnboarding.payload.candidateParty,
          svOnboarding.payload.candidateName,
          svOnboarding.payload.candidateParticipantId,
          weight,
          svOnboarding.payload.token,
        )
      )
    )
    listConfirmations(expectedAction, limit)
  }

  def listSvOnboardingRequestsByDsoMembers(
      dsoRules: Contract.Has[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]

  protected def listExpiredRoundBased[Id <: javab.codegen.ContractId[T], T <: javab.Template](
      companion: TemplateCompanion[Id, T]
  )(amulet: T => cc.amulet.Amulet): ListExpiredContracts[Id, T]

  final def listUnclaimedRewards(
      limit: Limit
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[UnclaimedReward.ContractId, cc.amulet.UnclaimedReward]]] =
    for {
      unclaimedRewards <- multiDomainAcsStore.listContracts(
        cc.amulet.UnclaimedReward.COMPANION,
        limit = limit,
      )
    } yield unclaimedRewards map (_.contract)

  def listMemberTrafficContracts(memberId: Member, domainId: DomainId, limit: Limit)(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[cc.globaldomain.MemberTraffic.ContractId, cc.globaldomain.MemberTraffic]]
  ]

  /** List issuing mining rounds past their targetClosesAt */
  def listExpiredIssuingMiningRounds: ListExpiredContracts[
    cc.round.IssuingMiningRound.ContractId,
    cc.round.IssuingMiningRound,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(cc.round.IssuingMiningRound.COMPANION)(
      _.targetClosesAt
    )

  /** List stale confirmations past their expiresAt */
  def listStaleConfirmations
      : ListExpiredContracts[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(cn.dsorules.Confirmation.COMPANION)(
      _.expiresAt
    )

  /** List all the current amulet price votes. */
  final def listAllAmuletPriceVotes(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.dso.amuletprice.AmuletPriceVote.ContractId, cn.dso.amuletprice.AmuletPriceVote]]
  ] =
    for {
      votes <- multiDomainAcsStore.listContracts(
        cn.dso.amuletprice.AmuletPriceVote.COMPANION,
        limit,
      )
    } yield votes map (_.contract)

  /** List the current amulet price votes by the DSO members. */
  def listMemberAmuletPriceVotes(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[cn.dso.amuletprice.AmuletPriceVote.ContractId, cn.dso.amuletprice.AmuletPriceVote]]
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
  def listValidatorLicenses(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[vl.ValidatorLicense.ContractId, vl.ValidatorLicense]]] =
    multiDomainAcsStore
      .listContracts(vl.ValidatorLicense.COMPANION, limit)
      .map(_ map (_.contract))

  def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long]

  def listAmuletPriceVotes(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[cn.dso.amuletprice.AmuletPriceVote.ContractId, cn.dso.amuletprice.AmuletPriceVote]]
  ] =
    multiDomainAcsStore
      .listContracts(cn.dso.amuletprice.AmuletPriceVote.COMPANION, limit)
      .map(_ map (_.contract))

  def listVoteRequests(limit: Limit = Limit.DefaultLimit)(implicit tc: TraceContext): Future[
    Seq[Contract[cn.dsorules.VoteRequest.ContractId, cn.dsorules.VoteRequest]]
  ] =
    multiDomainAcsStore
      .listContracts(cn.dsorules.VoteRequest.COMPANION, limit)
      .map(_ map (_.contract))

  def lookupVoteByThisSvAndVoteRequestWithOffset(
      voteRequestCid: cn.dsorules.VoteRequest.ContractId
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[cn.dsorules.Vote]]]

  def lookupVoteRequest(contractId: cn.dsorules.VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[cn.dsorules.VoteRequest.ContractId, cn.dsorules.VoteRequest]]]

  def getVoteRequest(contractId: cn.dsorules.VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Contract[cn.dsorules.VoteRequest.ContractId, cn.dsorules.VoteRequest]] =
    lookupVoteRequest(contractId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"Vote request not found for tracking-id $contractId")
          .asRuntimeException()
      )
    )

  def listVoteRequestsByTrackingCid(
      voteRequestCids: Seq[cn.dsorules.VoteRequest.ContractId],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[VoteRequest.ContractId, VoteRequest]]
  ]

  def lookupVoteRequestByThisSvAndActionWithOffset(action: ActionRequiringConfirmation)(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[Contract[VoteRequest.ContractId, VoteRequest]]]
  ]

  def lookupAmuletPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[cp.AmuletPriceVote.ContractId, cp.AmuletPriceVote]]]

  protected def lookupSvOnboardingRequestByCandidateNameWithOffset(
      candidateName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ]

  def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed]]]
  ]

  def listElectionRequests(
      dsoRules: AssignedContract[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.dsorules.ElectionRequest.ContractId, cn.dsorules.ElectionRequest]]
  ]

  def lookupElectionRequestByRequesterWithOffset(
      requester: PartyId,
      epoch: Long,
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[cn.dsorules.ElectionRequest.ContractId, cn.dsorules.ElectionRequest]
    ]]
  ]

  def listExpiredElectionRequests(
      epoch: Long,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[
    cn.dsorules.ElectionRequest.ContractId,
    cn.dsorules.ElectionRequest,
  ]]]

  private[this] def listLaggingDsoRulesFollowers(
      targetDomain: DomainId
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[?, ?]]] = for {
    amuletRulesO <- lookupAmuletRules()
    otherContracts <- multiDomainAcsStore.listAssignedContractsNotOnDomainN(
      targetDomain,
      dsoRulesFollowers,
    )
  } yield otherContracts ++ amuletRulesO
    .filterNot(_.domain == targetDomain)
    .toList

  final def listDsoRulesTransferFollowers()(implicit
      tc: TraceContext
  ): Future[Seq[FollowTask[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules, _, _]]] = {
    lookupDsoRules().flatMap(_.map { dsoRules =>
      listLaggingDsoRulesFollowers(dsoRules.domain)
        .map(_ map (FollowTask(dsoRules, _)))
    }.getOrElse(Future successful Seq.empty))
  }

  def listAmuletRulesTransferFollowers()(implicit
      tc: TraceContext
  ): Future[
    Seq[FollowTask[cc.amuletrules.AmuletRules.ContractId, cc.amuletrules.AmuletRules, ?, ?]]
  ] = {
    lookupAmuletRules().flatMap(_.map { amuletRules =>
      multiDomainAcsStore
        .listAssignedContractsNotOnDomainN(
          amuletRules.domain,
          amuletRulesFollowers,
        )
        .map(_.map(FollowTask(amuletRules, _)).toSeq)
    }.getOrElse(Future successful Seq.empty))
  }

  def lookupCnsEntryByNameWithOffset(name: String)(implicit tc: TraceContext): Future[
    QueryResult[Option[AssignedContract[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]]
  ]

  def lookupCnsEntryByName(
      name: String
  )(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]] =
    lookupCnsEntryByNameWithOffset(name).map(_.value)

  final def lookupCnsEntryContext(contractId: cn.cns.CnsEntryContext.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[cn.cns.CnsEntryContext.ContractId, cn.cns.CnsEntryContext]]] = for {
    cws <- multiDomainAcsStore
      .lookupContractById(cn.cns.CnsEntryContext.COMPANION)(contractId)
  } yield cws map (_.contract)

  def lookupSubscriptionInitialPaymentWithOffset(
      paymentCid: sub.SubscriptionInitialPayment.ContractId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      AssignedContract[sub.SubscriptionInitialPayment.ContractId, sub.SubscriptionInitialPayment]
    ]]
  ]

  def lookupSubscriptionInitialPayment(
      paymentCid: sub.SubscriptionInitialPayment.ContractId
  )(implicit tc: TraceContext): Future[Option[
    AssignedContract[sub.SubscriptionInitialPayment.ContractId, sub.SubscriptionInitialPayment]
  ]] = lookupSubscriptionInitialPaymentWithOffset(paymentCid).map(_.value)

  def listInitialPaymentConfirmationByCnsName(
      confirmer: PartyId,
      name: String,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[cn.dsorules.Confirmation.ContractId, cn.dsorules.Confirmation]]
  ]

  def lookupFeaturedAppRightWithOffset(
      providerPartyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[AssignedContract[cc.amulet.FeaturedAppRight.ContractId, cc.amulet.FeaturedAppRight]]
    ]
  ]

  def lookupFeaturedAppRight(
      providerPartyId: PartyId
  )(implicit
      tc: TraceContext
  ): Future[
    Option[AssignedContract[cc.amulet.FeaturedAppRight.ContractId, cc.amulet.FeaturedAppRight]]
  ] =
    lookupFeaturedAppRightWithOffset(providerPartyId).map(_.value)

  def getDsoTransferContextForRound(round: Round)(implicit
      tc: TraceContext
  ): Future[Option[AppTransferContext]] =
    getOpenMiningRoundTriple().map(_.toSeq).flatMap { openRounds =>
      openRounds.find(_.payload.round == round).traverse(getTransferContext)
    }

  def getDsoTransferContext()(implicit
      tc: TraceContext
  ): Future[AppTransferContext] =
    getLatestActiveOpenMiningRound().flatMap(getTransferContext)

  private def getTransferContext(
      openMiningRound: SvDsoStore.OpenMiningRound[Contract.Has]
  )(implicit tc: TraceContext): Future[AppTransferContext] = {
    for {
      featured <- lookupFeaturedAppRight(key.dsoParty)
      amuletRules <- getAmuletRules()
    } yield {
      new AppTransferContext(
        amuletRules.contractId,
        openMiningRound.contractId,
        featured.map(_.contractId).toJava,
      )
    }
  }

  def lookupCnsEntryContext(
      reference: sub.SubscriptionRequest.ContractId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    cn.cns.CnsEntryContext.ContractId,
    cn.cns.CnsEntryContext,
  ]]]
}

object SvDsoStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
      domainMigrationId: Long,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvDsoStore = {
    storage match {
      case db: DbStorage =>
        new DbSvDsoStore(
          key,
          db,
          loggerFactory,
          retryProvider,
          domainMigrationId,
          participantId,
        )
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  }

  private val dsoRulesFollowers: Seq[ConstrainedTemplate] = {
    import com.daml.network.codegen.java.cn.dsorules
    Seq[ConstrainedTemplate](
      // AmuletRules is specially handled so should *not* be listed here, even
      // though it follows DsoRules
      dsorules.VoteRequest.COMPANION,
      dsorules.Confirmation.COMPANION,
      dsorules.ElectionRequest.COMPANION,
      so.SvOnboardingRequest.COMPANION,
      so.SvOnboardingConfirmed.COMPANION,
      cn.dso.memberstate.SvStatusReport.COMPANION,
      cn.dso.memberstate.SvNodeState.COMPANION,
      cn.dso.memberstate.MemberRewardState.COMPANION,
    )
  }

  private[network] val amuletRulesFollowers: Seq[ConstrainedTemplate] = Seq[ConstrainedTemplate](
    cc.round.OpenMiningRound.COMPANION,
    cc.round.SummarizingMiningRound.COMPANION,
    cc.round.IssuingMiningRound.COMPANION,
    cc.round.ClosedMiningRound.COMPANION,
    cc.amulet.FeaturedAppRight.COMPANION,
    cc.amulet.UnclaimedReward.COMPANION,
    cc.validatorlicense.ValidatorLicense.COMPANION,
    cn.cns.CnsEntry.COMPANION,
    cn.cns.CnsEntryContext.COMPANION,
    cn.cns.CnsRules.COMPANION,
    cn.dso.amuletprice.AmuletPriceVote.COMPANION,
    cn.wallet.subscriptions.TerminatedSubscription.COMPANION, // TODO (#8782) move it to UserWalletStore.templatesMovedByMyAutomation
  )

  private[network] val templatesMovedByMyAutomation: Seq[ConstrainedTemplate] =
    (dsoRulesFollowers ++ amuletRulesFollowers) ++ Seq[ConstrainedTemplate](
      // AmuletRules and DsoRules are specially handled, so not listed in followers
      cn.dsorules.DsoRules.COMPANION,
      cc.amuletrules.AmuletRules.COMPANION,
    )

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(
      dsoParty: PartyId,
      domainMigrationId: Long,
  ): MultiDomainAcsStore.ContractFilter[DsoAcsStoreRowData] = {
    import MultiDomainAcsStore.mkFilter
    val dso = dsoParty.toProtoPrimitive

    val dsoFilters = Map[PackageQualifiedName, TemplateFilter[?, ?, DsoAcsStoreRowData]](
      mkFilter(cn.dso.amuletprice.AmuletPriceVote.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            voter = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
          )
      },
      mkFilter(cn.dsorules.Confirmation.COMPANION)(co => co.payload.dso == dso) { contract =>
        val (
          actionCnsEntryContextCid,
          actionCnsEntryContextPaymentId,
          actionCnsEntryContextArcType,
        ) =
          contract.payload.action match {
            case arcCnsEntryContext: cn.dsorules.actionrequiringconfirmation.ARC_CnsEntryContext =>
              arcCnsEntryContext.cnsEntryContextAction match {
                case action: cn.dsorules.cnsentrycontext_actionrequiringconfirmation.CNSRARC_CollectInitialEntryPayment =>
                  (
                    Some(arcCnsEntryContext.cnsEntryContextCid),
                    Some(action.cnsEntryContext_CollectInitialEntryPaymentValue.paymentCid),
                    Some("CNSRARC_CollectInitialEntryPayment"),
                  )
                case action: cn.dsorules.cnsentrycontext_actionrequiringconfirmation.CNSRARC_RejectEntryInitialPayment =>
                  (
                    Some(arcCnsEntryContext.cnsEntryContextCid),
                    Some(action.cnsEntryContext_RejectEntryInitialPaymentValue.paymentCid),
                    Some("CNSRARC_RejectEntryInitialPayment"),
                  )
                case _ =>
                  (None, None, None)
              }
            case _ => (None, None, None)
          }
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          actionRequiringConfirmation =
            Some(AcsJdbcTypes.payloadJsonFromDefinedDataType(contract.payload.action)),
          confirmer = Some(PartyId.tryFromProtoPrimitive(contract.payload.confirmer)),
          actionCnsEntryContextCid = actionCnsEntryContextCid,
          actionCnsEntryContextPaymentId = actionCnsEntryContextPaymentId,
          actionCnsEntryContextArcType = actionCnsEntryContextArcType,
        )
      },
      mkFilter(cn.dsorules.ElectionRequest.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          requester = Some(PartyId.tryFromProtoPrimitive(contract.payload.requester)),
          electionRequestEpoch = Some(contract.payload.epoch),
        )
      },
      mkFilter(cn.dsorules.VoteRequest.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.voteBefore)),
          actionRequiringConfirmation =
            Some(AcsJdbcTypes.payloadJsonFromDefinedDataType(contract.payload.action)),
          requesterName = Some(contract.payload.requester),
          voteRequestTrackingCid =
            Some(contract.payload.trackingCid.toScala.getOrElse(contract.contractId)),
        )
      },
      mkFilter(cn.dsorules.DsoRules.COMPANION)(co => co.payload.dso == dso)(
        DsoAcsStoreRowData(_)
      ),
      mkFilter(cn.dso.memberstate.SvStatusReport.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            svParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
          )
      },
      mkFilter(cn.dso.memberstate.SvNodeState.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          svParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
        )
      },
      mkFilter(cn.dso.memberstate.MemberRewardState.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            svName = Some(contract.payload.svName),
          )
      },
      mkFilter(so.SvOnboardingRequest.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          svOnboardingToken = Some(contract.payload.token),
          svCandidateParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.candidateParty)),
          svCandidateName = Some(contract.payload.candidateName),
        )
      },
      mkFilter(so.SvOnboardingConfirmed.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          svCandidateParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.svParty)),
          svCandidateName = Some(contract.payload.svName),
        )
      },
      mkFilter(cc.amuletrules.AmuletRules.COMPANION)(co => co.payload.dso == dso)(
        DsoAcsStoreRowData(_)
      ),
      mkFilter(cc.amulet.Amulet.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          amuletRoundOfExpiry = Some(CNNodeUtil.amuletExpiresAt(contract.payload).number),
        )
      },
      mkFilter(cc.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          featuredAppRightProvider = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
        )
      },
      mkFilter(cc.amulet.LockedAmulet.COMPANION)(co => co.payload.amulet.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          amuletRoundOfExpiry = Some(CNNodeUtil.amuletExpiresAt(contract.payload.amulet).number),
        )
      },
      mkFilter(cc.amulet.AppRewardCoupon.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          rewardRound = Some(contract.payload.round.number),
          rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
          rewardAmount = Some(contract.payload.amount),
          appRewardIsFeatured = Some(contract.payload.featured),
        )
      },
      mkFilter(cc.amulet.ValidatorRewardCoupon.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          rewardRound = Some(contract.payload.round.number),
          rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          rewardAmount = Some(contract.payload.amount),
        )
      },
      mkFilter(cc.validatorlicense.ValidatorFaucetCoupon.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            rewardRound = Some(contract.payload.round.number),
            rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
          )
      },
      mkFilter(cc.amulet.SvRewardCoupon.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          rewardRound = Some(contract.payload.round.number),
          rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
          rewardWeight = Some(contract.payload.weight),
        )
      },
      mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          miningRound = Some(contract.payload.round.number),
        )
      },
      mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
          miningRound = Some(contract.payload.round.number),
        )
      },
      mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          miningRound = Some(contract.payload.round.number),
        )
      },
      mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          miningRound = Some(contract.payload.round.number),
        )
      },
      mkFilter(cc.amulet.UnclaimedReward.COMPANION)(co => co.payload.dso == dso)(
        DsoAcsStoreRowData(_)
      ),
      mkFilter(vl.ValidatorLicense.COMPANION)(vl => vl.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          validator = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
        )
      },
      mkFilter(cc.globaldomain.MemberTraffic.COMPANION)(vt =>
        vt.payload.dso == dso && vt.payload.migrationId == domainMigrationId
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          memberTrafficMember = Member
            .fromProtoPrimitive_(contract.payload.memberId)
            .fold(
              // we ignore cases where the member id is invalid instead of throwing an exception
              // to avoid killing the entire ingestion pipeline as a result
              _ => None,
              Some(_),
            ),
          totalTrafficPurchased = Some(contract.payload.totalPurchased),
        )
      },
      mkFilter(cn.cns.CnsRules.COMPANION)(co => co.payload.dso == dso)(DsoAcsStoreRowData(_)),
      mkFilter(cn.cns.CnsEntry.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          cnsEntryName = Some(contract.payload.name),
        )
      },
      mkFilter(cn.cns.CnsEntryContext.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          cnsEntryName = Some(contract.payload.name),
          subscriptionReferenceContractId = Some(contract.payload.reference),
        )
      },
      mkFilter(sub.SubscriptionInitialPayment.COMPANION)(co =>
        co.payload.subscriptionData.dso == dso && co.payload.subscriptionData.provider == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          subscriptionReferenceContractId = Some(contract.payload.reference),
        )
      },
      mkFilter(sub.SubscriptionPayment.COMPANION)(co =>
        co.payload.subscriptionData.dso == dso && co.payload.subscriptionData.provider == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          subscriptionReferenceContractId = Some(contract.payload.reference),
        )
      },
      mkFilter(sub.SubscriptionIdleState.COMPANION)(co =>
        co.payload.subscriptionData.dso == dso && co.payload.subscriptionData.provider == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          subscriptionReferenceContractId = Some(contract.payload.reference),
          subscriptionNextPaymentDueAt =
            Some(Timestamp.assertFromInstant(contract.payload.nextPaymentDueAt)),
        )
      },
      // TODO (#8782) revisit if it makes sense
      mkFilter(sub.TerminatedSubscription.COMPANION)(co =>
        co.payload.subscriptionData.dso == dso && co.payload.subscriptionData.provider == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          subscriptionReferenceContractId = Some(contract.payload.reference),
        )
      },
    )

    MultiDomainAcsStore.SimpleContractFilter(
      dsoParty,
      dsoFilters,
    )
  }

  type OpenMiningRound[Ct[_, _]] =
    Ct[cc.round.OpenMiningRound.ContractId, cc.round.OpenMiningRound]
  type OpenMiningRoundContract =
    OpenMiningRound[Contract]

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
          // TODO(M3-07): when changing AmuletConfigs it will make sense to store tickDuration on the rounds and express targetClosesAt as 2 * tickDuration
          middle.payload.opensAt.plus(middleTickDuration),
          newest.payload.opensAt,
        ),
      )
    }

    def toSeq: Seq[OpenMiningRoundContract] = Seq(oldest, middle, newest)
  }

  case class IdleCnsSubscription(
      state: Contract[
        sub.SubscriptionIdleState.ContractId,
        sub.SubscriptionIdleState,
      ],
      context: Contract[
        cn.cns.CnsEntryContext.ContractId,
        cn.cns.CnsEntryContext,
      ],
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("state", _.state), param("context", _.context))
  }

  private def noActiveDsoRules =
    Status.NOT_FOUND.withDescription("No active DsoRules contract").asRuntimeException()

  case class DsoRulesWithMemberNodeStates(
      dsoRules: AssignedContract[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules],
      svNodeStates: Map[
        PartyId,
        Contract[cn.dso.memberstate.SvNodeState.ContractId, cn.dso.memberstate.SvNodeState],
      ],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("domainId", _.dsoRules.domain),
        param("dsoRulesCid", _.dsoRules.contractId),
        param("svNodeStates", _.svNodeStates),
      )

    def currentDomainNodeConfigs(): Seq[cn.dso.globaldomain.DomainNodeConfig] = {
      // TODO(#4906): make its callers work with soft-domain migration
      svNodeStates.values
        .flatMap(_.payload.state.domainNodes.asScala.get(dsoRules.domain.toProtoPrimitive))
        .toSeq
    }

    def activeSvParticipantAndMediatorIds(): Seq[Member] = {
      val svParticipants = dsoRules.contract.payload.members
        .values()
        .asScala
        .map(_.participantId)
        .toSeq
        .map(ParticipantId.tryFromProtoPrimitive)
      val offboardedSvParticipants = dsoRules.contract.payload.offboardedMembers
        .values()
        .asScala
        .map(_.participantId)
        .toSeq
        .map(ParticipantId.tryFromProtoPrimitive)
      val svMediators = svNodeStates.values
        .flatMap(_.payload.state.domainNodes.values().asScala)
        .flatMap(_.mediator.toScala)
        .map(m =>
          MediatorId
            .fromProtoPrimitive(m.mediatorId, "mediator")
            .fold(err => throw new IllegalArgumentException(err.message), identity)
        )
      svParticipants.filterNot(offboardedSvParticipants.contains) ++ svMediators
    }

    def getSvMemberName(svParty: PartyId): Future[String] =
      dsoRules.contract.payload.members.asScala
        .get(svParty.toProtoPrimitive)
        .fold(
          Future.failed[String](
            Status.NOT_FOUND
              .withDescription(show"$svParty is not an active DSO member")
              .asRuntimeException()
          )
        )(info => Future.successful(info.name))

  }

  case class DsoRulesWithSvNodeState(
      dsoRules: AssignedContract[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules],
      svParty: PartyId,
      svNodeState: Contract[
        cn.dso.memberstate.SvNodeState.ContractId,
        cn.dso.memberstate.SvNodeState,
      ],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("domainId", _.dsoRules.domain),
        param("dsoRulesCid", _.dsoRules.contractId),
        param("svParty", _.svParty),
        param("svNodeState", _.svNodeState),
      )

    def isStale(
        store: MultiDomainAcsStore
    )(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] =
      for {
        // TODO(#4906): check whether we also need to compare the domain-id to detect staleness
        checkDsoRules <- store.lookupContractById(cn.dsorules.DsoRules.COMPANION)(
          dsoRules.contractId
        )
        checkSvNodeState <- store
          .lookupContractById(cn.dso.memberstate.SvNodeState.COMPANION)(svNodeState.contractId)
      } yield checkDsoRules.isEmpty || checkSvNodeState.isEmpty

    def lookupSequencerConfigFor(
        globalDomainId: DomainId,
        domainTimeLowerBound: Instant,
        migrationId: Long,
    ): Option[cn.dso.globaldomain.SequencerConfig] = {
      for {
        domainNodeConfig <- svNodeState.payload.state.domainNodes.asScala
          .get(globalDomainId.toProtoPrimitive)
        sequencerConfig <- domainNodeConfig.sequencer.toScala
        if sequencerConfig.migrationId == migrationId && sequencerConfig.url.nonEmpty && sequencerConfig.availableAfter.toScala
          .exists(availableAfter => domainTimeLowerBound.isAfter(availableAfter))
      } yield sequencerConfig
    }
  }
}

case class ExpiredRewardCouponsBatch(
    closedRoundCid: cc.round.ClosedMiningRound.ContractId,
    closedRoundNumber: Long,
    validatorCoupons: Seq[cc.amulet.ValidatorRewardCoupon.ContractId],
    appCoupons: Seq[cc.amulet.AppRewardCoupon.ContractId],
    svRewardCoupons: Seq[cc.amulet.SvRewardCoupon.ContractId],
    validatorFaucets: Seq[cc.validatorlicense.ValidatorFaucetCoupon.ContractId],
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("closedRoundCid", _.closedRoundCid.contractId.singleQuoted),
      param("closedRoundNumber", _.closedRoundNumber),
      customParam(inst => s"validatorCoupons: ${inst.validatorCoupons}"),
      customParam(inst => s"appCoupons: ${inst.appCoupons}"),
      customParam(inst => s"validatorFaucetCoupons: ${inst.validatorFaucets}"),
    )
}

case class AppRewardCouponsSum(featured: BigDecimal, unfeatured: BigDecimal)
