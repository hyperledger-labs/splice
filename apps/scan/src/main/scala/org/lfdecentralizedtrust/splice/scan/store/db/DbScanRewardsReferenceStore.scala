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
import org.lfdecentralizedtrust.splice.store.db.{DbAppStore, DbMultiDomainAcsStore, StoreDescriptor}
import org.lfdecentralizedtrust.splice.util.{ContractWithState, TemplateJsonDecoder}

import scala.concurrent.{ExecutionContext, Future}

class DbScanRewardsReferenceStore(
    override val key: ScanRewardsReferenceStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    synchronizerId: SynchronizerId,
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
          "dsoParty" -> key.dsoParty.toProtoPrimitive
        ),
      ),
      domainMigrationInfo = domainMigrationInfo,
      ingestionConfig = ingestionConfig,
      acsArchiveConfigOpt = Some(
        DbMultiDomainAcsStore.AcsArchiveConfig.withIndexColumns(
          ScanRewardsReferenceTables.archiveTableName,
          ScanRewardsReferenceTables.ScanRewardsReferenceStoreRowData.hasIndexColumns.indexColumnNames,
        )
      ),
    )
    with ScanRewardsReferenceStore {

  def lookupOpenMiningRoundsActiveWithin(
      lowerBoundIncl: CantonTimestamp,
      upperBoundIncl: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[TcsStore.TemporalContractWithState[OpenMiningRound.ContractId, OpenMiningRound]]
  ] =
    multiDomainAcsStore.listAllContractsActiveWithin(
      OpenMiningRound.COMPANION,
      lowerBoundIncl,
      upperBoundIncl,
      synchronizerId,
    )

  def lookupFeaturedAppRightsAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    multiDomainAcsStore.listAllContractsAsOf(FeaturedAppRight.COMPANION, asOf, synchronizerId)

  def lookupOpenMiningRoundsAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]]] =
    multiDomainAcsStore.listAllContractsAsOf(OpenMiningRound.COMPANION, asOf, synchronizerId)
}
