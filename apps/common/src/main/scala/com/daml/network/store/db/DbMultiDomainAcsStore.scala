package com.daml.network.store.db

import akka.NotUsed
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.Source
import cats.implicits.*
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.TransferEvent
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.*
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

class DbMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    storage: DbStorage,
    tableName: String,
    resolveDomainId: => Future[DomainId], // no support for multi-domain yet
    override protected val loggerFactory: NamedLoggerFactory,
    @unused futureSupervisor: FutureSupervisor,
    @unused retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    traceContext: TraceContext,
    closeContext: CloseContext,
) extends MultiDomainAcsStore
    with AcsTables
    with NamedLogging {

  import MultiDomainAcsStore.*
  import profile.api.*

  // TODO (#5247): resolve/compute from ingestion filters
  val storeId: Int = 1

  @unused
  private def minimumLastOffset(): Future[Option[String]] = {
    storage
      .querySingle(
        sql"""
        select min(last_ingested_offset)
        from store_ingestion_states
        where store_id = $storeId
         """.as[String].headOption,
        "minimumLastOffset",
      )
      .value
  }

  override def lookupContractById[C, TCid <: ContractId[_], T](companion: C)(id: ContractId[_])(
      implicit companionClass: ContractCompanion[C, TCid, T]
  ): Future[Option[ContractWithState[TCid, T]]] = {
    storage
      .querySingle( // index: acs_store_template_sid_cid
        sql"""
          #$selectFromAcsTable
          where store_id = $storeId
            and contract_id = ${lengthLimited(id.contractId)}
           """.as[AcsStoreRowTemplate].headOption,
        "lookupContractById",
      )
      .semiflatMap(contractWithStateFromRow(companion)(_))
      .value
  }

  override def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Seq[ContractWithState[TCid, T]]] = {
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage.query( // index: acs_store_template_sid_tid_en
        sql"""
          #$selectFromAcsTable
          where store_id = $storeId
            and template_id = $templateId
          order by event_number
          limit ${sqlLimit(limit)}
           """.as[AcsStoreRowTemplate],
        "listContracts",
      )
      limited = applyLimit(limit, result)(noTracingLogger)
      withState <- limited.traverse(contractWithStateFromRow(companion)(_))
    } yield withState
  }

  override def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[ReadyContract[TCid, T]]] = {
    // TODO (#5314): do a query instead of in-memory filtering via toReadyContract
    listContracts(companion, limit).map(_.flatMap(_.toReadyContract))
  }

  // TODO (#3822) `expiresAt` is unused here, but necessary for the in-memory version to work.
  // With an ExpiredContract interface it can be dropped from both.
  override private[network] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[
    T
  ], T <: Template](companion: C)(expiresAt: T => Instant)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): ListExpiredContracts[TCid, T] = { (now, rawLimit) => implicit traceContext =>
    val limit = PageLimit(rawLimit.toLong)
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage
        .query( // index: acs_store_template_sid_ce, except it's missing template_id and event_number
          sql"""
          #$selectFromAcsTable
          where store_id = $storeId
            and template_id = $templateId
            and contract_expires_at < $now
          order by event_number
          limit ${sqlLimit(limit)}
           """.as[AcsStoreRowTemplate],
          "listExpiredFromPayloadExpiry",
        )
      limited = applyLimit(limit, result)(noTracingLogger)
      withState <- limited.traverse(contractWithStateFromRow(companion)(_))
      // TODO (#5314): adjust the query instead of in-memory filtering via toReadyContract
    } yield withState.flatMap(_.toReadyContract)
  }

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: DomainId,
      limit: Limit,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[Contract[TCid, T]]] = {
    // TODO (#5314): the DbMultiDomainAcsStore is currently tied to a single domain,
    //  so this method doesn't make that much sense atm
    resolveDomainId.flatMap { thisDomain =>
      if (thisDomain != domain) {
        logger.warn(
          "Tried to list contracts on domain {} but this DB ACS Store is for domain {}.",
          domain,
          thisDomain,
        )
        Future.successful(Seq.empty)
      } else {
        listContracts(companion, limit).map(_.map(_.contract))
      }
    }
  }

  override def streamReadyContracts[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    streamReadyContracts(companion, PageLimit(100L))
  }

  def streamReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      pageSize: PageLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    val templateId = companionClass.typeId(companion)
    Source
      .future(resolveDomainId) // TODO (#5314): this probably won't apply anymore
      .flatMapConcat { domainId =>
        Source
          .unfoldAsync(0L) { fromEventNumber =>
            storage
              .query( // index: acs_store_template_sid_tid_en
                sql"""
                    #$selectFromAcsTable
                    where store_id = $storeId
                      and template_id = $templateId
                      and event_number >= $fromEventNumber
                    order by event_number
                    limit ${sqlLimit(pageSize)}
                     """.as[AcsStoreRowTemplate],
                "streamReadyContracts",
              )
              .map { rows =>
                Some(
                  rows.lastOption
                    .map(_.eventNumber)
                    .map { lastEventNumber =>
                      (
                        lastEventNumber + 1,
                        rows.map(row => ReadyContract(contractFromRow(companion)(row), domainId)),
                      )
                    }
                    .getOrElse(fromEventNumber -> Vector.empty)
                )
              }
          }
          .delayWith(
            () =>
              (rows: Vector[ReadyContract[TCid, T]]) => {
                if (rows.isEmpty) {
                  // TODO (#5374): the polling shouldn't be necessary
                  StreamSleepOnNoResult // no need to spam the DB with queries that give empty results
                } else {
                  0.millis
                }
              },
            DelayOverflowStrategy.backpressure,
          )
      }
      .mapConcat(identity)
  }

  private val StreamSleepOnNoResult = 500.millis

  override def streamReadyForTransferIn(): Source[TransferEvent.Out, NotUsed] = ???

  override def isReadyForTransferIn(out: TransferId): Future[Boolean] = ???

  override def signalWhenIngestedOrShutdown(domainId: DomainId, offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def signalWhenAcsCompletedOrShutdown(domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def ingestionSink: IngestionSink = ???

  override def close(): Unit = ()

  private val selectFromAcsTable: String =
    s"""
      |select store_id,
      |  event_number,
      |  contract_id,
      |  template_id,
      |  create_arguments,
      |  contract_metadata_created_at,
      |  contract_metadata_contract_key_hash,
      |  contract_metadata_driver_internal,
      |  contract_expires_at
      |from $tableName
      |""".stripMargin

  private def contractWithStateFromRow[C, TCid <: ContractId[_], T](companion: C)(
      row: AcsStoreRowTemplate
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[ContractWithState[TCid, T]] = {
    resolveDomainId.map { domainId =>
      val contract = contractFromRow(companion)(row)
      // TODO (#5314): handle InFlight
      val state = ContractState.Assigned(domainId)
      ContractWithState(contract, state)
    }
  }

  private def contractFromRow[C, TCId <: ContractId[_], T](companion: C)(
      row: AcsStoreRowTemplate
  )(implicit companionClass: ContractCompanion[C, TCId, T]): Contract[TCId, T] = {
    companionClass
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
  }

  private def sqlLimit(limit: Limit): Long = {
    limit match {
      case HardLimit(limit) => limit + 1
      case PageLimit(limit) => limit
    }
  }
}
