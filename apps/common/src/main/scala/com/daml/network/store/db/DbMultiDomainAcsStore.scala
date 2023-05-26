package com.daml.network.store.db

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.TransferEvent
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.{MultiDomainAcsStore, TxLogStore}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.{LengthLimitedString, String255}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}

class DbMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    storage: DbStorage,
    tableName: String,
    resolveDomainId: => Future[DomainId], // no support for multi-domain yet
    override protected val loggerFactory: NamedLoggerFactory,
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    @unused futureSupervisor: FutureSupervisor,
    @unused retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    traceContext: TraceContext,
    closeContext: CloseContext,
) extends MultiDomainAcsStore
    with TxLogStore[TXI, TXE]
    with AcsTables
    with NamedLogging {

  import MultiDomainAcsStore.*
  import profile.api.*

  override def lookupContractById[C, TCid <: ContractId[_], T](companion: C)(id: ContractId[_])(
      implicit companionClass: ContractCompanion[C, TCid, T]
  ): Future[Option[ContractWithState[TCid, T]]] = {
    storage
      .querySingle(
        sql"""
          select store_id,
               event_number,
               contract_id,
               template_id,
               create_arguments,
               contract_metadata_created_at,
               contract_metadata_contract_key_hash,
               contract_metadata_driver_internal,
               contract_expires_at
          from #$tableName
          where contract_id = ${lengthLimited(id.contractId)}
           """.as[AcsStoreRowTemplate].headOption,
        "lookupContractById",
      )
      .semiflatMap(contractWithStateFromRow(companion)(_))
      .value
  }

  override def findContractWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(p: Contract[TCid, T] => Boolean)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]] = ???

  override def findContractOnDomainWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, p: Contract[TCid, T] => Boolean)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[Contract[TCid, T]]]] = ???

  override def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Seq[ContractWithState[TCid, T]]] = ???

  override def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[ReadyContract[TCid, T]]] =
    ???

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: DomainId,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[Contract[TCid, T]]] = ???

  override def streamReadyContracts[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Source[ReadyContract[TCid, T], NotUsed] = ???

  override def streamReadyForTransferIn(): Source[TransferEvent.Out, NotUsed] = ???

  override def isReadyForTransferIn(out: TransferId): Future[Boolean] = ???

  override def signalWhenIngestedOrShutdown(domainId: DomainId, offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def signalWhenAcsCompletedOrShutdown(domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def ingestionSink: IngestionSink = ???

  override def getTxLogIndicesByOffset(offset: Int, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] = ???

  override def getLatestTxLogIndex(query: TXI => Boolean)(implicit
      ec: ExecutionContext
  ): Future[TXI] = ???

  override def getTxLogIndicesAfterEventId(
      domainId: DomainId,
      beginAfterEventId: String,
      limit: Int,
  )(implicit ec: ExecutionContext): Future[Seq[TXI]] = ???

  override def getTxLogIndicesByFilter(filter: TXI => Boolean)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] = ???

  override def close(): Unit = ()

  private def contractWithStateFromRow[C, TCid <: ContractId[_], T](companion: C)(
      row: AcsStoreRowTemplate
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[ContractWithState[TCid, T]] = {
    resolveDomainId.map { domainId =>
      val contract = companionClass
        .fromJson(companion)(
          row.templateId,
          row.contractId.contractId,
          row.createArguments,
          row.contractMetadataCreatedAt.toInstant,
          row.contractMetadataContractKeyHash,
          row.contractMetadataDriverInternal,
        )
        .fold(
          err => throw new IllegalStateException(s"Stored a contract that cannot be decoded: $err"),
          identity,
        )
      val state = ContractState.Assigned(domainId)
      ContractWithState(contract, state)
    }
  }

  // The DB may truncate strings of unbounded length, so it's advised to use a LengthLimitedString instead.
  private def lengthLimited(s: String): LengthLimitedString = String255.tryCreate(s)
}
