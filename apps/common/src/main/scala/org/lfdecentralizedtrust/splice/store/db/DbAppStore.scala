// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.ParticipantId

import scala.concurrent.ExecutionContext

abstract class DbTxLogAppStore[TXE](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    acsStoreDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    txLogStoreDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    enableissue12777Workaround: Boolean,
    oHistoryMetrics: Option[HistoryMetrics] = None,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = acsTableName,
      acsStoreDescriptor = acsStoreDescriptor,
      domainMigrationInfo = domainMigrationInfo,
      participantId = participantId,
      enableissue12777Workaround = enableissue12777Workaround,
      oHistoryMetrics = oHistoryMetrics,
    )
    with TxLogAppStore[TXE] {

  override val multiDomainAcsStore: DbMultiDomainAcsStore[TXE] =
    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      Some(txLogTableName),
      acsStoreDescriptor,
      Some(txLogStoreDescriptor),
      loggerFactory,
      acsContractFilter,
      txLogConfig,
      domainMigrationInfo,
      participantId,
      retryProvider,
      handleIngestionSummary,
    )
}

abstract class DbAppStore(
    storage: DbStorage,
    acsTableName: String,
    acsStoreDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    enableissue12777Workaround: Boolean,
    oHistoryMetrics: Option[HistoryMetrics] = None,
)(implicit
    protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends AppStore {

  protected def retryProvider: RetryProvider
  final protected def futureSupervisor: FutureSupervisor = retryProvider.futureSupervisor

  protected def handleIngestionSummary(summary: IngestionSummary): Unit = ()

  override val multiDomainAcsStore: DbMultiDomainAcsStore[?] =
    new DbMultiDomainAcsStore[Nothing](
      storage,
      acsTableName,
      None,
      acsStoreDescriptor,
      None,
      loggerFactory,
      acsContractFilter,
      TxLogStore.Config.empty,
      domainMigrationInfo,
      participantId,
      retryProvider,
      handleIngestionSummary,
    )

  override lazy val domains: InMemoryDomainStore =
    new InMemoryDomainStore(
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      retryProvider,
    )

  override lazy val updateHistory: UpdateHistory =
    new UpdateHistory(
      storage,
      domainMigrationInfo,
      acsStoreDescriptor.name,
      participantId,
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      enableissue12777Workaround,
      oHistoryMetrics,
    )

  override def close(): Unit = {
    multiDomainAcsStore.close()
  }
}
