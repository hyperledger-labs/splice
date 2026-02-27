// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.{ScanStore, ScanTcsStore}
import org.lfdecentralizedtrust.splice.store.{Limit, TcsStore}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.db.{DbAppStore, DbMultiDomainAcsStore, StoreDescriptor}
import org.lfdecentralizedtrust.splice.util.{ContractWithState, TemplateJsonDecoder}

import scala.concurrent.{ExecutionContext, Future}

class DbScanTcsStore(
    override val key: ScanStore.Key,
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
      acsTableName = ScanTcsTables.acsTableName,
      interfaceViewsTableNameOpt = None,
      acsStoreDescriptor = StoreDescriptor(
        version = 1,
        name = "DbScanTcsStore",
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
          ScanTcsTables.archiveTableName,
          ScanTcsTables.ScanTcsStoreRowData.hasIndexColumns.indexColumnNames,
        )
      ),
    )
    with ScanTcsStore
    with TcsStore {

  override def lookupContractByIdAsOf[C, TCid <: ContractId[?], T](
      companion: C
  )(id: ContractId[?], asOf: CantonTimestamp, synchronizerId: SynchronizerId)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] =
    multiDomainAcsStore.lookupContractByIdAsOf(companion)(id, asOf, synchronizerId)

  override def listContractsAsOf[C, TCid <: ContractId[?], T](
      companion: C,
      asOf: CantonTimestamp,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]] =
    multiDomainAcsStore.listContractsAsOf(companion, asOf, synchronizerId, limit)

  def lookupFeaturedAppRightsAsOf(
      asOf: CantonTimestamp,
      limit: Limit = defaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    listContractsAsOf(FeaturedAppRight.COMPANION, asOf, synchronizerId, limit)

  def lookupOpenMiningRoundsAsOf(
      asOf: CantonTimestamp,
      limit: Limit = defaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]]] =
    listContractsAsOf(OpenMiningRound.COMPANION, asOf, synchronizerId, limit)

  def lookupAmuletRulesAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]] =
    listContractsAsOf(AmuletRules.COMPANION, asOf, synchronizerId, defaultLimit).map(_.headOption)
}
