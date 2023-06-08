package com.daml.network.store.db

import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  CNNodeAppStore,
  ConfiguredDefaultDomain,
  InMemoryDomainStore,
  InMemoryMultiDomainAcsStore,
  TxLogStore,
}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage

import scala.concurrent.ExecutionContext

abstract class DbCNNodeAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
](
    storage: DbStorage,
    tableName: String,
    storeDescriptor: io.circe.Json,
)(implicit
    protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends CNNodeAppStore[TXI, TXE] { this: ConfiguredDefaultDomain =>
  protected def futureSupervisor: FutureSupervisor

  protected def retryProvider: RetryProvider

  override val multiDomainAcsStore: DbMultiDomainAcsStore[TXI, TXE] =
    new DbMultiDomainAcsStore(
      storage,
      tableName,
      storeDescriptor,
      tc => defaultAcsDomainIdF(tc),
      loggerFactory,
      acsContractFilter,
      txLogParser,
      futureSupervisor,
      retryProvider,
    )

  override def txLog: TxLogStore[TXI, TXE] = new InMemoryMultiDomainAcsStore(
    loggerFactory,
    acsContractFilter,
    txLogParser,
    futureSupervisor,
    retryProvider,
  )

  override lazy val domains: InMemoryDomainStore =
    new InMemoryDomainStore(
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      futureSupervisor,
      retryProvider,
    )

  override def close(): Unit = ()
}

abstract class DbCNNodeAppStoreWithoutHistory(
    storage: DbStorage,
    tableName: String,
    storeDescriptor: io.circe.Json,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStore[TxLogStore.IndexRecord, TxLogStore.Entry[TxLogStore.IndexRecord]](
      storage,
      tableName,
      storeDescriptor,
    ) { this: ConfiguredDefaultDomain => }
