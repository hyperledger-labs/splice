// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store.db

import com.daml.network.environment.RetryProvider
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.*
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.ParticipantId

import scala.concurrent.ExecutionContext

abstract class DbTxLogAppStore[TXE](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    storeDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    enableissue12777Workaround: Boolean,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = acsTableName,
      storeDescriptor = storeDescriptor,
      domainMigrationInfo = domainMigrationInfo,
      participantId = participantId,
      enableissue12777Workaround = enableissue12777Workaround,
    )
    with TxLogAppStore[TXE] {

  override val multiDomainAcsStore: DbMultiDomainAcsStore[TXE] =
    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      Some(txLogTableName),
      storeDescriptor,
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
    storeDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    enableissue12777Workaround: Boolean,
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
      storeDescriptor,
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
      storeDescriptor.name,
      participantId,
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      enableissue12777Workaround,
    )

  override def close(): Unit = {
    multiDomainAcsStore.close()
  }
}
