package com.daml.network.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.network.environment.RetryProvider
import com.daml.network.store.*
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}

import scala.concurrent.ExecutionContext

abstract class DbCNNodeAppStore[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    storeDescriptor: io.circe.Json,
)(implicit
    protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends CNNodeAppStore[TXI, TXE] {

  protected def retryProvider: RetryProvider
  final protected def futureSupervisor: FutureSupervisor = retryProvider.futureSupervisor

  override val multiDomainAcsStore: DbMultiDomainAcsStore[TXI, TXE] =
    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      txLogTableName,
      storeDescriptor,
      loggerFactory,
      acsContractFilter,
      txLogParser,
      retryProvider,
      (evt, tc) => ingestionAcsInsert(evt)(tc),
      (evt, tc) => ingestionTxLogInsert(evt)(tc),
    )

  // TODO(M3-83): set this to multiDomainAcsStore once all db stores support reading from the db txlog
  override def txLog: TxLogStore[TXI, TXE] = new InMemoryMultiDomainAcsStore(
    loggerFactory,
    acsContractFilter,
    txLogParser,
    retryProvider,
  )

  override lazy val domains: InMemoryDomainStore =
    new InMemoryDomainStore(
      acsContractFilter.ingestionFilter.primaryParty,
      loggerFactory,
      retryProvider,
    )

  def ingestionAcsInsert(createdEvent: CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, DBIOAction[?, NoStream, Effect.Write]]

  def ingestionTxLogInsert(record: TXI)(implicit
      tc: TraceContext
  ): Either[String, DBIOAction[?, NoStream, Effect.Write]]

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
      "THIS_STORE_DOES_NOT_HAVE_A_TXLOG",
      storeDescriptor,
    ) { this: ConfiguredDefaultDomain =>

  override def ingestionTxLogInsert(record: TxLogStore.IndexRecord)(implicit
      tc: TraceContext
  ): Either[String, DBIOAction[?, NoStream, Effect.Write]] = Right(DBIO.successful(()))

}

abstract class DbCNNodeAppStoreWithHistory[
    TXI <: TxLogStore.IndexRecord,
    TXE <: TxLogStore.Entry[TXI],
](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    storeDescriptor: io.circe.Json,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStore[TXI, TXE](
      storage,
      acsTableName,
      txLogTableName,
      storeDescriptor,
    )
    with CNNodeAppStoreWithHistory[TXI, TXE]
