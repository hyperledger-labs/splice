// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.store.{Limit, TcsStore}
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
  Contract,
  ContractWithState,
  PackageQualifiedName,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbScanRewardsReferenceStore(
    override val key: ScanRewardsReferenceStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
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
      acsStoreDescriptor = StoreDescriptor(
        version = 1,
        name = "DbScanRewardsReferenceStore",
        party = key.dsoParty,
        participant = participantId,
        key = Map(
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
          "synchronizerId" -> key.synchronizerId.toProtoPrimitive,
        ),
      ),
      domainMigrationInfo = domainMigrationInfo,
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
    with AcsQueries {

  override def waitUntilInitialized: Future[Unit] = multiDomainAcsStore.waitUntilAcsIngested()

  private val tcsStore = new DbTcsStore(
    multiDomainAcsStore,
    descriptor => SynchronizerId.tryFromString(descriptor.key("synchronizerId")),
  )

  override def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]] = {
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
                .map { r =>
                  recordTime -> (
                    r.contract.payload.round.number.toLong,
                    CantonTimestamp.assertFromInstant(r.contract.payload.opensAt)
                  )
                }
            }.toMap
          }
        }
    }
  }

  override def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]] =
    lookupFeaturedAppRightsAsOf(asOf)
      .map(_.map(_.contract.payload.provider).toSet)

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
}
