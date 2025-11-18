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
import org.lfdecentralizedtrust.splice.config.IngestionConfig

import scala.concurrent.ExecutionContext

abstract class DbTxLogAppStore[TXE](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    interfaceViewsTableNameOpt: Option[String],
    acsStoreDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    txLogStoreDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    ingestionConfig: IngestionConfig,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = acsTableName,
      interfaceViewsTableNameOpt = interfaceViewsTableNameOpt,
      acsStoreDescriptor = acsStoreDescriptor,
      domainMigrationInfo = domainMigrationInfo,
      ingestionConfig = ingestionConfig,
    )
    with TxLogAppStore[TXE] {

  override val multiDomainAcsStore: DbMultiDomainAcsStore[TXE] =
    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      Some(txLogTableName),
      interfaceViewsTableNameOpt,
      acsStoreDescriptor,
      Some(txLogStoreDescriptor),
      loggerFactory,
      acsContractFilter,
      txLogConfig,
      domainMigrationInfo,
      retryProvider,
      ingestionConfig,
      handleIngestionSummary,
    )
}

abstract class DbAppStore(
    storage: DbStorage,
    acsTableName: String,
    interfaceViewsTableNameOpt: Option[String],
    acsStoreDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    ingestionConfig: IngestionConfig,
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
      interfaceViewsTableNameOpt,
      acsStoreDescriptor,
      None,
      loggerFactory,
      acsContractFilter,
      TxLogStore.Config.empty,
      domainMigrationInfo,
      retryProvider,
      ingestionConfig,
      handleIngestionSummary,
    )

  override lazy val storeName: String = multiDomainAcsStore.storeName

  override lazy val domains: InMemorySynchronizerStore =
    new InMemorySynchronizerStore(
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      retryProvider,
    )

  override def close(): Unit = {
    multiDomainAcsStore.close()
  }
}
