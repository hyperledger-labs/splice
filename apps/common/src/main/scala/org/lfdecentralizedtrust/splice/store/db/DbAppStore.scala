// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.tracing.TraceContext

import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

abstract class DbTxLogAppStore[TXE](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    interfaceViewsTableNameOpt: Option[String],
    acsStoreDescriptor: StoreDescriptor,
    txLogStoreDescriptor: StoreDescriptor,
    storeIds: DbMultiDomainAcsStore.StoreIds,
    migrationId: Long,
    ingestionConfig: IngestionConfig,
    acsArchiveConfigOpt: Option[AcsArchiveConfig] = None,
    additionalIngestionSinks: Seq[AdditionalIngestionSink] = Seq.empty,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = acsTableName,
      interfaceViewsTableNameOpt = interfaceViewsTableNameOpt,
      acsStoreDescriptor = acsStoreDescriptor,
      storeIds = storeIds,
      migrationId = migrationId,
      ingestionConfig = ingestionConfig,
      acsArchiveConfigOpt = acsArchiveConfigOpt,
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
      storeIds,
      loggerFactory,
      acsContractFilter,
      txLogConfig,
      migrationId,
      retryProvider,
      ingestionConfig,
      handleIngestionSummary,
      defaultLimit = defaultLimit,
      acsArchiveConfigOpt = acsArchiveConfigOpt,
      additionalIngestionSinks = additionalIngestionSinks,
    )
}

abstract class DbAppStore(
    storage: DbStorage,
    acsTableName: String,
    interfaceViewsTableNameOpt: Option[String],
    acsStoreDescriptor: StoreDescriptor,
    storeIds: DbMultiDomainAcsStore.StoreIds,
    migrationId: Long,
    ingestionConfig: IngestionConfig,
    acsArchiveConfigOpt: Option[AcsArchiveConfig] = None,
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
      storeIds,
      loggerFactory,
      acsContractFilter,
      TxLogStore.Config.empty,
      migrationId,
      retryProvider,
      ingestionConfig,
      handleIngestionSummary,
      defaultLimit,
      acsArchiveConfigOpt = acsArchiveConfigOpt,
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

object DbAppStore {

  def getHighestKnownMigrationId(
      storage: DbStorage
  )(implicit
      ec: ExecutionContext,
      closeContext: CloseContext,
      tc: TraceContext,
  ): Future[Option[Long]] = {
    val queryResult = storage.query(
      sql"""select max(migration_id) from store_last_ingested_offsets""".as[Option[Long]],
      "getHighestKnownMigrationId",
    )
    queryResult.map(_.headOption.flatten)
  }
}
