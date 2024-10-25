// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store

import cats.implicits.toTraverseOps
import com.daml.ledger.javaapi.data as javab
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.automation.TransferFollowTrigger.Task as FollowTask
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedReward
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules_MiningRound_Archive,
  AppTransferContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as vl
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.amuletprice as cp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_ConfirmSvOnboarding,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding as so
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as sub
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection.GetAmuletRulesDomain
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{
  ConstrainedTemplate,
  QueryResult,
  TemplateFilter,
}
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
import org.lfdecentralizedtrust.splice.sv.store.db.DbSvDsoStore
import org.lfdecentralizedtrust.splice.sv.store.db.DsoTables.DsoAcsStoreRowData
import org.lfdecentralizedtrust.splice.util.Contract.Companion.Template as TemplateCompanion
import org.lfdecentralizedtrust.splice.util.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, Member, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

/* Store used by the SV app for filtering contracts visible to the DSO party. */
trait SvDsoStore
    extends AppStore
    with PackageIdResolver.HasAmuletRules
    with DsoRulesStore
    with MiningRoundsStore
    with VotesStore {
  import SvDsoStore.{amuletRulesFollowers, dsoRulesFollowers}

  protected val outerLoggerFactory: NamedLoggerFactory
  protected def templateJsonDecoder: TemplateJsonDecoder

  override protected lazy val loggerFactory: NamedLoggerFactory =
    outerLoggerFactory.append("store", "dsoParty")

  override lazy val acsContractFilter =
    SvDsoStore.contractFilter(key.dsoParty, domainMigrationId)

  def key: SvStore.Key

  def domainMigrationId: Long

  def lookupSvStatusReport(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[
    AssignedContract[
      splice.dso.svstate.SvStatusReport.ContractId,
      splice.dso.svstate.SvStatusReport,
    ]
  ]]

  def getSvStatusReport(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[
    AssignedContract[
      splice.dso.svstate.SvStatusReport.ContractId,
      splice.dso.svstate.SvStatusReport,
    ]
  ] =
    lookupSvStatusReport(svPartyId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"No SvStatusReport found for $svPartyId")
          .asRuntimeException()
      )
    )

  def lookupSvRewardState(svName: String)(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[SvRewardState.ContractId, SvRewardState]]]

  def listSvRewardStates(svName: String, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvRewardState.ContractId, SvRewardState]]]

  def lookupAmuletRulesWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[
      AssignedContract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]
    ]]
  ] = multiDomainAcsStore
    .findAnyContractWithOffset(splice.amuletrules.AmuletRules.COMPANION)
    .map(_.map(_.flatMap(_.toAssignedContract)))

  def lookupAmuletRules()(implicit
      tc: TraceContext
  ): Future[
    Option[
      AssignedContract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]
    ]
  ] =
    lookupAmuletRulesWithOffset().map(_.value)

  def getAmuletRules()(implicit
      tc: TraceContext
  ): Future[Contract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]] =
    getAssignedAmuletRules().map(_.contract)

  def getAssignedAmuletRules()(implicit
      tc: TraceContext
  ): Future[
    AssignedContract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]
  ] =
    lookupAmuletRules().map(
      _.getOrElse(
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

  def lookupAnsRulesWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[AssignedContract[splice.ans.AnsRules.ContractId, splice.ans.AnsRules]]]
  ] = {
    for {
      result <- multiDomainAcsStore
        .findAnyContractWithOffset(splice.ans.AnsRules.COMPANION)
    } yield result.map(_.flatMap(_.toAssignedContract))
  }

  def lookupAnsRules()(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[splice.ans.AnsRules.ContractId, splice.ans.AnsRules]]] =
    lookupAnsRulesWithOffset().map(_.value)

  def getAnsRules()(implicit
      tc: TraceContext
  ): Future[Contract[splice.ans.AnsRules.ContractId, splice.ans.AnsRules]] =
    lookupAnsRules().map(
      _.map(_.contract).getOrElse(
        throw Status.NOT_FOUND.withDescription("No active AnsRules contract").asRuntimeException()
      )
    )

  /** List amulets that are expired and can never be used as transfer input. */
  final def listExpiredAmulets
      : ListExpiredContracts[splice.amulet.Amulet.ContractId, splice.amulet.Amulet] =
    listExpiredRoundBased(splice.amulet.Amulet.COMPANION)(identity)

  /** List locked amulets that are expired and can never be used as transfer input. */
  final def listLockedExpiredAmulets
      : ListExpiredContracts[splice.amulet.LockedAmulet.ContractId, splice.amulet.LockedAmulet] =
    listExpiredRoundBased(splice.amulet.LockedAmulet.COMPANION)(_.amulet)

  def listExpiredVoteRequests(): ListExpiredContracts[VoteRequest.ContractId, VoteRequest] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(VoteRequest.COMPANION)

  def listConfirmations(
      action: splice.dsorules.ActionRequiringConfirmation,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]]]

  def listAppRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[splice.amulet.AppRewardCoupon.ContractId, splice.amulet.AppRewardCoupon]]]

  def sumAppRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit
      tc: TraceContext
  ): Future[AppRewardCouponsSum]

  def listAppRewardCouponsGroupedByCounterparty(
      domain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvDsoStore.RoundCounterpartyBatch[splice.amulet.AppRewardCoupon.ContractId]]]

  def listValidatorRewardCouponsOnDomain(
      round: Long,
      roundDomain: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[
      Contract[splice.amulet.ValidatorRewardCoupon.ContractId, splice.amulet.ValidatorRewardCoupon]
    ]
  ]

  def sumValidatorRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[BigDecimal]

  def listValidatorRewardCouponsGroupedByCounterparty(
      domain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvDsoStore.RoundCounterpartyBatch[splice.amulet.ValidatorRewardCoupon.ContractId]]]

  def listValidatorFaucetCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[
      splice.validatorlicense.ValidatorFaucetCoupon.ContractId,
      splice.validatorlicense.ValidatorFaucetCoupon,
    ]]
  ]

  def listValidatorLivenessActivityRecordsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[
      splice.validatorlicense.ValidatorLivenessActivityRecord.ContractId,
      splice.validatorlicense.ValidatorLivenessActivityRecord,
    ]]
  ]

  def countValidatorFaucetCouponsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[Long]

  def countValidatorLivenessActivityRecordsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[Long]

  def listValidatorFaucetCouponsGroupedByCounterparty(
      domain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[SvDsoStore.RoundCounterpartyBatch[splice.validatorlicense.ValidatorFaucetCoupon.ContractId]]
  ]

  def listValidatorLivenessActivityRecordsGroupedByCounterparty(
      domain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[SvDsoStore.RoundCounterpartyBatch[
      splice.validatorlicense.ValidatorLivenessActivityRecord.ContractId
    ]]
  ]

  def listSvRewardCouponsOnDomain(
      round: Long,
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[
      splice.amulet.SvRewardCoupon.ContractId,
      splice.amulet.SvRewardCoupon,
    ]]
  ]

  /** Get the closed round contracts associated with the given round numbers.
    * Contracts that do not exist are filtered out.
    */
  def listClosedRounds(
      roundNumbers: Set[Long],
      domainId: DomainId,
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[splice.round.ClosedMiningRound.ContractId, splice.round.ClosedMiningRound]]
  ]

  def sumSvRewardCouponWeightsOnDomain(
      round: Long,
      domainId: DomainId,
  )(implicit tc: TraceContext): Future[Long]

  def listSvRewardCouponsGroupedByCounterparty(
      domain: DomainId,
      totalCouponsLimit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[SvDsoStore.RoundCounterpartyBatch[splice.amulet.SvRewardCoupon.ContractId]]]

  protected[this] def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[
    Option[AssignedContract[
      splice.round.ClosedMiningRound.ContractId,
      splice.round.ClosedMiningRound,
    ]]
  ]

  final def getExpiredRewards(
      domain: DomainId,
      enableExpireValidatorFaucet: Boolean,
      totalCouponsLimit: Limit = PageLimit.tryCreate(100),
  )(implicit
      tc: TraceContext
  ): Future[Seq[ExpiredRewardCouponsBatch]] = {
    def filterRoundCounterpartyBatch[T](
        batches: Seq[SvDsoStore.RoundCounterpartyBatch[T]],
        roundMap: Map[
          java.lang.Long,
          Contract[splice.round.ClosedMiningRound.ContractId, splice.round.ClosedMiningRound],
        ],
    ): Seq[
      (Contract[splice.round.ClosedMiningRound.ContractId, splice.round.ClosedMiningRound], Seq[T])
    ] =
      batches.flatMap { batch =>
        roundMap.get(batch.roundNumber).map(closedRound => (closedRound, batch.batch)).toList
      }
    for {
      appRewardGroups <- listAppRewardCouponsGroupedByCounterparty(
        domain,
        totalCouponsLimit = totalCouponsLimit,
      )
      validatorRewardGroups <- listValidatorRewardCouponsGroupedByCounterparty(
        domain,
        totalCouponsLimit = totalCouponsLimit,
      )
      validatorFaucetGroups <-
        if (enableExpireValidatorFaucet)
          listValidatorFaucetCouponsGroupedByCounterparty(
            domain,
            totalCouponsLimit = totalCouponsLimit,
          )
        else Future.successful(Seq.empty)
      validatorLivenessActivityRecordGroups <-
        listValidatorLivenessActivityRecordsGroupedByCounterparty(
          domain,
          totalCouponsLimit = totalCouponsLimit,
        )
      svRewardCouponGroups <- listSvRewardCouponsGroupedByCounterparty(
        domain,
        totalCouponsLimit = totalCouponsLimit,
      )
      roundNumbers =
        (appRewardGroups ++ validatorRewardGroups ++ validatorFaucetGroups ++ validatorLivenessActivityRecordGroups ++ svRewardCouponGroups)
          .map(_.roundNumber)
          .toSet
      closedRounds <- listClosedRounds(roundNumbers, domain, totalCouponsLimit)
      closedRoundMap = closedRounds.map(r => r.payload.round.number -> r).toMap
    } yield filterRoundCounterpartyBatch(appRewardGroups, closedRoundMap).map {
      case (closedRound, batch) =>
        ExpiredRewardCouponsBatch(
          closedRoundCid = closedRound.contractId,
          closedRoundNumber = closedRound.payload.round.number,
          validatorCoupons = Seq.empty,
          appCoupons = batch,
          svRewardCoupons = Seq.empty,
          validatorFaucets = Seq.empty,
          validatorLivenessActivityRecords = Seq.empty,
        )
    } ++
      filterRoundCounterpartyBatch(validatorRewardGroups, closedRoundMap).map {
        case (closedRound, batch) =>
          ExpiredRewardCouponsBatch(
            closedRoundCid = closedRound.contractId,
            closedRoundNumber = closedRound.payload.round.number,
            validatorCoupons = batch,
            appCoupons = Seq.empty,
            svRewardCoupons = Seq.empty,
            validatorFaucets = Seq.empty,
            validatorLivenessActivityRecords = Seq.empty,
          )
      } ++ filterRoundCounterpartyBatch(validatorFaucetGroups, closedRoundMap).map {
        case (closedRound, batch) =>
          ExpiredRewardCouponsBatch(
            closedRoundCid = closedRound.contractId,
            closedRoundNumber = closedRound.payload.round.number,
            validatorCoupons = Seq.empty,
            appCoupons = Seq.empty,
            svRewardCoupons = Seq.empty,
            validatorFaucets = batch,
            validatorLivenessActivityRecords = Seq.empty,
          )
      } ++ filterRoundCounterpartyBatch(validatorLivenessActivityRecordGroups, closedRoundMap).map {
        case (closedRound, batch) =>
          ExpiredRewardCouponsBatch(
            closedRoundCid = closedRound.contractId,
            closedRoundNumber = closedRound.payload.round.number,
            validatorCoupons = Seq.empty,
            appCoupons = Seq.empty,
            svRewardCoupons = Seq.empty,
            validatorFaucets = Seq.empty,
            validatorLivenessActivityRecords = batch,
          )
      } ++ filterRoundCounterpartyBatch(svRewardCouponGroups, closedRoundMap).map {
        case (closedRound, batch) =>
          ExpiredRewardCouponsBatch(
            closedRoundCid = closedRound.contractId,
            closedRoundNumber = closedRound.payload.round.number,
            validatorCoupons = Seq.empty,
            appCoupons = Seq.empty,
            svRewardCoupons = batch,
            validatorFaucets = Seq.empty,
            validatorLivenessActivityRecords = Seq.empty,
          )
      }
  }

  def listOldestSummarizingMiningRounds(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[
    splice.round.SummarizingMiningRound.ContractId,
    splice.round.SummarizingMiningRound,
  ]]]

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
        splice.round.ClosedMiningRound.ContractId,
        splice.round.ClosedMiningRound,
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
        splice.round.ClosedMiningRound.COMPANION,
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
          validatorLivenessActivityRecords <- listValidatorLivenessActivityRecordsOnDomain(
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
              appRewardCoupons.isEmpty && validatorRewardCoupons.isEmpty && validatorLivenessActivityRecords.isEmpty && svRewardCoupons.isEmpty &&
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
      Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]
    ]]
  ]

  def lookupAnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: sub.SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    QueryResult[
      Option[Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]]
    ]
  ]

  def lookupAnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: sub.SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    QueryResult[
      Option[Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]]
    ]
  ]

  def lookupAnsInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: sub.SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    QueryResult[
      Option[Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]]
    ]
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
    multiDomainAcsStore.listExpiredFromPayloadExpiry(so.SvOnboardingRequest.COMPANION)

  def listExpiredSvOnboardingConfirmed
      : ListExpiredContracts[so.SvOnboardingConfirmed.ContractId, so.SvOnboardingConfirmed] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(so.SvOnboardingConfirmed.COMPANION)

  def listExpiredAnsEntries: ListExpiredContracts[
    splice.ans.AnsEntry.ContractId,
    splice.ans.AnsEntry,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(splice.ans.AnsEntry.COMPANION)

  def listExpiredAnsSubscriptions(
      now: CantonTimestamp,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[SvDsoStore.IdleAnsSubscription]]

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
  ): Future[
    Seq[Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]]
  ] = {
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

  def listSvOnboardingRequestsBySvs(
      dsoRules: Contract.Has[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]

  protected def listExpiredRoundBased[Id <: javab.codegen.ContractId[T], T <: javab.Template](
      companion: TemplateCompanion[Id, T]
  )(amulet: T => splice.amulet.Amulet): ListExpiredContracts[Id, T]

  final def listUnclaimedRewards(
      limit: Limit
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[UnclaimedReward.ContractId, splice.amulet.UnclaimedReward]]] =
    for {
      unclaimedRewards <- multiDomainAcsStore.listContracts(
        splice.amulet.UnclaimedReward.COMPANION,
        limit = limit,
      )
    } yield unclaimedRewards map (_.contract)

  def listMemberTrafficContracts(memberId: Member, domainId: DomainId, limit: Limit)(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[
      splice.decentralizedsynchronizer.MemberTraffic.ContractId,
      splice.decentralizedsynchronizer.MemberTraffic,
    ]]
  ]

  /** List issuing mining rounds past their targetClosesAt */
  def listExpiredIssuingMiningRounds: ListExpiredContracts[
    splice.round.IssuingMiningRound.ContractId,
    splice.round.IssuingMiningRound,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(splice.round.IssuingMiningRound.COMPANION)

  /** List stale confirmations past their expiresAt */
  def listStaleConfirmations: ListExpiredContracts[
    splice.dsorules.Confirmation.ContractId,
    splice.dsorules.Confirmation,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(splice.dsorules.Confirmation.COMPANION)

  /** List all the current amulet price votes. */
  final def listAllAmuletPriceVotes(
      limit: Limit = Limit.DefaultLimit
  )(implicit tc: TraceContext): Future[
    Seq[Contract[
      splice.dso.amuletprice.AmuletPriceVote.ContractId,
      splice.dso.amuletprice.AmuletPriceVote,
    ]]
  ] =
    for {
      votes <- multiDomainAcsStore.listContracts(
        splice.dso.amuletprice.AmuletPriceVote.COMPANION,
        limit,
      )
    } yield votes map (_.contract)

  /** List the current amulet price votes by the SVs. */
  def listSvAmuletPriceVotes(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[
      splice.dso.amuletprice.AmuletPriceVote.ContractId,
      splice.dso.amuletprice.AmuletPriceVote,
    ]]
  ]

  protected def lookupSvOnboardingRequestByCandidatePartyWithOffset(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[so.SvOnboardingRequest.ContractId, so.SvOnboardingRequest]]]
  ]

  def lookupValidatorLicenseWithOffset(validator: PartyId)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[vl.ValidatorLicense.ContractId, vl.ValidatorLicense]]]]

  def listValidatorLicensePerValidator(validator: String, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]]

  def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long]

  def listAmuletPriceVotes(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[
      splice.dso.amuletprice.AmuletPriceVote.ContractId,
      splice.dso.amuletprice.AmuletPriceVote,
    ]]
  ] =
    multiDomainAcsStore
      .listContracts(splice.dso.amuletprice.AmuletPriceVote.COMPANION, limit)
      .map(_ map (_.contract))

  def lookupVoteByThisSvAndVoteRequestWithOffset(
      voteRequestCid: splice.dsorules.VoteRequest.ContractId
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[splice.dsorules.Vote]]]

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
      dsoRules: AssignedContract[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[splice.dsorules.ElectionRequest.ContractId, splice.dsorules.ElectionRequest]]
  ]

  def lookupElectionRequestByRequesterWithOffset(
      requester: PartyId,
      epoch: Long,
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[
      Contract[splice.dsorules.ElectionRequest.ContractId, splice.dsorules.ElectionRequest]
    ]]
  ]

  def listExpiredElectionRequests(
      epoch: Long,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[
    splice.dsorules.ElectionRequest.ContractId,
    splice.dsorules.ElectionRequest,
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
  ): Future[
    Seq[FollowTask[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules, _, _]]
  ] = {
    lookupDsoRules().flatMap(_.map { dsoRules =>
      listLaggingDsoRulesFollowers(dsoRules.domain)
        .map(_ map (FollowTask(dsoRules, _)))
    }.getOrElse(Future successful Seq.empty))
  }

  def listAmuletRulesTransferFollowers()(implicit
      tc: TraceContext
  ): Future[
    Seq[FollowTask[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules, ?, ?]]
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

  def lookupAnsEntryByNameWithOffset(name: String, now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[AssignedContract[splice.ans.AnsEntry.ContractId, splice.ans.AnsEntry]]]
  ]

  def lookupAnsEntryByName(
      name: String,
      now: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[splice.ans.AnsEntry.ContractId, splice.ans.AnsEntry]]] =
    lookupAnsEntryByNameWithOffset(name, now).map(_.value)

  final def lookupAnsEntryContext(contractId: splice.ans.AnsEntryContext.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[splice.ans.AnsEntryContext.ContractId, splice.ans.AnsEntryContext]]] =
    for {
      cws <- multiDomainAcsStore
        .lookupContractById(splice.ans.AnsEntryContext.COMPANION)(contractId)
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

  def listInitialPaymentConfirmationByAnsName(
      confirmer: PartyId,
      name: String,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]]
  ]

  def lookupFeaturedAppRightWithOffset(
      providerPartyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[
        AssignedContract[splice.amulet.FeaturedAppRight.ContractId, splice.amulet.FeaturedAppRight]
      ]
    ]
  ]

  def lookupFeaturedAppRight(
      providerPartyId: PartyId
  )(implicit
      tc: TraceContext
  ): Future[
    Option[
      AssignedContract[splice.amulet.FeaturedAppRight.ContractId, splice.amulet.FeaturedAppRight]
    ]
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
      openMiningRound: MiningRoundsStore.OpenMiningRound[Contract.Has]
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

  def lookupAnsEntryContext(
      reference: sub.SubscriptionRequest.ContractId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    splice.ans.AnsEntryContext.ContractId,
    splice.ans.AnsEntryContext,
  ]]]

  def lookupTransferPreapprovalByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[
      splice.amuletrules.TransferPreapproval.ContractId,
      splice.amuletrules.TransferPreapproval,
    ]]
  ]

  def listAmuletsByOwner(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Seq[ContractWithState[
      splice.amulet.Amulet.ContractId,
      splice.amulet.Amulet,
    ]]
  ]

  def lookupTransferCommandCounterBySenderWithOffset(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[
    QueryResult[Option[ContractWithState[
      splice.externalpartyamuletrules.TransferCommandCounter.ContractId,
      splice.externalpartyamuletrules.TransferCommandCounter,
    ]]]
  ]

  def lookupTransferCommandCounterBySender(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[
    Option[ContractWithState[
      splice.externalpartyamuletrules.TransferCommandCounter.ContractId,
      splice.externalpartyamuletrules.TransferCommandCounter,
    ]]
  ] = lookupTransferCommandCounterBySenderWithOffset(partyId).map(_.value)

  def listTransferCommandCounterConfirmationBySender(
      confirmer: PartyId,
      sender: PartyId,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[splice.dsorules.Confirmation.ContractId, splice.dsorules.Confirmation]]
  ]

  def listExpiredTransferPreapprovals: ListExpiredContracts[
    splice.amuletrules.TransferPreapproval.ContractId,
    splice.amuletrules.TransferPreapproval,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(
      splice.amuletrules.TransferPreapproval.COMPANION
    )
}

object SvDsoStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
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
          domainMigrationInfo,
          participantId,
        )
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  }

  private val dsoRulesFollowers: Seq[ConstrainedTemplate] = {
    import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules
    Seq[ConstrainedTemplate](
      // AmuletRules is specially handled so should *not* be listed here, even
      // though it follows DsoRules
      dsorules.VoteRequest.COMPANION,
      dsorules.Confirmation.COMPANION,
      dsorules.ElectionRequest.COMPANION,
      so.SvOnboardingRequest.COMPANION,
      so.SvOnboardingConfirmed.COMPANION,
      splice.dso.svstate.SvStatusReport.COMPANION,
      splice.dso.svstate.SvNodeState.COMPANION,
      splice.dso.svstate.SvRewardState.COMPANION,
    )
  }

  private[splice] val amuletRulesFollowers: Seq[ConstrainedTemplate] = Seq[ConstrainedTemplate](
    splice.round.OpenMiningRound.COMPANION,
    splice.round.SummarizingMiningRound.COMPANION,
    splice.round.IssuingMiningRound.COMPANION,
    splice.round.ClosedMiningRound.COMPANION,
    splice.amulet.FeaturedAppRight.COMPANION,
    splice.amulet.UnclaimedReward.COMPANION,
    splice.validatorlicense.ValidatorLicense.COMPANION,
    splice.ans.AnsEntry.COMPANION,
    splice.ans.AnsEntryContext.COMPANION,
    splice.ans.AnsRules.COMPANION,
    splice.amuletrules.TransferPreapproval.COMPANION,
    splice.dso.amuletprice.AmuletPriceVote.COMPANION,
    splice.wallet.subscriptions.TerminatedSubscription.COMPANION, // TODO (#8782) move it to UserWalletStore.templatesMovedByMyAutomation
  )

  private[splice] val templatesMovedByMyAutomation: Seq[ConstrainedTemplate] =
    (dsoRulesFollowers ++ amuletRulesFollowers) ++ Seq[ConstrainedTemplate](
      // AmuletRules and DsoRules are specially handled, so not listed in followers
      splice.dsorules.DsoRules.COMPANION,
      splice.amuletrules.AmuletRules.COMPANION,
    )

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(
      dsoParty: PartyId,
      domainMigrationId: Long,
  ): MultiDomainAcsStore.ContractFilter[DsoAcsStoreRowData] = {
    import MultiDomainAcsStore.mkFilter
    val dso = dsoParty.toProtoPrimitive

    val dsoFilters = Map[PackageQualifiedName, TemplateFilter[?, ?, DsoAcsStoreRowData]](
      mkFilter(splice.dso.amuletprice.AmuletPriceVote.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            voter = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
          )
      },
      mkFilter(splice.dsorules.Confirmation.COMPANION)(co => co.payload.dso == dso) { contract =>
        val (
          actionAnsEntryContextCid,
          actionAnsEntryContextPaymentId,
          actionAnsEntryContextArcType,
        ) =
          contract.payload.action match {
            case arcAnsEntryContext: splice.dsorules.actionrequiringconfirmation.ARC_AnsEntryContext =>
              arcAnsEntryContext.ansEntryContextAction match {
                case action: splice.dsorules.ansentrycontext_actionrequiringconfirmation.ANSRARC_CollectInitialEntryPayment =>
                  (
                    Some(arcAnsEntryContext.ansEntryContextCid),
                    Some(action.ansEntryContext_CollectInitialEntryPaymentValue.paymentCid),
                    Some("ANSRARC_CollectInitialEntryPayment"),
                  )
                case action: splice.dsorules.ansentrycontext_actionrequiringconfirmation.ANSRARC_RejectEntryInitialPayment =>
                  (
                    Some(arcAnsEntryContext.ansEntryContextCid),
                    Some(action.ansEntryContext_RejectEntryInitialPaymentValue.paymentCid),
                    Some("ANSRARC_RejectEntryInitialPayment"),
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
          actionAnsEntryContextCid = actionAnsEntryContextCid,
          actionAnsEntryContextPaymentId = actionAnsEntryContextPaymentId,
          actionAnsEntryContextArcType = actionAnsEntryContextArcType,
        )
      },
      mkFilter(splice.dsorules.ElectionRequest.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          requester = Some(PartyId.tryFromProtoPrimitive(contract.payload.requester)),
          electionRequestEpoch = Some(contract.payload.epoch),
        )
      },
      mkFilter(splice.dsorules.VoteRequest.COMPANION)(co => co.payload.dso == dso) { contract =>
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
      mkFilter(splice.dsorules.DsoRules.COMPANION)(co => co.payload.dso == dso)(
        DsoAcsStoreRowData(_)
      ),
      mkFilter(splice.dso.svstate.SvStatusReport.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            svParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
          )
      },
      mkFilter(splice.dso.svstate.SvNodeState.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          svParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
        )
      },
      mkFilter(splice.dso.svstate.SvRewardState.COMPANION)(co => co.payload.dso == dso) {
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
      mkFilter(splice.amuletrules.AmuletRules.COMPANION)(co => co.payload.dso == dso)(
        DsoAcsStoreRowData(_)
      ),
      mkFilter(splice.amulet.Amulet.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          amuletRoundOfExpiry = Some(SpliceUtil.amuletExpiresAt(contract.payload).number),
        )
      },
      mkFilter(splice.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          featuredAppRightProvider = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
        )
      },
      mkFilter(splice.amulet.LockedAmulet.COMPANION)(co => co.payload.amulet.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            amuletRoundOfExpiry = Some(SpliceUtil.amuletExpiresAt(contract.payload.amulet).number),
          )
      },
      mkFilter(splice.amulet.AppRewardCoupon.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          rewardRound = Some(contract.payload.round.number),
          rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
          rewardAmount = Some(contract.payload.amount),
          appRewardIsFeatured = Some(contract.payload.featured),
        )
      },
      mkFilter(splice.amulet.ValidatorRewardCoupon.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            rewardRound = Some(contract.payload.round.number),
            rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
            rewardAmount = Some(contract.payload.amount),
          )
      },
      mkFilter(splice.validatorlicense.ValidatorFaucetCoupon.COMPANION)(co =>
        co.payload.dso == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          rewardRound = Some(contract.payload.round.number),
          rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
        )
      },
      mkFilter(splice.validatorlicense.ValidatorLivenessActivityRecord.COMPANION)(co =>
        co.payload.dso == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          rewardRound = Some(contract.payload.round.number),
          rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
        )
      },
      mkFilter(splice.amulet.SvRewardCoupon.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          rewardRound = Some(contract.payload.round.number),
          rewardParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
          rewardWeight = Some(contract.payload.weight),
        )
      },
      mkFilter(splice.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          miningRound = Some(contract.payload.round.number),
        )
      },
      mkFilter(splice.round.IssuingMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
          miningRound = Some(contract.payload.round.number),
        )
      },
      mkFilter(splice.round.SummarizingMiningRound.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            miningRound = Some(contract.payload.round.number),
          )
      },
      mkFilter(splice.round.ClosedMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          miningRound = Some(contract.payload.round.number),
        )
      },
      mkFilter(splice.amulet.UnclaimedReward.COMPANION)(co => co.payload.dso == dso)(
        DsoAcsStoreRowData(_)
      ),
      mkFilter(vl.ValidatorLicense.COMPANION)(vl => vl.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          validator = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
        )
      },
      mkFilter(splice.decentralizedsynchronizer.MemberTraffic.COMPANION)(vt =>
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
          memberTrafficDomain = Some(DomainId.tryFromString(contract.payload.synchronizerId)),
          totalTrafficPurchased = Some(contract.payload.totalPurchased),
        )
      },
      mkFilter(splice.ans.AnsRules.COMPANION)(co => co.payload.dso == dso)(DsoAcsStoreRowData(_)),
      mkFilter(splice.ans.AnsEntry.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          ansEntryName = Some(contract.payload.name),
        )
      },
      mkFilter(splice.ans.AnsEntryContext.COMPANION)(co => co.payload.dso == dso) { contract =>
        DsoAcsStoreRowData(
          contract,
          ansEntryName = Some(contract.payload.name),
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
      mkFilter(splice.amuletrules.TransferPreapproval.COMPANION)(co => co.payload.dso == dso) {
        contract =>
          DsoAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
      },
      mkFilter(splice.externalpartyamuletrules.TransferCommand.COMPANION)(co =>
        co.payload.dso == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract
        )
      },
      mkFilter(splice.externalpartyamuletrules.TransferCommandCounter.COMPANION)(co =>
        co.payload.dso == dso
      ) { contract =>
        DsoAcsStoreRowData(
          contract,
          walletParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sender)),
        )
      },
    )

    MultiDomainAcsStore.SimpleContractFilter(
      dsoParty,
      dsoFilters,
    )
  }

  case class IdleAnsSubscription(
      state: Contract[
        sub.SubscriptionIdleState.ContractId,
        sub.SubscriptionIdleState,
      ],
      context: Contract[
        splice.ans.AnsEntryContext.ContractId,
        splice.ans.AnsEntryContext,
      ],
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("state", _.state), param("context", _.context))
  }

  case class RoundCounterpartyBatch[+T](
      counterparty: PartyId,
      roundNumber: Long,
      batch: Seq[T],
  )
}

case class ExpiredRewardCouponsBatch(
    closedRoundCid: splice.round.ClosedMiningRound.ContractId,
    closedRoundNumber: Long,
    validatorCoupons: Seq[splice.amulet.ValidatorRewardCoupon.ContractId],
    appCoupons: Seq[splice.amulet.AppRewardCoupon.ContractId],
    svRewardCoupons: Seq[splice.amulet.SvRewardCoupon.ContractId],
    validatorFaucets: Seq[splice.validatorlicense.ValidatorFaucetCoupon.ContractId],
    validatorLivenessActivityRecords: Seq[
      splice.validatorlicense.ValidatorLivenessActivityRecord.ContractId
    ],
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("closedRoundCid", _.closedRoundCid.contractId.singleQuoted),
      param("closedRoundNumber", _.closedRoundNumber),
      customParam(inst => s"validatorCoupons: ${inst.validatorCoupons}"),
      customParam(inst => s"appCoupons: ${inst.appCoupons}"),
      customParam(inst => s"svRewardCoupons: ${inst.svRewardCoupons}"),
      customParam(inst => s"validatorFaucetCoupons: ${inst.validatorFaucets}"),
      customParam(inst =>
        s"validatorLivenessActivityRecords: ${inst.validatorLivenessActivityRecords}"
      ),
    )
}

case class AppRewardCouponsSum(featured: BigDecimal, unfeatured: BigDecimal)
