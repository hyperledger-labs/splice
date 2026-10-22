// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  FeaturedAppKind,
  FeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.rewards.AssetActivityParams
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers, TcsStore}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsArchiveConfig,
  AcsQueries,
  AcsTables,
  DbAppStore,
  DbTcsStore,
  StoreDescriptor,
}
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.SelectFromAcsTableResult
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  Contract,
  ContractWithState,
  PackageQualifiedName,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object DbScanRewardsReferenceStore {

  // same as `defaultAppActivityWeight` in the daml.
  val DefaultAppActivityWeight: BigDecimal = BigDecimal(1)

  // same as `defaultAppKind` in the daml.
  val DefaultAppKind: FeaturedAppKind = FeaturedAppKind.FAK_GENERAL

  private def appKindPrecedence(kind: FeaturedAppKind): Int = kind match {
    case FeaturedAppKind.FAK_GENERAL => 0
    case FeaturedAppKind.FAK_ASSETMOVING => 1
    case FeaturedAppKind.FAK_ASSET => 2
  }

  private def maxAppKind(a: FeaturedAppKind, b: FeaturedAppKind): FeaturedAppKind =
    if (appKindPrecedence(a) >= appKindPrecedence(b)) a else b
}

class DbScanRewardsReferenceStore(
    override val key: ScanRewardsReferenceStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    migrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    override val defaultLimit: Limit,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = ScanRewardsReferenceTables.acsTableName,
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
        version = 3,
        name = "DbScanRewardsReferenceStore",
        party = key.dsoParty,
        participant = participantId,
        key = Map(
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
          "synchronizerId" -> key.synchronizerId.toProtoPrimitive,
        ),
      ),
      migrationId = migrationId,
      ingestionConfig = ingestionConfig,
      acsArchiveConfigOpt = Some(
        AcsArchiveConfig.withIndexColumns(
          ScanRewardsReferenceTables.archiveTableName,
          ScanRewardsReferenceTables.ScanRewardsReferenceStoreRowData.hasIndexColumns.indexColumnNames,
        )
      ),
    )
    with ScanRewardsReferenceStore
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  override def waitUntilInitialized: Future[Unit] = multiDomainAcsStore.waitUntilAcsIngested()

  private val tcsStore = new DbTcsStore(
    multiDomainAcsStore,
    descriptor => SynchronizerId.tryFromString(descriptor.key("synchronizerId")),
  )

  override def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit
      tc: TraceContext
  ): Future[Map[CantonTimestamp, ScanRewardsReferenceStore.ActiveRoundInfo]] = {
    tcsStore.getEarliestArchivedAt().flatMap {
      case None =>
        Future.successful(Map.empty)
      case Some(ingestionStart) =>
        val afterIngestionStartTimes = recordTimes.filter(_ >= ingestionStart)
        if (afterIngestionStartTimes.isEmpty) Future.successful(Map.empty)
        else {
          val (minTime, maxTime) = afterIngestionStartTimes.foldLeft(
            (CantonTimestamp.MaxValue, CantonTimestamp.MinValue)
          ) { case ((lo, hi), t) => (lo.min(t), hi.max(t)) }
          lookupOpenMiningRoundsActiveWithin(minTime, maxTime).map { activeWithinResult =>
            afterIngestionStartTimes.flatMap { recordTime =>
              val roundsAtTime = TcsStore.contractsActiveAsOf(activeWithinResult, recordTime)
              val openRounds = roundsAtTime.filter { r =>
                CantonTimestamp.assertFromInstant(r.contract.payload.opensAt) <= recordTime
              }
              openRounds
                .minByOption(_.contract.payload.round.number)
                .flatMap { r =>
                  val opensAt = CantonTimestamp.assertFromInstant(r.contract.payload.opensAt)
                  Option.when(opensAt >= ingestionStart) {
                    recordTime -> ScanRewardsReferenceStore.ActiveRoundInfo(
                      roundNumber = r.contract.payload.round.number.toLong,
                      opensAt = opensAt,
                      assetActivityParams = AssetActivityParams
                        .fromRewardConfig(r.contract.payload.rewardConfig.toScala),
                    )
                  }
                }
            }.toMap
          }
        }
    }
  }

  override def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[ScanRewardsReferenceStore.FeaturedAppParties] =
    lookupFeaturedAppRightsAsOf(asOf).map { rights =>
      // Combine all the FeaturedAppRights of a provider
      val perProvider = rights.foldLeft(
        Map.empty[String, (BigDecimal, FeaturedAppKind, Set[String])]
      ) { (acc, right) =>
        val payload = right.contract.payload
        val weight = payload.activityWeight.toScala
          .map(BigDecimal(_))
          .getOrElse(DbScanRewardsReferenceStore.DefaultAppActivityWeight)
        val kind = payload.appKind.toScala.getOrElse(DbScanRewardsReferenceStore.DefaultAppKind)
        val opsParties = payload.opsParties.toScala.fold(Set.empty[String])(_.asScala.toSet)
        acc.updated(
          payload.provider,
          acc.get(payload.provider).fold((weight, kind, opsParties)) {
            case (prevWeight, prevKind, prevOpsParties) =>
              (
                // Use the max of all weights and kind, in case multiple contracts exist for a party
                prevWeight max weight,
                DbScanRewardsReferenceStore.maxAppKind(prevKind, kind),
                prevOpsParties ++ opsParties,
              )
          },
        )
      }
      ScanRewardsReferenceStore.FeaturedAppParties(
        providers = perProvider.view.mapValues { case (weight, kind, _) => (kind, weight) }.toMap,
        opsPartyProviders = perProvider.foldLeft(Map.empty[String, String]) {
          case (acc, (provider, (_, _, opsParties))) =>
            opsParties.foldLeft(acc) { (innerAcc, opsParty) =>
              innerAcc.get(opsParty) match {
                // On conflict keep the lexicographically lowest provider
                // TODO: consider emitting a warning on conflict
                case Some(existingProvider) if existingProvider <= provider => innerAcc
                case _ => innerAcc.updated(opsParty, provider)
              }
            }
        },
      )
    }

  override def lookupSvParticipantIdsAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]] =
    tcsStore.listAllContractsAsOf(DsoRules.COMPANION, asOf, limit = Some(2)).map {
      case Seq() => Set.empty[String]
      case Seq(c) =>
        import scala.jdk.CollectionConverters.*
        c.contract.payload.svs.values().asScala.toSet[splice.dsorules.SvInfo].map { info =>
          // svInfo.participantId is in the proto-primitive form accepted by
          // ParticipantId.tryFromProtoPrimitive (with `PAR::` prefix), while
          // verdict.submittingParticipantUid carries only the UID portion.
          // Normalize to the latter for direct comparison.
          ParticipantId.tryFromProtoPrimitive(info.participantId).uid.toProtoPrimitive
        }
      case multiple =>
        throw new IllegalStateException(
          s"Expected at most one active DsoRules contract as of $asOf, but found ${multiple.size}: " +
            multiple.map(_.contract.contractId.contractId).mkString(", ")
        )
    }

  override def lookupAmuletConfigAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[AmuletConfig[USD]]] =
    tcsStore.listAllContractsAsOf(AmuletRules.COMPANION, asOf, limit = Some(2)).map {
      case Seq() => None
      case Seq(c) => Some(AmuletConfigSchedule(c.contract).getConfigAsOf(asOf))
      case multiple =>
        throw new IllegalStateException(
          s"Expected at most one active AmuletRules contract as of $asOf, but found ${multiple.size}: " +
            multiple.map(_.contract.contractId.contractId).mkString(", ")
        )
    }

  def lookupOpenMiningRoundsActiveWithin(
      lowerBoundIncl: CantonTimestamp,
      upperBoundIncl: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[TcsStore.TemporalContractWithState[OpenMiningRound.ContractId, OpenMiningRound]]
  ] =
    tcsStore.listAllContractsActiveWithin(
      OpenMiningRound.COMPANION,
      lowerBoundIncl,
      upperBoundIncl,
    )

  def lookupFeaturedAppRightsAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    tcsStore.listAllContractsAsOf(FeaturedAppRight.COMPANION, asOf)

  def lookupOpenMiningRoundsAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]]] =
    tcsStore.listAllContractsAsOf(OpenMiningRound.COMPANION, asOf)

  override def lookupOpenMiningRoundByNumber(
      roundNumber: Long
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[OpenMiningRound.ContractId, OpenMiningRound]]] =
    waitUntilInitialized.flatMap { _ =>
      lookupOpenMiningRoundByNumberQuery(roundNumber)
    }

  private def lookupOpenMiningRoundByNumberQuery(
      roundNumber: Long
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[OpenMiningRound.ContractId, OpenMiningRound]]] = {
    val storeId = multiDomainAcsStore.acsStoreId
    val migrationId = multiDomainAcsStore.domainMigrationId
    val pqn = PackageQualifiedName.fromJavaCodegenCompanion(OpenMiningRound.COMPANION)
    val columns = SelectFromAcsTableResult.sqlColumnsCommaSeparated()
    val query =
      sql"""(
         select #$columns
         from #${ScanRewardsReferenceTables.acsTableName} acs
         where acs.store_id = $storeId
           and acs.migration_id = $migrationId
           and acs.package_name = ${pqn.packageName}
           and acs.template_id_qualified_name = ${pqn.qualifiedName}
           and acs.round = $roundNumber
       ) union all (
         select #$columns
         from #${ScanRewardsReferenceTables.archiveTableName} acs
         where acs.store_id = $storeId
           and acs.migration_id = $migrationId
           and acs.package_name = ${pqn.packageName}
           and acs.template_id_qualified_name = ${pqn.qualifiedName}
           and acs.round = $roundNumber
       ) limit 1""".as[SelectFromAcsTableResult]
    for {
      result <- futureUnlessShutdownToFuture(
        storage.query(query, "lookupOpenMiningRoundByNumber")
      )
    } yield result.headOption.map(contractFromRow(OpenMiningRound.COMPANION)(_))
  }

  override def lookupLatestArchivedOpenMiningRound(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[Long]] =
    tcsStore.getEarliestArchivedAt().flatMap {
      case Some(earliestArchivedAt) if asOf >= earliestArchivedAt =>
        multiDomainAcsStore.waitUntilRecordTimeReached(key.synchronizerId, asOf).flatMap { _ =>
          val storeId = multiDomainAcsStore.acsStoreId
          val migrationId = multiDomainAcsStore.domainMigrationId
          val pqn = PackageQualifiedName.fromJavaCodegenCompanion(OpenMiningRound.COMPANION)
          // Note: Ordering by archived_at (instead of max(round)) lets us use the
          // existing archived_temporal index
          futureUnlessShutdownToFuture(
            storage.query(
              sql"""select acs.round
                from #${ScanRewardsReferenceTables.archiveTableName} acs
                where acs.store_id = $storeId
                  and acs.migration_id = $migrationId
                  and acs.package_name = ${pqn.packageName}
                  and acs.template_id_qualified_name = ${pqn.qualifiedName}
                  and acs.archived_at <= $asOf
                order by acs.archived_at desc
                limit 1
           """.as[Option[Long]].headOption.map(_.flatten),
              "lookupLatestArchivedOpenMiningRound",
            )
          )
        }
      case _ => Future.successful(None)
    }

  override def lookupArchivedAtForOpenMiningRound(
      roundNumber: Long
  )(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
    waitUntilInitialized.flatMap { _ =>
      val storeId = multiDomainAcsStore.acsStoreId
      val migrationId = multiDomainAcsStore.domainMigrationId
      val pqn = PackageQualifiedName.fromJavaCodegenCompanion(OpenMiningRound.COMPANION)
      futureUnlessShutdownToFuture(
        storage.query(
          sql"""select acs.archived_at
                from #${ScanRewardsReferenceTables.archiveTableName} acs
                where acs.store_id = $storeId
                  and acs.migration_id = $migrationId
                  and acs.package_name = ${pqn.packageName}
                  and acs.template_id_qualified_name = ${pqn.qualifiedName}
                  and acs.round = $roundNumber
                limit 1
           """.as[CantonTimestamp].headOption,
          "lookupArchivedAtForOpenMiningRound",
        )
      )
    }

  override def lookupLowestPrunableArchivedRewardRound()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    waitUntilInitialized.flatMap { _ =>
      val storeId = multiDomainAcsStore.acsStoreId
      val migrationId = multiDomainAcsStore.domainMigrationId
      // It is important to limit the query for the round to OpenMiningRound
      // as we prune only the contracts <= OpenMiningRound's archived_at
      // and the db will likely still have other contracts for this round
      // even after the pruning.
      val pqn = PackageQualifiedName.fromJavaCodegenCompanion(OpenMiningRound.COMPANION)
      futureUnlessShutdownToFuture(
        storage.query(
          sql"""select min(archived.round)
                from #${ScanRewardsReferenceTables.archiveTableName} archived
                where archived.store_id = $storeId and archived.migration_id = $migrationId
                  and archived.package_name = ${pqn.packageName}
                  and archived.template_id_qualified_name = ${pqn.qualifiedName}
                  and not exists (
                    select 1
                    from #${ScanRewardsReferenceTables.acsTableName} active
                    where active.store_id = archived.store_id
                      and active.migration_id = archived.migration_id
                      and active.round <= archived.round
                  )
           """.as[Option[Long]].head,
          "lookupLowestPrunableArchivedRewardRound",
        )
      )
    }

  // Returns number of rows deleted
  override def pruneArchivedUpToRound(
      roundNumber: Long
  )(implicit tc: TraceContext): Future[Long] =
    lookupArchivedAtForOpenMiningRound(roundNumber).flatMap {
      case None =>
        Future.failed(
          new IllegalStateException(
            s"Cannot prune archived data for round $roundNumber: its OpenMiningRound has not been observed as archived."
          )
        )
      case Some(uptoInclusive) => tcsStore.pruneArchivedUpTo(uptoInclusive)
    }

  override def listActiveCalculateRewardsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]] =
    for {
      _ <- waitUntilInitialized
      result <- futureUnlessShutdownToFuture(
        storage.query(
          selectFromAcsTable(
            ScanRewardsReferenceTables.acsTableName,
            multiDomainAcsStore.acsStoreId,
            multiDomainAcsStore.domainMigrationId,
            CalculateRewardsV2.COMPANION,
            orderLimit = sql"""order by acs.round asc limit ${sqlLimit(limit)}""",
          ),
          "listActiveCalculateRewardsV2",
        )
      )
      limited = applyLimit("listActiveCalculateRewardsV2", limit, result)
    } yield limited.map(contractFromRow(CalculateRewardsV2.COMPANION)(_))

  override def listActiveCalculateRewardsV2ForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]] =
    for {
      _ <- waitUntilInitialized
      result <- futureUnlessShutdownToFuture(
        storage.query(
          selectFromAcsTable(
            ScanRewardsReferenceTables.acsTableName,
            multiDomainAcsStore.acsStoreId,
            multiDomainAcsStore.domainMigrationId,
            CalculateRewardsV2.COMPANION,
            where = sql"""acs.round = $roundNumber""",
          ),
          "listActiveCalculateRewardsV2ForRound",
        )
      )
    } yield result.map(contractFromRow(CalculateRewardsV2.COMPANION)(_))
}
