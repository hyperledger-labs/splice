// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import cats.data.OptionT
import cats.implicits.*
import com.daml.ledger.javaapi.data as javab
import com.daml.ledger.javaapi.data.codegen.ContractId
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
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
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

import scala.jdk.CollectionConverters.*
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class DbSvDsoStore(
    override val key: SvStore.Key,
    storage: DbStorage,
    override protected val outerLoggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    acsStoreDescriptorUserVersion: Option[Long] = None,
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
      domainMigrationInfo,
      ingestionConfig,
    )
    with SvDsoStore
    with AcsTables
    with AcsQueries
    with AcsJdbcTypes
    with DbVotesAcsStoreQueryBuilder
    with LimitHelpers {
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

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

  override def domainMigrationId: Long = domainMigrationInfo.currentMigrationId

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId

  override def listExpiredAnsSubscriptions(
      now: CantonTimestamp,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[SvDsoStore.IdleAnsSubscription]] = waitUntilAcsIngested {
    for {
      joinedRows <- storage
        .query(
          sql"""
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
              order by idle.subscription_next_payment_due_at
              limit    ${sqlLimit(limit)}
          """.as[(SelectFromAcsTableResult, SelectFromAcsTableResult)],
          "listExpiredAnsSubscriptions",
        )
    } yield applyLimit("listExpiredAnsSubscriptions", limit, joinedRows).map {
      case (idleRow, ctxRow) =>
        val idleContract = contractFromRow(SubscriptionIdleState.COMPANION)(idleRow)
        val ctxContract = contractFromRow(AnsEntryContext.COMPANION)(ctxRow)
        SvDsoStore.IdleAnsSubscription(idleContract, ctxContract)
    }
  }

  override def listSvOnboardingConfirmed(limit: Limit)(implicit
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
      limit: Limit = Limit.DefaultLimit,
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
      totalCouponsLimit: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[AppRewardCoupon.ContractId]]] =
    listCouponsGroupedByRound(
      AppRewardCoupon.COMPANION,
      domain,
      totalCouponsLimit,
      ignoredParties,
    )

  override def listValidatorRewardCouponsGroupedByRound(
      domain: SynchronizerId,
      totalCouponsLimit: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[ValidatorRewardCoupon.ContractId]]] =
    listCouponsGroupedByRound(
      ValidatorRewardCoupon.COMPANION,
      domain,
      totalCouponsLimit,
      ignoredParties,
    )

  override def listValidatorFaucetCouponsGroupedByRound(
      domain: SynchronizerId,
      totalCouponsLimit: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[ValidatorFaucetCoupon.ContractId]]] =
    listCouponsGroupedByRound(
      ValidatorFaucetCoupon.COMPANION,
      domain,
      totalCouponsLimit,
      ignoredParties,
    )

  override def listValidatorLivenessActivityRecordsGroupedByRound(
      domain: SynchronizerId,
      totalCouponsLimit: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      tc: TraceContext
  ): Future[Seq[RoundBatch[ValidatorLivenessActivityRecord.ContractId]]] =
    listCouponsGroupedByRound(
      ValidatorLivenessActivityRecord.COMPANION,
      domain,
      totalCouponsLimit,
      ignoredParties,
    )

  override def listSvRewardCouponsGroupedByRound(
      domain: SynchronizerId,
      totalCouponsLimit: Limit,
      ignoredParties: Set[PartyId],
  )(implicit tc: TraceContext): Future[Seq[RoundBatch[SvRewardCoupon.ContractId]]] =
    listCouponsGroupedByRound(
      SvRewardCoupon.COMPANION,
      domain,
      totalCouponsLimit,
      ignoredParties,
    )

  private def listCouponsGroupedByRound[C, TCId <: ContractId[?]: ClassTag, T](
      companion: C,
      domain: SynchronizerId,
      totalCouponsLimit: Limit,
      ignoredParties: Set[PartyId],
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Seq[SvDsoStore.RoundBatch[TCId]]] = {
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
                select reward_round, array_agg(contract_id)
                from dso_acs_store
                where store_id = $acsStoreId
                  and migration_id = $domainMigrationId
                  and package_name = ${packageQualifiedName.packageName}
                  and template_id_qualified_name = ${packageQualifiedName.qualifiedName}
                  and assigned_domain = $domain
                  and reward_party is not null -- otherwise index is not used
                  and reward_round is not null -- otherwise index is not used
                  """ ++ partyFilter ++ sql"""
                group by reward_round
                order by reward_round asc
                limit ${sqlLimit(totalCouponsLimit)}
               """).toActionBuilder.as[(Long, Array[ContractId[ValidatorRewardCoupon]])],
            opName,
          )
      } yield applyLimit(opName, totalCouponsLimit, result)
        .map { case (round, batch) =>
          RoundBatch(
            round,
            batch
              .take(totalCouponsLimit.limit)
              .map(cid => companionClass.toContractId(companion, cid.contractId))
              .toSeq,
          )
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

  override def listOldestSummarizingMiningRounds(limit: Limit = Limit.DefaultLimit)(implicit
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
      limit: Limit = Limit.DefaultLimit,
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
      limit: Limit = Limit.DefaultLimit,
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
      ignoredParties: Set[PartyId]
  ): ListExpiredContracts[splice.amulet.Amulet.ContractId, splice.amulet.Amulet] = {
    val filterClause = if (ignoredParties.nonEmpty) {
      (sql" and " ++ notInClause("create_arguments->>'owner'", ignoredParties)).toActionBuilder
    } else {
      sql""
    }
    listExpiredRoundBased(splice.amulet.Amulet.COMPANION, filterClause)
  }

  override def listLockedExpiredAmulets(
      ignoredParties: Set[PartyId]
  ): ListExpiredContracts[splice.amulet.LockedAmulet.ContractId, splice.amulet.LockedAmulet] = {
    val filterClause = if (ignoredParties.nonEmpty) {
      (sql" and " ++ notInClause("create_arguments->'amulet'->>'owner'", ignoredParties) ++
        sql" and not (create_arguments->'lock'->'holders' ??| ${ignoredParties
            .map(p => lengthLimited(p.toProtoPrimitive))
            .toArray: Array[String2066]})").toActionBuilder
    } else {
      sql""
    }
    listExpiredRoundBased(splice.amulet.LockedAmulet.COMPANION, filterClause)
  }

  private def listExpiredRoundBased[Id <: ContractId[T], T <: javab.Template](
      companion: Template[Id, T],
      extraFilter: SQLActionBuilder,
  ): ListExpiredContracts[Id, T] = (_, limit) =>
    implicit tc => {
      waitUntilAcsIngested {
        for {
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
                  order by mining_round desc limit 1)""" ++ extraFilter).toActionBuilder,
              orderLimit = sql"""order by mining_round desc limit ${sqlLimit(limit)}""",
            ),
            "listExpiredRoundBased",
          )
          assigned = rows.map(assignedContractFromRow(companion)(_))
        } yield assigned
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

  override def listSvAmuletPriceVotes(limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[AmuletPriceVote.ContractId, AmuletPriceVote]]] = waitUntilAcsIngested {
    import scala.jdk.CollectionConverters.*
    for {
      dsoRules <- getDsoRules()
      voterParties = inClause(
        "voter",
        dsoRules.payload.svs.asScala
          .map { case (party, _) =>
            lengthLimited(party)
          },
      )
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
    } yield limited.map(contractFromRow(AmuletPriceVote.COMPANION)(_)).distinctBy(_.payload.sv)
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
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] = waitUntilAcsIngested {
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
      limit: Limit,
  )(implicit tc: TraceContext): Future[
    Seq[Contract[splice.round.ClosedMiningRound.ContractId, splice.round.ClosedMiningRound]]
  ] = {
    if (roundNumbers.isEmpty)
      Future.successful(Seq.empty)
    else {
      val roundNumbersClause = inClause("mining_round", roundNumbers)
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

  override def featuredAppActivityMarkerCountAboveOrEqualTo(threshold: Int)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    waitUntilAcsIngested {
      futureUnlessShutdownToFuture(
        storage
          .query(
            sql"""
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
                 limit $threshold
              ) as markers;
                   """.toActionBuilder.as[Int],
            "featuredAppActivityMarkerCountAboveOrEqualTo",
          )
      ).map(results => results.contains(threshold))
    }

  override def listFeaturedAppActivityMarkersByContractIdHash(
      contractIdHashLbIncl: Int,
      contractIdHashUbIncl: Int,
      limit: Int,
  )(implicit tc: TraceContext): Future[Seq[Contract[
    splice.amulet.FeaturedAppActivityMarker.ContractId,
    splice.amulet.FeaturedAppActivityMarker,
  ]]] =
    for {
      result <- storage
        .query(
          selectFromAcsTable(
            DsoTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            FeaturedAppActivityMarker.COMPANION,
            where = sql"""
                  $contractIdHashLbIncl <= stable_int32_hash(contract_id)
              AND stable_int32_hash(contract_id) <= $contractIdHashUbIncl
            """,
            orderLimit = sql"""order by stable_int32_hash(contract_id) limit $limit""",
          ),
          "listFeaturedAppActivityMarkersByContractIdHash",
        )
    } yield result.map(contractFromRow(FeaturedAppActivityMarker.COMPANION)(_))

  override def close(): Unit = {
    dsoStoreMetrics.close()
    super.close()
  }
}
