// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import cats.data.OptionT
import cats.implicits.*
import com.daml.ledger.javaapi.data as javab
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.nonempty.NonEmpty
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  SummarizingMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.{
  ValidatorFaucetCoupon,
  ValidatorLicense,
  ValidatorLivenessActivityRecord,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.{AnsEntry, AnsEntryContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.amuletprice.AmuletPriceVote
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.{SvNodeState, SvRewardState}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvStatusReport
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.{
  SvOnboardingConfirmed,
  SvOnboardingRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.{
  SubscriptionIdleState,
  SubscriptionInitialPayment,
  SubscriptionRequest,
}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{ContractCompanion, QueryResult}
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{AcsStoreId, SelectFromAcsTableResult}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsJdbcTypes,
  AcsQueries,
  AcsTables,
  DbAppStore,
  StoreDescriptor,
}
import org.lfdecentralizedtrust.splice.store.{
  DbVotesAcsStoreQueryBuilder,
  UnavailablePartiesStore,
  IngestionSummary,
  Limit,
  LimitHelpers,
  MultiDomainAcsStore,
}
import org.lfdecentralizedtrust.splice.sv.store.{AppRewardCouponsSum, SvDsoStore, SvStore}
import SvDsoStore.RoundBatch
import com.digitalasset.canton.config.CantonRequireTypes.String2066
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.util.Contract.Companion.Template
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{Member, ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.concurrent.{ExecutionContext, Future}

class DbSvDsoStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    val domainMigrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    acsStoreDescriptorUserVersion: Option[Long] = None,
    override val defaultLimit: Limit,
)(implicit
    override protected val ec: ExecutionContext,
    override protected val templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage,
      DsoTables.acsTableName,
      interfaceViewsTableNameOpt = None,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      // WARNING: Reinitializing the acs store is a very expensive operation, as it currently fetches the full
      // unfiltered ACS from the participant, irrespective of the filter defined by `acsContractFilter`.
      // This may lead to the entire app being unavailable or not working properly until the full ACS has been ingested.
      // Do not modify any part of the store descriptor unless you are sure that the resulting downtime is acceptable.
      // If you do modify it, make sure to very clearly document in the release notes that there will be planned downtime,
      // and notify the person coordinating the deployment.
      acsStoreDescriptor = StoreDescriptor(
        version = 2,
        name = "DbSvDsoStore",
        party = key.dsoParty,
        participant = participantId,
        key = Map(
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
          "svParty" -> key.svParty.toProtoPrimitive,
        ),
        userVersion = acsStoreDescriptorUserVersion,
      ),
      domainMigrationId,
      ingestionConfig,
    )
    with SvDsoStore
    with AcsTables
    with AcsQueries
    with AcsJdbcTypes
    with DbVotesAcsStoreQueryBuilder
    with LimitHelpers {
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  override protected implicit val dbProfile: DbStorage.Profile = storage.profile

  val dsoStoreMetrics = new DbSvDsoStoreMetrics(retryProvider.metricsFactory)

  override def handleIngestionSummary(summary: IngestionSummary): Unit = {
    summary.ingestedCreatedEvents.foreach { ev =>
      Contract.fromCreatedEvent(splice.round.OpenMiningRound.COMPANION)(ev).foreach { round =>
        dsoStoreMetrics.latestOpenMiningRound.updateValue(round.payload.round.number)
      }
      Contract.fromCreatedEvent(splice.round.IssuingMiningRound.COMPANION)(ev).foreach { round =>
        dsoStoreMetrics.latestIssuingMiningRound.updateValue(round.payload.round.number)
      }
    }
  }

  import multiDomainAcsStore.waitUntilAcsIngested

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId

  override def listExpiredAnsSubscriptions(
      now: CantonTimestamp,
      limit: Limit = defaultLimit,
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None,
  )(implicit tc: TraceContext): Future[Seq[SvDsoStore.IdleAnsSubscription]] = waitUntilAcsIngested {
    for {
      ignoredParties <- UnavailablePartiesStore.listParties(unavailablePartiesStore)
      ignoredPartiesFilter: SQLActionBuilder =
        if (ignoredParties.nonEmpty) {
          (sql" and " ++ notInClause(
            "idle.create_arguments->'subscriptionData'->>'sender'",
            ignoredParties,
          )).toActionBuilder
        } else {
          sql""
        }
      joinedRows <- storage
        .query(
          (sql"""
              select
                       idle.store_id,
                       idle.migration_id,
                       idle.event_number,
                       idle.contract_id,
                       idle.template_id_package_id,
                       idle.template_id_qualified_name,
                       idle.package_name,
                       idle.create_arguments,
                       idle.created_event_blob,
                       idle.created_at,
                       idle.contract_expires_at,
                       ctx.store_id,
                       ctx.migration_id,
                       ctx.event_number,
                       ctx.contract_id,
                       ctx.template_id_package_id,
                       ctx.template_id_qualified_name,
                       ctx.package_name,
                       ctx.create_arguments,
                       ctx.created_event_blob,
                       ctx.created_at,
                       ctx.contract_expires_at
              from     dso_acs_store idle
              join     dso_acs_store ctx
              on       idle.subscription_reference_contract_id = ctx.subscription_reference_contract_id
                and      ctx.store_id = idle.store_id
                and      ctx.migration_id = idle.migration_id
              where    idle.store_id = $acsStoreId
                and      idle.migration_id = $domainMigrationId
                and      idle.template_id_qualified_name = ${QualifiedName(
              SubscriptionIdleState.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                and      ctx.template_id_qualified_name = ${QualifiedName(
              AnsEntryContext.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                and      idle.subscription_next_payment_due_at < $now
                 """ ++ ignoredPartiesFilter ++ sql"""
              order by idle.subscription_next_payment_due_at
              limit    ${sqlLimit(limit)}
          """).toActionBuilder.as[(SelectFromAcsTableResult, SelectFromAcsTableResult)],
          "listExpiredAnsSubscriptions",
        )
    } yield applyLimit("listExpiredAnsSubscriptions", limit, joinedRows).map {
      case (idleRow, ctxRow) =>
        val idleContract = contractFromRow(SubscriptionIdleState.COMPANION)(idleRow)
        val ctxContract = contractFromRow(AnsEntryContext.COMPANION)(ctxRow)
        SvDsoStore.IdleAnsSubscription(idleContract, ctxContract)
    }
  }

  override def listSvOnboardingConfirmed(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            selectFromAcsTable(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              SvOnboardingConfirmed.COMPANION,
              orderLimit = sql"""limit ${sqlLimit(limit)}""",
            ),
            "listSvOnboardingConfirmed",
          )
        limited = applyLimit("listSvOnboardingConfirmed", limit, result)
      } yield limited.map(contractFromRow(SvOnboardingConfirmed.COMPANION)(_))
    }

  override def lookupSvOnboardingConfirmedByParty(svParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .querySingle(
            selectFromAcsTable(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              SvOnboardingConfirmed.COMPANION,
              where = sql"""sv_candidate_party = $svParty""",
              orderLimit = sql"limit 1",
            ).headOption,
            "lookupSvOnboardingConfirmedByParty",
          )
          .value
      } yield result.map(contractFromRow(SvOnboardingConfirmed.COMPANION)(_))
    }

  override def listConfirmations(action: ActionRequiringConfirmation, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            where = sql"""action_requiring_confirmation = ${payloadJsonFromDefinedDataType(
                action
              )}""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listConfirmations",
        )
      limited = applyLimit("listConfirmations", limit, result)
    } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
  }

  override def listConfirmationsByActionConfirmer(
      action: ActionRequiringConfirmation,
      confirmer: PartyId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            where = sql"""
                confirmer = $confirmer
                and action_requiring_confirmation = ${payloadJsonFromDefinedDataType(action)}""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listConfirmations",
        )
      limited = applyLimit("listConfirmations", limit, result)
    } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
  }

  override def listConfirmationsByConfirmer(
      confirmer: PartyId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            where = sql"""confirmer = $confirmer""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listConfirmationsByConfirmer",
        )
      limited = applyLimit("listConfirmationsByConfirmer", limit, result)
    } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
  }

  override def listAllConfirmations(
      limit: Limit
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listAllConfirmations",
        )
      limited = applyLimit("listAllConfirmations", limit, result)
    } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
  }

  override def listAppRewardCouponsOnDomain(
      round: Long,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]]] =
    listRewardCouponsOnDomain(AppRewardCoupon.COMPANION, round, synchronizerId, limit)

  override def sumAppRewardCouponsOnDomain(round: Long, synchronizerId: SynchronizerId)(implicit
      tc: TraceContext
  ): Future[AppRewardCouponsSum] = for {
    sums <- selectFromRewardCouponsOnDomain[(Option[BigDecimal], Option[BigDecimal])](
      sql"""select
              sum(case app_reward_is_featured when true then reward_amount else 0 end),
              sum(case app_reward_is_featured when true then 0 else reward_amount end)""",
      PackageQualifiedName.fromJavaCodegenCompanion(AppRewardCoupon.COMPANION),
      round,
      synchronizerId,
    )
  } yield sums.headOption
    .map { case (featured, unfeatured) =>
      AppRewardCouponsSum(featured = featured.getOrElse(0L), unfeatured = unfeatured.getOrElse(0L))
    }
    .getOrElse(AppRewardCouponsSum(0L, 0L))

  override def listValidatorRewardCouponsOnDomain(
      round: Long,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]]] =
    listRewardCouponsOnDomain(ValidatorRewardCoupon.COMPANION, round, synchronizerId, limit)

  override def sumValidatorRewardCouponsOnDomain(round: Long, synchronizerId: SynchronizerId)(
      implicit tc: TraceContext
  ): Future[BigDecimal] =
    selectFromRewardCouponsOnDomain[Option[BigDecimal]](
      sql"select sum(reward_amount)",
      PackageQualifiedName.fromJavaCodegenCompanion(ValidatorRewardCoupon.COMPANION),
      round,
      synchronizerId,
    ).map(_.headOption.flatten.getOrElse(BigDecimal(0)))

  override def listValidatorFaucetCouponsOnDomain(
      round: Long,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorFaucetCoupon.ContractId, ValidatorFaucetCoupon]]] =
    listRewardCouponsOnDomain(ValidatorFaucetCoupon.COMPANION, round, synchronizerId, limit)

  override def listValidatorLivenessActivityRecordsOnDomain(
      round: Long,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[ValidatorLivenessActivityRecord.ContractId, ValidatorLivenessActivityRecord]]
  ] =
    listRewardCouponsOnDomain(
      ValidatorLivenessActivityRecord.COMPANION,
      round,
      synchronizerId,
      limit,
    )

  override def listSvRewardCouponsOnDomain(
      round: Long,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvRewardCoupon.ContractId, SvRewardCoupon]]] =
    listRewardCouponsOnDomain(SvRewardCoupon.COMPANION, round, synchronizerId, limit)

  override protected def listClosedMiningRoundsWithoutCouponsOnDomain(
      domain: SynchronizerId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]
  ] = {
    val closedRoundPqn =
      PackageQualifiedName.fromJavaCodegenCompanion(ClosedMiningRound.COMPANION)
    val couponPqns = Seq(
      AppRewardCoupon.COMPANION,
      ValidatorRewardCoupon.COMPANION,
      SvRewardCoupon.COMPANION,
      ValidatorLivenessActivityRecord.COMPANION,
    ).map(PackageQualifiedName.fromJavaCodegenCompanion)

    // I think `reduce` makes this nicer to read but the wartremover doesn't like it...
    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    val notExistsClauses = couponPqns
      .map { pqn =>
        sql"""and not exists (
                select 1 from #${DsoTables.acsTableName} rc
                where rc.store_id = $acsStoreId
                  and rc.migration_id = $domainMigrationId
                  and rc.package_name = ${pqn.packageName}
                  and rc.template_id_qualified_name = ${pqn.qualifiedName}
                  and rc.reward_round = cr.mining_round
                  and rc.reward_party is not null
                  and rc.assigned_domain = $domain
              )"""
      }
      .reduce((a, b) => (a ++ b).toActionBuilder)

    val opName = "listClosedMiningRoundsWithoutCouponsOnDomain"
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (sql"""select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated("cr.")}
                   from #${DsoTables.acsTableName} cr
                   where cr.store_id = $acsStoreId
                     and cr.migration_id = $domainMigrationId
                     and cr.package_name = ${closedRoundPqn.packageName}
                     and cr.template_id_qualified_name = ${closedRoundPqn.qualifiedName}
                     and cr.assigned_domain = $domain
                """ ++ notExistsClauses ++ sql"""
                   order by cr.event_number
                   limit ${sqlLimit(limit)}
                """).toActionBuilder.as[SelectFromAcsTableResult],
            opName,
          )
        limited = applyLimit(opName, limit, result)
      } yield limited.map(contractFromRow(ClosedMiningRound.COMPANION)(_))
    }
  }

  override def countValidatorFaucetCouponsOnDomain(round: Long, synchronizerId: SynchronizerId)(
      implicit tc: TraceContext
  ): Future[Long] = selectFromRewardCouponsOnDomain[Option[Long]](
    sql"select count(*)",
    PackageQualifiedName.fromJavaCodegenCompanion(ValidatorFaucetCoupon.COMPANION),
    round,
    synchronizerId,
  ).map(_.headOption.flatten.getOrElse(0L))

  override def countValidatorLivenessActivityRecordsOnDomain(
      round: Long,
      synchronizerId: SynchronizerId,
  )(implicit
      tc: TraceContext
  ): Future[Long] = selectFromRewardCouponsOnDomain[Option[Long]](
    sql"select count(*)",
    PackageQualifiedName.fromJavaCodegenCompanion(ValidatorLivenessActivityRecord.COMPANION),
    round,
    synchronizerId,
  ).map(_.headOption.flatten.getOrElse(0L))

  override def sumSvRewardCouponWeightsOnDomain(round: Long, synchronizerId: SynchronizerId)(
      implicit tc: TraceContext
  ): Future[Long] = selectFromRewardCouponsOnDomain[Option[Long]](
    sql"select sum(reward_weight)",
    PackageQualifiedName.fromJavaCodegenCompanion(SvRewardCoupon.COMPANION),
    round,
    synchronizerId,
  ).map(_.headOption.flatten.getOrElse(0L))

  private def listRewardCouponsOnDomain[C, TCId <: ContractId[?], T](
      companion: C,
      round: Long,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Seq[Contract[TCId, T]]] = {
    selectFromRewardCouponsOnDomain[SelectFromAcsTableResult](
      sql"select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()}",
      companionClass.packageQualifiedName(companion),
      round,
      synchronizerId,
      limit = limit,
    ).map(_.map(contractFromRow(companion)(_)))
  }

  private def selectFromRewardCouponsOnDomain[R: GetResult](
      selectClause: SQLActionBuilder,
      packageQualifiedName: PackageQualifiedName,
      round: Long,
      synchronizerId: SynchronizerId,
      limit: Limit = defaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[R]] = {
    val opName = s"selectFrom${packageQualifiedName.qualifiedName.entityName}OnDomain"
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (selectClause ++
              sql"""
                   from #${DsoTables.acsTableName}
                   where store_id = $acsStoreId
                     and migration_id = $domainMigrationId
                     and package_name = ${packageQualifiedName.packageName}
                     and template_id_qualified_name = ${packageQualifiedName.qualifiedName}
                     and assigned_domain = $synchronizerId
                     and reward_round = $round
                     and reward_party is not null -- otherwise index is not used
                   limit ${sqlLimit(limit)}
                 """).toActionBuilder.as[R],
            opName,
          )
        limited = applyLimit(opName, limit, result)
      } yield limited
    }
  }

  override def listAppRewardCouponsGroupedByRound(
      domain: SynchronizerId,
      batchSize: Limit,
      numBatches: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[Contract[AppRewardCoupon.ContractId, AppRewardCoupon]]]] =
    listCouponsGroupedByRound(
      AppRewardCoupon.COMPANION,
      domain,
      batchSize,
      numBatches,
      ignoredParties,
    )

  override def listValidatorRewardCouponsGroupedByRound(
      domain: SynchronizerId,
      batchSize: Limit,
      numBatches: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]]]] =
    listCouponsGroupedByRound(
      ValidatorRewardCoupon.COMPANION,
      domain,
      batchSize,
      numBatches,
      ignoredParties,
    )

  override def listValidatorFaucetCouponsGroupedByRound(
      domain: SynchronizerId,
      batchSize: Limit,
      numBatches: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[Contract[ValidatorFaucetCoupon.ContractId, ValidatorFaucetCoupon]]]] =
    listCouponsGroupedByRound(
      ValidatorFaucetCoupon.COMPANION,
      domain,
      batchSize,
      numBatches,
      ignoredParties,
    )

  override def listValidatorLivenessActivityRecordsGroupedByRound(
      domain: SynchronizerId,
      batchSize: Limit,
      numBatches: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[
    Contract[ValidatorLivenessActivityRecord.ContractId, ValidatorLivenessActivityRecord]
  ]]] =
    listCouponsGroupedByRound(
      ValidatorLivenessActivityRecord.COMPANION,
      domain,
      batchSize,
      numBatches,
      ignoredParties,
    )

  override def listSvRewardCouponsGroupedByRound(
      domain: SynchronizerId,
      batchSize: Limit,
      numBatches: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[Contract[SvRewardCoupon.ContractId, SvRewardCoupon]]]] =
    listCouponsGroupedByRound(
      SvRewardCoupon.COMPANION,
      domain,
      batchSize,
      numBatches,
      ignoredParties,
    )

  private def listCouponsGroupedByRound[C, TCId <: ContractId[?], T](
      companion: C,
      domain: SynchronizerId,
      batchSize: Limit,
      numBatches: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Seq[SvDsoStore.RoundBatch[Contract[TCId, T]]]] = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    val templateId = companionClass.typeId(companion)
    val opName = s"list${templateId.getEntityName}GroupedByRound"
    val partyFilter =
      if (ignoredParties.nonEmpty)
        companion match {
          case SvRewardCoupon.COMPANION =>
            // only SV reward coupons have a beneficiary field
            (sql"and " ++ notInClause("reward_party", ignoredParties) ++
              sql" and " ++ notInClause(
                "create_arguments ->> 'beneficiary'",
                ignoredParties,
              )).toActionBuilder
          case _ =>
            (sql"and " ++ notInClause("reward_party", ignoredParties)).toActionBuilder
        }
      else sql""
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            (sql"""
                select sub.reward_round, #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()}
                from (
                  select reward_round, #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()},
                         row_number() over (partition by reward_round) as rn
                  from dso_acs_store
                  where store_id = $acsStoreId
                    and migration_id = $domainMigrationId
                    and package_name = ${packageQualifiedName.packageName}
                    and template_id_qualified_name = ${packageQualifiedName.qualifiedName}
                    and assigned_domain = $domain
                    and reward_party is not null -- otherwise index is not used
                    and reward_round is not null -- otherwise index is not used
                    """ ++ partyFilter ++ sql"""
                ) sub
                where sub.rn <= ${sqlLimit(batchSize)}
                order by sub.reward_round asc
                limit ${sqlLimit(numBatches) * sqlLimit(batchSize)}
               """).toActionBuilder.as[(Long, SelectFromAcsTableResult)],
            // the limit at the end is not exact but good enough to avoid fetching an unbounded result, the applyLimit below forces us into the actual batch limit.
            opName,
          )
      } yield {
        val groupedResults = applyLimit(opName, numBatches, result.groupBy(_._1))
        groupedResults.map { case (round, batch) =>
          RoundBatch(
            round,
            batch.map { case (_, c) => contractFromRow(companion)(c) }.toSeq,
          )
        }.toSeq
      }
    }
  }

  override protected def lookupOldestClosedMiningRound()(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[ClosedMiningRound.ContractId, ClosedMiningRound]]] =
    waitUntilAcsIngested {
      (for {
        dsoRules <- OptionT(lookupDsoRules())
        result <- OptionT(
          futureUnlessShutdownToFuture(
            storage
              .querySingle(
                selectFromAcsTableWithState(
                  DsoTables.acsTableName,
                  acsStoreId,
                  domainMigrationId,
                  ClosedMiningRound.COMPANION,
                  additionalWhere = sql"""
                    and assigned_domain = ${dsoRules.domain}
                    and mining_round is not null""",
                  orderLimit = sql"""order by mining_round limit 1""",
                ).headOption,
                "lookupOldestClosedMiningRound",
              )
              .value
          )
        )
      } yield assignedContractFromRow(ClosedMiningRound.COMPANION)(result)).value
    }

  override def listOldestSummarizingMiningRounds(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[AssignedContract[SummarizingMiningRound.ContractId, SummarizingMiningRound]]] =
    for {
      result <- storage
        .query(
          selectFromAcsTableWithState(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            SummarizingMiningRound.COMPANION,
            orderLimit = sql"""order by mining_round limit ${sqlLimit(limit)}""",
          ),
          "listOldestSummarizingMiningRounds",
        )
      limited = applyLimit("listOldestSummarizingMiningRounds", limit, result)
    } yield limited.map(assignedContractFromRow(SummarizingMiningRound.COMPANION)(_))

  override def listCalculateRewardsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[
    AssignedContract[
      splice.amulet.rewardaccountingv2.CalculateRewardsV2.ContractId,
      splice.amulet.rewardaccountingv2.CalculateRewardsV2,
    ]
  ]] =
    for {
      result <- storage
        .query(
          selectFromAcsTableWithState(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            splice.amulet.rewardaccountingv2.CalculateRewardsV2.COMPANION,
            orderLimit = sql"""order by mining_round limit ${sqlLimit(limit)}""",
          ),
          "listCalculateRewardsV2",
        )
      limited = applyLimit("listCalculateRewardsV2", limit, result)
    } yield limited.map(
      assignedContractFromRow(splice.amulet.rewardaccountingv2.CalculateRewardsV2.COMPANION)(_)
    )

  override def listProcessRewardsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[
    AssignedContract[
      splice.amulet.rewardaccountingv2.ProcessRewardsV2.ContractId,
      splice.amulet.rewardaccountingv2.ProcessRewardsV2,
    ]
  ]] =
    for {
      result <- storage
        .query(
          selectFromAcsTableWithState(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            splice.amulet.rewardaccountingv2.ProcessRewardsV2.COMPANION,
            orderLimit = sql"""order by mining_round limit ${sqlLimit(limit)}""",
          ),
          "listProcessRewardsV2",
        )
      limited = applyLimit("listProcessRewardsV2", limit, result)
    } yield limited.map(
      assignedContractFromRow(splice.amulet.rewardaccountingv2.ProcessRewardsV2.COMPANION)(_)
    )

  override def listProcessRewardsV2Sample(dryRun: Boolean, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[
    AssignedContract[
      splice.amulet.rewardaccountingv2.ProcessRewardsV2.ContractId,
      splice.amulet.rewardaccountingv2.ProcessRewardsV2,
    ]
  ]] =
    for {
      result <- storage
        .query(
          (sql"""
             select #${AcsQueries.SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated(
              "sample."
            )}
             from (
               select #${AcsQueries.SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated()}
               from #${DsoTables.acsTableName} acs
               where acs.store_id = $acsStoreId
                 and acs.migration_id = $domainMigrationId
                 and acs.package_name = ${splice.amulet.rewardaccountingv2.ProcessRewardsV2.PACKAGE_NAME}
                 and acs.template_id_qualified_name = ${QualifiedName(
              splice.amulet.rewardaccountingv2.ProcessRewardsV2.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                 and acs.create_arguments->>'dryRun' = ${dryRun.toString}
               limit 1000
             ) sample
             order by random()
             limit ${sqlLimit(limit)}
           """).as[AcsQueries.SelectFromAcsTableWithStateResult],
          "listProcessRewardsV2Sample",
        )
      limited = applyLimit("listProcessRewardsV2Sample", limit, result)
    } yield limited.map(
      assignedContractFromRow(splice.amulet.rewardaccountingv2.ProcessRewardsV2.COMPANION)(_)
    )

  override def listRewardCouponsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[
    AssignedContract[
      splice.amulet.RewardCouponV2.ContractId,
      splice.amulet.RewardCouponV2,
    ]
  ]] =
    for {
      result <- storage
        .query(
          selectFromAcsTableWithState(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            splice.amulet.RewardCouponV2.COMPANION,
            orderLimit = sql"""order by reward_round limit ${sqlLimit(limit)}""",
          ),
          "listRewardCouponsV2",
        )
      limited = applyLimit("listRewardCouponsV2", limit, result)
    } yield limited.map(
      assignedContractFromRow(splice.amulet.RewardCouponV2.COMPANION)(_)
    )

  override def getRewardCouponsV2AgeHistogram(
      t1: FiniteDuration,
      t2: FiniteDuration,
      t3: FiniteDuration,
      now: CantonTimestamp,
      limit: Limit,
  )(implicit tc: TraceContext): Future[(Long, Long, Long, Long)] = {
    val t1m = t1.toMicros
    val t2m = t2.toMicros
    val t3m = t3.toMicros
    for {
      result <- storage
        .query(
          sql"""
            with ages as (
              select $now - created_at as age
              from #${DsoTables.acsTableName} acs
              where acs.store_id = $acsStoreId
                and acs.migration_id = $domainMigrationId
                and acs.package_name = ${splice.amulet.RewardCouponV2.COMPANION.PACKAGE_NAME}
                and acs.template_id_qualified_name = ${QualifiedName(
              splice.amulet.RewardCouponV2.COMPANION.getTemplateIdWithPackageId
            )}
              limit ${sqlLimit(limit)}
            )
            select
              count(*) filter (where age <  $t1m)                as bucket_1,
              count(*) filter (where age >= $t1m and age < $t2m) as bucket_2,
              count(*) filter (where age >= $t2m and age < $t3m) as bucket_3,
              count(*) filter (where age >= $t3m)                as bucket_4
            from ages
         """.toActionBuilder.as[(Long, Long, Long, Long)].head,
          "getRewardCouponsV2AgeHistogram",
        )
    } yield {
      if (result._1 + result._2 + result._3 + result._4 > limit.limit) {
        // Similar to applyLimit(), which warns if the result is truncated
        logger.warn(
          s"There are more than ${limit.limit} RewardCouponV2 contracts, the results of the getRewardCouponsV2TtlHistogram query are inaccurate"
        )
      }
      result
    }
  }

  override def listDryRunRewardAccountingContractsByRounds(rounds: Seq[Long])(implicit
      tc: TraceContext
  ): Future[
    (
        Seq[AssignedContract[
          splice.amulet.rewardaccountingv2.CalculateRewardsV2.ContractId,
          splice.amulet.rewardaccountingv2.CalculateRewardsV2,
        ]],
        Seq[AssignedContract[
          splice.amulet.rewardaccountingv2.ProcessRewardsV2.ContractId,
          splice.amulet.rewardaccountingv2.ProcessRewardsV2,
        ]],
    )
  ] = {
    NonEmpty.from(rounds) match {
      case None => Future.successful((Seq.empty, Seq.empty))
      case Some(rounds) =>
        val roundsClause = DbStorage.toInClause("mining_round", rounds)
        val calculateRewardsF = storage
          .query(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              splice.amulet.rewardaccountingv2.CalculateRewardsV2.COMPANION,
              additionalWhere = (sql" and " ++ roundsClause).toActionBuilder,
            ),
            "listDryRunCalculateRewardsV2ByRounds",
          )
          .map(
            _.map(
              assignedContractFromRow(
                splice.amulet.rewardaccountingv2.CalculateRewardsV2.COMPANION
              )(
                _
              )
            ).filter(_.payload.dryRun)
          )
        val processRewardsF = storage
          .query(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              splice.amulet.rewardaccountingv2.ProcessRewardsV2.COMPANION,
              additionalWhere = (sql" and " ++ roundsClause).toActionBuilder,
            ),
            "listDryRunProcessRewardsV2ByRounds",
          )
          .map(
            _.map(
              assignedContractFromRow(splice.amulet.rewardaccountingv2.ProcessRewardsV2.COMPANION)(
                _
              )
            ).filter(_.payload.dryRun)
          )
        for {
          calculateRewards <- calculateRewardsF
          processRewards <- processRewardsF
        } yield (calculateRewards, processRewards)
    }
  }

  override def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[Confirmation.ContractId, Confirmation]]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            where = sql"""
                    confirmer = $confirmer
                and action_requiring_confirmation = ${payloadJsonFromDefinedDataType(action)}
                  """,
            orderLimit = sql" limit 1",
          ).headOption,
          "lookupConfirmationByActionWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupAnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[
      Contract[Confirmation.ContractId, Confirmation]
    ]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            where = sql"""
                        confirmer = $confirmer
                    and action_ans_entry_context_payment_id = $paymentId
                    and action_ans_entry_context_arc_type = ${lengthLimited(
                DsoTables.AnsActionTypeCollectInitialEntryPayment
              )}
                      """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAnsAcceptedInitialPaymentConfirmationByPaymentIdWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupAnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[
      Contract[Confirmation.ContractId, Confirmation]
    ]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            where = sql"""
                        confirmer = $confirmer
                    and action_ans_entry_context_payment_id = $paymentId
                    and action_ans_entry_context_arc_type = ${lengthLimited(
                DsoTables.AnsActionTypeRejectEntryInitialPayment
              )}
                      """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupAnsInitialPaymentConfirmationByPaymentIdWithOffset(
      confirmer: PartyId,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[
    MultiDomainAcsStore.QueryResult[Option[
      Contract[Confirmation.ContractId, Confirmation]
    ]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            Confirmation.COMPANION,
            where = sql"""
                        confirmer = $confirmer
                    and action_ans_entry_context_payment_id = $paymentId
                      """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAnsInitialPaymentConfirmationByPaymentIdWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(Confirmation.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listInitialPaymentConfirmationByAnsName(
      confirmer: PartyId,
      name: String,
      limit: Limit = defaultLimit,
  )(implicit tc: TraceContext): Future[Seq[Contract[Confirmation.ContractId, Confirmation]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .query(
            selectFromAcsTable(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              Confirmation.COMPANION,
              where = sql"""
                           confirmer = $confirmer
                       and action_ans_entry_context_cid IN (
                         select contract_id
                         from #${DsoTables.acsTableName}
                         where store_id = $acsStoreId
                           and migration_id = $domainMigrationId
                           and template_id_qualified_name = ${QualifiedName(
                  AnsEntryContext.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
                           and ans_entry_name = ${lengthLimited(name)})""",
              orderLimit = sql"""limit ${sqlLimit(limit)}""",
            ),
            "listInitialPaymentConfirmationByAnsName",
          )
        limited = applyLimit("listInitialPaymentConfirmationByAnsName", limit, result)
      } yield limited.map(contractFromRow(Confirmation.COMPANION)(_))
    }

  override def lookupSvOnboardingRequestByTokenWithOffset(
      token: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            SvOnboardingRequest.COMPANION,
            where = sql"""sv_onboarding_token = ${lengthLimited(token)}""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingRequestByTokenWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listSvOnboardingRequestsBySvs(
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules],
      limit: Limit = defaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]] =
    waitUntilAcsIngested {
      import scala.jdk.CollectionConverters.*
      val svCandidates = dsoRules.payload.svs.asScala
        .map { case (party, sv) =>
          sql"(${lengthLimited(party)}, ${lengthLimited(sv.name)})"
        }
        .reduceOption { (acc, next) =>
          (acc ++ sql"," ++ next).toActionBuilder
        }
        .getOrElse(
          throw new IllegalArgumentException("DsoRules is supposed to have at least one sv")
        )
      for {
        result <- storage
          .query(
            selectFromAcsTable(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              SvOnboardingRequest.COMPANION,
              where =
                (sql"""(sv_candidate_party, sv_candidate_name) in (""" ++ svCandidates ++ sql")").toActionBuilder,
              orderLimit = sql"""limit ${sqlLimit(limit)}""",
            ),
            "listSvOnboardingRequestsBySvs",
          )
        limited = applyLimit("listSvOnboardingRequestsBySvs", limit, result)
      } yield limited.map(contractFromRow(SvOnboardingRequest.COMPANION)(_))
    }

  override def listExpiredAmulets(
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None
  ): ListExpiredContracts[splice.amulet.Amulet.ContractId, splice.amulet.Amulet] = {
    val filterClause: TraceContext => Future[SQLActionBuilder] = implicit tc =>
      UnavailablePartiesStore.listParties(unavailablePartiesStore).map { ignoredParties =>
        if (ignoredParties.nonEmpty) {
          (sql" and " ++ notInClause("create_arguments->>'owner'", ignoredParties)).toActionBuilder
        } else {
          sql""
        }
      }
    listExpiredRoundBased(splice.amulet.Amulet.COMPANION, filterClause)
  }

  override def listLockedExpiredAmulets(
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None
  ): ListExpiredContracts[splice.amulet.LockedAmulet.ContractId, splice.amulet.LockedAmulet] = {
    val filterClause: TraceContext => Future[SQLActionBuilder] = implicit tc =>
      UnavailablePartiesStore.listParties(unavailablePartiesStore).map { ignoredParties =>
        if (ignoredParties.nonEmpty) {
          (sql" and " ++ notInClause("create_arguments->'amulet'->>'owner'", ignoredParties) ++
            sql" and not (create_arguments->'lock'->'holders' ??| ${ignoredParties
                .map(p => lengthLimited(p.toProtoPrimitive))
                .toArray: Array[String2066]})").toActionBuilder
        } else {
          sql""
        }
      }
    listExpiredRoundBased(splice.amulet.LockedAmulet.COMPANION, filterClause)
  }

  override def listExpiredAmuletAllocations(
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None
  ): ListExpiredContracts[
    splice.amuletallocation.AmuletAllocation.ContractId,
    splice.amuletallocation.AmuletAllocation,
  ] = (now, limit) =>
    implicit tc => {
      waitUntilAcsIngested {
        for {
          ignoredParties <- UnavailablePartiesStore.listParties(unavailablePartiesStore)
          filterClause =
            if (ignoredParties.nonEmpty) {
              (sql" and " ++ notInClause(
                "create_arguments->'allocation'->'transferLeg'->>'sender'",
                ignoredParties,
              ) ++ sql" and " ++ notInClause(
                "create_arguments->'allocation'->'transferLeg'->>'receiver'",
                ignoredParties,
              )).toActionBuilder
            } else {
              sql""
            }
          synchronizerId <- getDsoRules().map(_.domain)
          rows <- storage.query(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              splice.amuletallocation.AmuletAllocation.COMPANION,
              additionalWhere = (sql"""
                and assigned_domain = $synchronizerId
                and acs.contract_expires_at < ${now}
              """ ++ filterClause).toActionBuilder,
              orderLimit = sql"""limit ${sqlLimit(limit)}""",
            ),
            "listExpiredAmuletAllocations",
          )
        } yield rows.map(
          assignedContractFromRow(
            splice.amuletallocation.AmuletAllocation.COMPANION
          )(_)
        )
      }
    }

  override def listExpiredAmuletAllocationsV2(
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None
  ): ListExpiredContracts[
    splice.amuletallocationv2.AmuletAllocationV2.ContractId,
    splice.amuletallocationv2.AmuletAllocationV2,
  ] = (now, limit) =>
    implicit tc => {
      waitUntilAcsIngested {
        for {
          ignoredParties <- UnavailablePartiesStore.listParties(unavailablePartiesStore)
          // `not (json_array ?| string_array)` means "arrays do not overlap"
          // the first ? is to escape the second
          filterClause =
            if (ignoredParties.nonEmpty) {
              (sql" and " ++ notInClause(
                "create_arguments->'allocation'->'authorizer'->>'owner'",
                ignoredParties,
              ) ++ sql" and not (create_arguments->'settlement'->'executors' ??| ${ignoredParties
                  .map(p => lengthLimited(p.toProtoPrimitive))
                  .toArray[String2066]})").toActionBuilder
            } else {
              sql""
            }
          synchronizerId <- getDsoRules().map(_.domain)
          rows <- storage.query(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              splice.amuletallocationv2.AmuletAllocationV2.COMPANION,
              additionalWhere = (sql"""
                and assigned_domain = $synchronizerId
                and acs.contract_expires_at < ${now}
              """ ++ filterClause).toActionBuilder,
              orderLimit = sql"""limit ${sqlLimit(limit)}""",
            ),
            "listExpiredAmuletAllocationsV2",
          )
        } yield rows.map(
          assignedContractFromRow(
            splice.amuletallocationv2.AmuletAllocationV2.COMPANION
          )(_)
        )
      }
    }

  override def listExpiredAmuletTransferInstructions(
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None
  ): ListExpiredContracts[
    splice.amulettransferinstruction.AmuletTransferInstruction.ContractId,
    splice.amulettransferinstruction.AmuletTransferInstruction,
  ] = (now, limit) =>
    implicit tc => {
      waitUntilAcsIngested {
        for {
          ignoredParties <- UnavailablePartiesStore.listParties(unavailablePartiesStore)
          filterClause =
            if (ignoredParties.nonEmpty) {
              (sql" and " ++ notInClause(
                "create_arguments->'transfer'->>'sender'",
                ignoredParties,
              ) ++ sql" and " ++ notInClause(
                "create_arguments->'transfer'->>'receiver'",
                ignoredParties,
              )).toActionBuilder
            } else {
              sql""
            }
          synchronizerId <- getDsoRules().map(_.domain)
          rows <- storage.query(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION,
              additionalWhere = (sql"""
                and assigned_domain = $synchronizerId
                and acs.contract_expires_at < ${now}
              """ ++ filterClause).toActionBuilder,
              orderLimit = sql"""limit ${sqlLimit(limit)}""",
            ),
            "listExpiredAmuletTransferInstructions",
          )
        } yield rows.map(
          assignedContractFromRow(
            splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION
          )(_)
        )
      }
    }

  private def listExpiredRoundBased[Id <: ContractId[T], T <: javab.Template](
      companion: Template[Id, T],
      extraFilter: TraceContext => Future[SQLActionBuilder],
  ): ListExpiredContracts[Id, T] = (_, limit) =>
    implicit tc => {
      waitUntilAcsIngested {
        for {
          extraFilterClause <- extraFilter(tc)
          synchronizerId <- getDsoRules().map(_.domain)
          rows <- storage.query(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              companion,
              additionalWhere = (sql"""
                and assigned_domain = $synchronizerId
                and acs.amulet_round_of_expiry <= (
                  select mining_round - 2
                  from dso_acs_store
                  where store_id = $acsStoreId
                    and migration_id = $domainMigrationId
                    and package_name = ${splice.round.OpenMiningRound.PACKAGE_NAME}
                    and template_id_qualified_name = ${QualifiedName(
                  splice.round.OpenMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
                  and mining_round is not null
                order by mining_round desc limit 1)
                and coalesce(acs.amulet_round_of_expiry <= (
                  select mining_round
                  from dso_acs_store
                  where store_id = $acsStoreId
                    and migration_id = $domainMigrationId
                    and package_name = ${splice.externalpartyconfigstate.ExternalPartyConfigState.PACKAGE_NAME}
                    and template_id_qualified_name = ${QualifiedName(
                  splice.externalpartyconfigstate.ExternalPartyConfigState.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
                    and mining_round is not null
                  order by mining_round asc limit 1), true)""" ++ extraFilterClause).toActionBuilder,
              orderLimit = sql"""order by mining_round desc limit ${sqlLimit(limit)}""",
            ),
            "listExpiredRoundBased",
          )
          assigned = rows.map(assignedContractFromRow(companion)(_))
        } yield assigned
      }
    }

  override def listExpiredRewardCouponsV2(
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None
  ): ListExpiredContracts[RewardCouponV2.ContractId, RewardCouponV2] = {
    (now, limit) => implicit tc =>
      waitUntilAcsIngested {
        for {
          ignoredParties <- UnavailablePartiesStore.listParties(unavailablePartiesStore)
          // Ignore only if (reward_beneficiary_is_observer = true)
          filterClause =
            if (ignoredParties.nonEmpty) {
              (sql" and (reward_beneficiary_is_observer = false or (" ++
                notInClause("reward_party", ignoredParties) ++
                sql" and (create_arguments->>'beneficiary' is null or " ++
                notInClause("create_arguments->>'beneficiary'", ignoredParties) ++
                sql")))").toActionBuilder
            } else {
              sql""
            }
          result <- storage.query(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              RewardCouponV2.COMPANION,
              additionalWhere =
                (sql"""and acs.contract_expires_at < $now""" ++ filterClause).toActionBuilder,
              orderLimit = sql"""limit ${sqlLimit(limit)}""",
            ),
            "listExpiredRewardCouponsV2",
          )
          limited = applyLimit("listExpiredRewardCouponsV2", limit, result)
        } yield limited.map(assignedContractFromRow(RewardCouponV2.COMPANION)(_))
      }
  }

  override def listMemberTrafficContracts(
      memberId: Member,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[MemberTraffic.ContractId, MemberTraffic]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            MemberTraffic.COMPANION,
            where = sql"""member_traffic_member = $memberId
                      and member_traffic_domain = $synchronizerId""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listMemberTrafficContracts",
        )
    } yield applyLimit("listMemberTrafficContracts", limit, result).map(
      contractFromRow(MemberTraffic.COMPANION)(_)
    )
  }

  override def listNonObserverRewardCouponsV2ProvidersSample(
      limit: Limit
  )(implicit
      tc: TraceContext
  ): Future[Seq[PartyId]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          // To do the `order by random()` the dedup/distinct must be a subquery.
          // Only the reward_party is fetched here to keep the query index-only.
          (sql"""
             select reward_party
             from (
               select distinct on (reward_party) reward_party
               from #${DsoTables.acsTableName}
               where store_id = $acsStoreId
                 and migration_id = $domainMigrationId
                 and package_name = ${RewardCouponV2.PACKAGE_NAME}
                 and template_id_qualified_name = ${QualifiedName(
              RewardCouponV2.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                 and reward_beneficiary_is_observer = false
               order by reward_party
             ) sub
             order by random()
             limit ${sqlLimit(limit)}
           """).as[String],
          "listNonObserverRewardCouponsV2ProvidersSample",
        )
    } yield applyLimit("listNonObserverRewardCouponsV2ProvidersSample", limit, result)
      .map(PartyId.tryFromProtoPrimitive)
  }

  override def listNonObserverRewardCouponsV2ForProvider(
      provider: PartyId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[RewardCouponV2.ContractId, RewardCouponV2]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            RewardCouponV2.COMPANION,
            where =
              sql"reward_beneficiary_is_observer = false and reward_party = ${lengthLimited(provider.toProtoPrimitive)}",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listNonObserverRewardCouponsV2ForProvider",
        )
    } yield applyLimit("listNonObserverRewardCouponsV2ForProvider", limit, result).map(
      contractFromRow(RewardCouponV2.COMPANION)(_)
    )
  }

  override def listTopNonObserverRewardCouponV2Providers(
      couponScanLimit: Int,
      maxProviders: Int,
  )(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, Long)]] = waitUntilAcsIngested {
    for {
      result <- storage
        .query(
          (sql"""
             select reward_party, count(*) as coupon_count
             from (
               select reward_party
               from #${DsoTables.acsTableName}
               where store_id = $acsStoreId
                 and migration_id = $domainMigrationId
                 and package_name = ${RewardCouponV2.PACKAGE_NAME}
                 and template_id_qualified_name = ${QualifiedName(
              RewardCouponV2.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                 and reward_beneficiary_is_observer = false
               limit $couponScanLimit
             ) sub
             group by reward_party
             order by coupon_count desc
             limit $maxProviders
           """).as[(String, Long)],
          "listTopNonObserverRewardCouponV2Providers",
        )
    } yield result.map { case (party, count) =>
      PartyId.tryFromProtoPrimitive(party) -> count
    }
  }

  override def listSvAmuletPriceVotes(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AmuletPriceVote.ContractId, AmuletPriceVote]]] = waitUntilAcsIngested {
    import scala.jdk.CollectionConverters.*
    getDsoRules().flatMap(dsoRules =>
      NonEmpty.from(
        dsoRules.payload.svs.asScala.toMap.map { case (party, _) => lengthLimited(party) }
      ) match {
        case None => Future.successful(Seq.empty)
        case Some(voters) =>
          for {
            dsoRules <- getDsoRules()
            voterParties = DbStorage.toInClause("voter", voters)
            result <- storage
              .query(
                selectFromAcsTable(
                  DsoTables.acsTableName,
                  acsStoreId,
                  domainMigrationId,
                  AmuletPriceVote.COMPANION,
                  where = voterParties,
                  orderLimit = sql"""limit ${sqlLimit(limit)}""",
                ),
                "listSvAmuletPriceVotes",
              )
            limited = applyLimit("listSvAmuletPriceVotes", limit, result)
          } yield limited
            .map(contractFromRow(AmuletPriceVote.COMPANION)(_))
            .distinctBy(_.payload.sv)
      }
    )
  }

  override protected def lookupSvOnboardingRequestByCandidatePartyWithOffset(
      candidateParty: PartyId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            SvOnboardingRequest.COMPANION,
            where = sql"""sv_candidate_party = $candidateParty""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingRequestByCandidatePartyWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupValidatorLicenseWithOffset(
      validator: PartyId
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[ValidatorLicense.ContractId, ValidatorLicense]]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            ValidatorLicense.COMPANION,
            where = sql"""validator = $validator""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupValidatorLicenseWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(ValidatorLicense.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def listValidatorLicensePerValidator(validator: String, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] =
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            ValidatorLicense.COMPANION,
            where = sql"""validator = ${lengthLimited(validator)}""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listValidatorLicensePerValidator",
        )
    } yield result.map(contractFromRow(ValidatorLicense.COMPANION)(_))

  override def getTotalPurchasedMemberTraffic(memberId: Member, synchronizerId: SynchronizerId)(
      implicit tc: TraceContext
  ): Future[Long] = waitUntilAcsIngested {
    for {
      sum <- storage
        .querySingle(
          sql"""
               select sum(total_traffic_purchased)
               from #${DsoTables.acsTableName}
               where store_id = $acsStoreId
                and migration_id = $domainMigrationId
                and package_name = ${MemberTraffic.PACKAGE_NAME}
                and template_id_qualified_name = ${QualifiedName(
              MemberTraffic.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                and member_traffic_member = ${lengthLimited(memberId.toProtoPrimitive)}
                and member_traffic_domain = $synchronizerId
             """.as[Long].headOption,
          "getTotalPurchasedMemberTraffic",
        )
        .value
    } yield sum.getOrElse(0L)
  }

  override def lookupVoteRequest(
      voteRequestCid: VoteRequest.ContractId
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[VoteRequest.ContractId, VoteRequest]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .querySingle(
          lookupVoteRequestQuery(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            "vote_request_tracking_cid",
            voteRequestCid,
          ),
          "lookupVoteRequest",
        )
        .value
    } yield result.map(contractFromRow(VoteRequest.COMPANION)(_))
  }

  override def listVoteRequestsByTrackingCid(
      trackingCids: Seq[VoteRequest.ContractId],
      limit: Limit = defaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] = waitUntilAcsIngested {
    NonEmpty.from(trackingCids) match {
      case None => Future.successful(Seq.empty)
      case Some(trackingCids) =>
        for {
          result <- storage
            .query(
              listVoteRequestsByTrackingCidQuery(
                acsTableName = DsoTables.acsTableName,
                acsStoreId = acsStoreId,
                domainMigrationId = domainMigrationId,
                trackingCidColumnName = "vote_request_tracking_cid",
                trackingCids = trackingCids,
                limit = limit,
              ),
              "listVoteRequestsByTrackingCid",
            )
          records = applyLimit("listVoteRequestsByTrackingCid", limit, result)
        } yield records
          .map(contractFromRow(VoteRequest.COMPANION)(_))
    }
  }

  override def lookupVoteByThisSvAndVoteRequestWithOffset(voteRequestCid: VoteRequest.ContractId)(
      implicit tc: TraceContext
  ): Future[MultiDomainAcsStore.QueryResult[Option[Vote]]] =
    waitUntilAcsIngested {
      (for {
        resultWithOffset <- storage
          .querySingle(
            selectFromAcsTableWithOffset(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              VoteRequest.COMPANION,
              where = sql"""vote_request_tracking_cid = $voteRequestCid""",
              orderLimit = sql"limit 1",
            ).headOption,
            "lookupVoteByThisSvAndVoteRequestWithOffset",
          )
      } yield MultiDomainAcsStore.QueryResult(
        resultWithOffset.offset,
        resultWithOffset.row
          .map(contractFromRow(VoteRequest.COMPANION)(_))
          .flatMap(_.payload.votes.values().asScala.find(_.sv == key.svParty.toProtoPrimitive)),
      )).getOrRaise(offsetExpectedError())
    }

  override def lookupVoteRequestByThisSvAndActionWithOffset(
      action: ActionRequiringConfirmation
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[VoteRequest.ContractId, VoteRequest]]]
  ] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            VoteRequest.COMPANION,
            where = sql"""action_requiring_confirmation = ${payloadJsonFromDefinedDataType(action)}
                       and requester_name = ${key.svParty}
                         """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupVoteRequestByThisSvAndActionWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(VoteRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupAmuletPriceVoteByThisSv()(implicit
      tc: TraceContext
  ): Future[Option[Contract[AmuletPriceVote.ContractId, AmuletPriceVote]]] = waitUntilAcsIngested {
    for {
      result <- storage
        .querySingle(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            AmuletPriceVote.COMPANION,
            where = sql"""voter = ${key.svParty}""",
            orderLimit = sql"""limit 1""",
          ).headOption,
          "lookupAmuletPriceVoteByThisSv",
        )
        .value
    } yield result.map(contractFromRow(AmuletPriceVote.COMPANION)(_))
  }

  override protected def lookupSvOnboardingRequestByCandidateNameWithOffset(
      candidateName: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            SvOnboardingRequest.COMPANION,
            where = sql"""sv_candidate_name = ${lengthLimited(candidateName)}""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingRequestByCandidateNameWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingRequest.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupSvOnboardingConfirmedByNameWithOffset(
      svName: String
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
  ]] = waitUntilAcsIngested {
    (for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            SvOnboardingConfirmed.COMPANION,
            where = sql"""sv_candidate_name = ${lengthLimited(svName)}""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSvOnboardingConfirmedByNameWithOffset",
        )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(SvOnboardingConfirmed.COMPANION)(_)),
    )).getOrRaise(offsetExpectedError())
  }

  override def lookupAnsEntryByNameWithOffset(
      name: String,
      now: CantonTimestamp,
  )(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[AssignedContract[AnsEntry.ContractId, AnsEntry]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            AnsEntry.COMPANION,
            where = sql"""ans_entry_name = ${lengthLimited(name)}
                      and assigned_domain is not null
                      and acs.contract_expires_at >= $now""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAnsEntryByNameWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      assigned = resultWithOffset.row.map(
        assignedContractFromRow(AnsEntry.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def lookupSubscriptionInitialPaymentWithOffset(
      paymentCid: SubscriptionInitialPayment.ContractId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[AssignedContract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment]]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            SubscriptionInitialPayment.COMPANION,
            where = sql"""contract_id = $paymentCid
                      and assigned_domain is not null""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupSubscriptionInitialPaymentWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      assigned = resultWithOffset.row.map(
        assignedContractFromRow(SubscriptionInitialPayment.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def lookupFeaturedAppRightWithOffset(
      providerPartyId: PartyId
  )(implicit tc: TraceContext): Future[MultiDomainAcsStore.QueryResult[
    Option[AssignedContract[FeaturedAppRight.ContractId, FeaturedAppRight]]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            FeaturedAppRight.COMPANION,
            where = sql"""featured_app_right_provider = $providerPartyId
                      and assigned_domain is not null""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupFeaturedAppRightWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      assigned = resultWithOffset.row.map(
        assignedContractFromRow(FeaturedAppRight.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def lookupAnsEntryContext(reference: SubscriptionRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AnsEntryContext.ContractId, AnsEntryContext]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              AnsEntryContext.COMPANION,
              additionalWhere = sql"""and subscription_reference_contract_id = $reference""",
              orderLimit = sql"""limit 1""",
            ).headOption,
            "lookupAnsEntryContext",
          )
          .value
      } yield row.map(contractWithStateFromRow(AnsEntryContext.COMPANION)(_))
    }

  override def listClosedRounds(
      roundNumbers: Set[Long],
      synchronizerId: SynchronizerId,
      limit: Limit = defaultLimit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[splice.round.ClosedMiningRound.ContractId, splice.round.ClosedMiningRound]]
  ] = {
    NonEmpty.from(roundNumbers) match {
      case None => Future.successful(Seq.empty)
      case Some(roundNumbers) =>
        val roundNumbersClause = DbStorage.toInClause("mining_round", roundNumbers)
        waitUntilAcsIngested {
          for {
            result <- storage
              .query(
                selectFromAcsTable(
                  DsoTables.acsTableName,
                  acsStoreId,
                  domainMigrationId,
                  ClosedMiningRound.COMPANION,
                  where =
                    (sql"""assigned_domain = $synchronizerId AND """ ++ roundNumbersClause).toActionBuilder,
                  orderLimit = sql"""limit ${sqlLimit(limit)}""",
                ),
                "listClosedRounds",
              )
          } yield result.map(contractFromRow(ClosedMiningRound.COMPANION)(_))
        }
    }
  }

  def lookupSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[SvNodeState.ContractId, SvNodeState]]] =
    lookupContractBySvParty(SvNodeState.COMPANION, svPartyId)

  override def lookupSvStatusReport(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[SvStatusReport.ContractId, SvStatusReport]]] =
    lookupContractBySvParty(SvStatusReport.COMPANION, svPartyId).map(
      _.map(c =>
        c.toAssignedContract.getOrElse(
          throw Status.FAILED_PRECONDITION
            .withDescription(
              s"Could not convert SvStatusReport ${c.contractId} to AssignedContract as it has state ${c.state}"
            )
            .asRuntimeException
        )
      )
    )

  override def lookupSvRewardState(svName: String)(implicit
      tc: TraceContext
  ): Future[Option[AssignedContract[SvRewardState.ContractId, SvRewardState]]] =
    lookupContractBySvName(SvRewardState.COMPANION, svName)

  override def listSvRewardStates(svName: String, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[SvRewardState.ContractId, SvRewardState]]] =
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            SvRewardState.COMPANION,
            where = sql"""sv_name = ${lengthLimited(svName)}""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listSvRewardStates",
        )
    } yield result.map(contractFromRow(SvRewardState.COMPANION)(_))

  private def lookupContractBySvParty[C, TCId <: ContractId[?], T](
      companion: C,
      svPartyId: PartyId,
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Option[ContractWithState[TCId, T]]] = {
    val templateId = companionClass.typeId(companion)
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              companion,
              additionalWhere = sql"""and sv_party = $svPartyId""",
              orderLimit = sql"""limit 1""",
            ).headOption,
            s"lookupContractBySvParty[$templateId]",
          )
          .value
      } yield row.map(contractWithStateFromRow(companion)(_))
    }
  }

  private def lookupContractBySvName[C, TCId <: ContractId[?], T](
      companion: C,
      svName: String,
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Option[AssignedContract[TCId, T]]] = {
    val templateId = companionClass.typeId(companion)
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              companion,
              additionalWhere = sql"""and sv_name = ${lengthLimited(svName)}""",
              orderLimit = sql"""limit 1""",
            ).headOption,
            s"lookupContractBySvName[$templateId]",
          )
          .value
      } yield row.map(assignedContractFromRow(companion)(_))
    }
  }

  def lookupTransferCommandCounterBySenderWithOffset(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[Option[ContractWithState[
    splice.externalpartyamuletrules.TransferCommandCounter.ContractId,
    splice.externalpartyamuletrules.TransferCommandCounter,
  ]]]] =
    waitUntilAcsIngested {
      (for {
        resultWithOffset <- storage
          .querySingle(
            selectFromAcsTableWithStateAndOffset(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              splice.externalpartyamuletrules.TransferCommandCounter.COMPANION,
              where = sql"""wallet_party = $partyId""",
              orderLimit = sql" limit 1",
            ).headOption,
            "lookupTransferCommandCounterBySender",
          )
      } yield MultiDomainAcsStore.QueryResult(
        resultWithOffset.offset,
        resultWithOffset.row.map(
          contractWithStateFromRow(
            splice.externalpartyamuletrules.TransferCommandCounter.COMPANION
          )(_)
        ),
      )).getOrRaise(offsetExpectedError())
    }

  def listTransferCommandCounterConfirmationBySender(
      confirmer: PartyId,
      partyId: PartyId,
  )(implicit tc: TraceContext): Future[Seq[Contract[
    splice.dsorules.Confirmation.ContractId,
    splice.dsorules.Confirmation,
  ]]] = {
    val expectedAction = new splice.dsorules.actionrequiringconfirmation.ARC_DsoRules(
      new splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateTransferCommandCounter(
        new splice.dsorules.DsoRules_CreateTransferCommandCounter(
          partyId.toProtoPrimitive
        )
      )
    )
    listConfirmationsByActionConfirmer(expectedAction, confirmer)
  }

  override def listExternalPartyAmuletRulesConfirmation(
      confirmer: PartyId
  )(implicit tc: TraceContext): Future[Seq[Contract[
    splice.dsorules.Confirmation.ContractId,
    splice.dsorules.Confirmation,
  ]]] = {
    val expectedAction = new splice.dsorules.actionrequiringconfirmation.ARC_DsoRules(
      new splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateExternalPartyAmuletRules(
        new splice.dsorules.DsoRules_CreateExternalPartyAmuletRules()
      )
    )
    listConfirmationsByActionConfirmer(expectedAction, confirmer)
  }

  override def listCreateBootstrapExternalPartyConfigStateInstructionConfirmation(
      confirmer: PartyId
  )(implicit tc: TraceContext): Future[Seq[Contract[
    splice.dsorules.Confirmation.ContractId,
    splice.dsorules.Confirmation,
  ]]] = {
    val expectedAction = new splice.dsorules.actionrequiringconfirmation.ARC_DsoRules(
      new splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateBootstrapExternalPartyConfigStateInstruction(
        new splice.dsorules.DsoRules_CreateBootstrapExternalPartyConfigStateInstruction()
      )
    )
    listConfirmationsByActionConfirmer(expectedAction, confirmer)
  }

  override def lookupAmuletConversionRateFeed(
      publisher: PartyId
  )(implicit tc: TraceContext): Future[Option[Contract[
    splice.ans.amuletconversionratefeed.AmuletConversionRateFeed.ContractId,
    splice.ans.amuletconversionratefeed.AmuletConversionRateFeed,
  ]]] =
    waitUntilAcsIngested {
      for {
        result <- storage
          .querySingle(
            selectFromAcsTable(
              DsoTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              splice.ans.amuletconversionratefeed.AmuletConversionRateFeed.COMPANION,
              where = sql"""
                    conversion_rate_feed_publisher = $publisher
                  """,
              orderLimit = sql" order by event_number desc limit 1",
            ).headOption,
            "lookupAmuletConversionRateFeed",
          )
          .value
      } yield result.map(
        contractFromRow(splice.ans.amuletconversionratefeed.AmuletConversionRateFeed.COMPANION)(_)
      )
    }

  override def featuredAppActivityMarkerCountAboveOrEqualTo(
      threshold: Int,
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None,
  )(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    waitUntilAcsIngested {
      for {
        ignoredParties <- UnavailablePartiesStore.listParties(unavailablePartiesStore)
        filterClause: SQLActionBuilder =
          if (ignoredParties.nonEmpty) {
            (sql" and " ++ notInClause("create_arguments->>'provider'", ignoredParties) ++
              sql" and " ++ notInClause(
                "create_arguments->>'beneficiary'",
                ignoredParties,
              )).toActionBuilder
          } else {
            sql""
          }
        results <- futureUnlessShutdownToFuture(
          storage
            .query(
              (sql"""
            select count(contract_id) as num_markers
              from (
                select contract_id
                  from dso_acs_store
                 where
                   store_id = $acsStoreId and
                   migration_id = $domainMigrationId and
                   package_name = ${FeaturedAppActivityMarker.PACKAGE_NAME} and
                   template_id_qualified_name = ${QualifiedName(
                  FeaturedAppActivityMarker.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
                 """ ++ filterClause ++ sql"""
                 limit $threshold
              ) as markers;
                   """).toActionBuilder.as[Int],
              "featuredAppActivityMarkerCountAboveOrEqualTo",
            )
        )
      } yield results.contains(threshold)
    }
  }

  override def listFeaturedAppActivityMarkersByContractIdHash(
      contractIdHashLbIncl: Int,
      contractIdHashUbIncl: Int,
      limit: Int,
      unavailablePartiesStore: Option[UnavailablePartiesStore] = None,
  )(implicit tc: TraceContext): Future[Seq[Contract[
    splice.amulet.FeaturedAppActivityMarker.ContractId,
    splice.amulet.FeaturedAppActivityMarker,
  ]]] = {
    for {
      ignoredParties <- UnavailablePartiesStore.listParties(unavailablePartiesStore)
      filterClause =
        if (ignoredParties.nonEmpty) {
          (sql" and " ++ notInClause("create_arguments->>'provider'", ignoredParties) ++
            sql" and " ++ notInClause(
              "create_arguments->>'beneficiary'",
              ignoredParties,
            )).toActionBuilder
        } else {
          sql""
        }
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            FeaturedAppActivityMarker.COMPANION,
            where = (sql"""
                  $contractIdHashLbIncl <= stable_int32_hash(contract_id)
              AND stable_int32_hash(contract_id) <= $contractIdHashUbIncl
            """ ++ filterClause).toActionBuilder,
            orderLimit = sql"""order by stable_int32_hash(contract_id) limit $limit""",
          ),
          "listFeaturedAppActivityMarkersByContractIdHash",
        )
    } yield result.map(contractFromRow(FeaturedAppActivityMarker.COMPANION)(_))
  }

  override def close(): Unit = {
    dsoStoreMetrics.close()
    super.close()
  }
}
