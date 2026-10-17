// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.store.db

import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{Member, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsQueries,
  AcsTables,
  DbAppStore,
  MemberTrafficQueries,
  StoreDescriptor,
}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.syncoperator.store.SyncOperatorStore
import org.lfdecentralizedtrust.splice.syncoperator.store.db.SyncOperatorTables.SyncOperatorAcsStoreRowData
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContext, Future}

class DbSyncOperatorStore(
    override val key: SyncOperatorStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    override val defaultLimit: Limit,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = SyncOperatorTables.acsTableName,
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
        version = 1,
        name = "DbSyncOperatorStore",
        party = key.operatorParty,
        participant = participantId,
        key = Map(
          "operatorParty" -> key.operatorParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
          "synchronizerId" -> key.synchronizerId.toProtoPrimitive,
        ),
      ),
      migrationId = domainMigrationId,
      ingestionConfig,
    )
    with AcsTables
    with AcsQueries
    with LimitHelpers
    with MemberTrafficQueries
    with SyncOperatorStore {

  override def dsoPartyId: PartyId = key.dsoParty

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[
    SyncOperatorAcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = SyncOperatorStore.contractFilter(key, logger)

  import multiDomainAcsStore.waitUntilAcsIngested

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId

  override def getTotalPurchasedMemberTraffic(memberId: Member)(implicit
      tc: TraceContext
  ): Future[Long] = waitUntilAcsIngested {
    sumPurchasedMemberTraffic(
      storage,
      SyncOperatorTables.acsTableName,
      acsStoreId,
      domainMigrationId,
      memberId,
      key.synchronizerId,
    )
  }

  override def listTotalPurchasedMemberTraffic()(implicit
      tc: TraceContext
  ): Future[Map[Member, Long]] = waitUntilAcsIngested {
    sumPurchasedMemberTrafficPerMember(
      storage,
      SyncOperatorTables.acsTableName,
      acsStoreId,
      domainMigrationId,
      key.synchronizerId,
    )
  }
}
