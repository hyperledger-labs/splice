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

abstract class DbCNNodeAppStore[TXE](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    storeDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    storeUpdateHistory: Boolean,
)(implicit
    protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends CNNodeAppStore[TXE] {

  protected def retryProvider: RetryProvider
  final protected def futureSupervisor: FutureSupervisor = retryProvider.futureSupervisor

  protected def handleIngestionSummary(summary: IngestionSummary): Unit = ()

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

  override lazy val domains: InMemoryDomainStore =
    new InMemoryDomainStore(
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      retryProvider,
    )

  // Note: everything deriving from this class has a TxLog, but not all apps need to persist the original
  // update history. For example, both the SV and Scan apps have a TxLog based on the DSO party, but we
  // only want one of them to be responsible for persisting the original update history.
  override lazy val updateHistory: Option[UpdateHistory] =
    if (storeUpdateHistory)
      Some(
        new UpdateHistory(
          storage,
          domainMigrationInfo,
          participantId,
          acsContractFilter.ingestionFilter.primaryParty,
          loggerFactory,
        )
      )
    else None

  override def close(): Unit = ()
}

abstract class DbCNNodeAppStoreWithoutHistory(
    storage: DbStorage,
    acsTableName: String,
    storeDescriptor: DbMultiDomainAcsStore.StoreDescriptor,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends CNNodeAppStoreWithoutHistory {

  protected def retryProvider: RetryProvider
  final protected def futureSupervisor: FutureSupervisor = retryProvider.futureSupervisor

  protected def handleIngestionSummary(summary: IngestionSummary): Unit = ()

  override val multiDomainAcsStore: DbMultiDomainAcsStore[Nothing] =
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

  override def updateHistory: Option[UpdateHistory] = None

  override def close(): Unit = ()
}
