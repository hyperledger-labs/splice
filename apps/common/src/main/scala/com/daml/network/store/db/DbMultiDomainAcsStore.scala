package com.daml.network.store.db

import akka.NotUsed
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.Source
import cats.implicits.*
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  InFlightTransferOutEvent,
  TransferEvent,
  TreeUpdate,
}
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.*
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.String256M
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.DurationInt

class DbMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    storage: DbStorage,
    tableName: String,
    storeDescriptor: io.circe.Json,
    resolveDomainId: TraceContext => Future[DomainId], // no support for multi-domain yet
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: MultiDomainAcsStore.ContractFilter,
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    @unused futureSupervisor: FutureSupervisor,
    @unused retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends MultiDomainAcsStore
    with TxLogStore[TXI, TXE]
    with AcsTables
    with AcsQueries
    with NamedLogging {

  import MultiDomainAcsStore.*
  import profile.api.*

  // storeId is the primary keys of rows in the store_descriptors table.
  // This ID is immutable and used in many queries, that's why it is cached here.
  private val storeIdA: AtomicReference[Option[Int]] = new AtomicReference(None)
  def storeId: Int = storeIdA
    .get()
    .getOrElse(throw new RuntimeException("Using storeId before it was assigned"))

  // Some callers depend on all queries always returning sensible data, but may perform queries
  // before the ACS is fully ingested. We therefore delay all queries until the ACS is ingested.
  private val finishedAcsIngestion: Promise[Unit] = Promise()
  def waitUntilAcsIngested[T](f: => Future[T]): Future[T] =
    finishedAcsIngestion.future.flatMap(_ => f)
  def waitUntilAcsIngested(): Future[Unit] =
    finishedAcsIngestion.future

  def lastIngestedOffset(
      storage: DbStorage,
      storeId: Int,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
      closeContext: CloseContext,
  ): Future[String] = waitUntilAcsIngested {
    import storage.api.jdbcProfile.api.*
    storage
      .querySingle(
        sql"""
              select last_ingested_offset from store_descriptors
              where id = $storeId
           """.as[String].headOption,
        "minimumLastOffset",
      )
      .value
      .map(
        _.getOrElse(
          throw new IllegalStateException("Offset must be defined, as the ACS was ingested")
        )
      )
  }

  override def lookupContractById[C, TCid <: ContractId[_], T](companion: C)(id: ContractId[_])(
      implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] = waitUntilAcsIngested {
    storage
      .querySingle( // index: acs_store_template_sid_cid
        sql"""
          #${selectFromAcsTable(tableName)}
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
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage.query( // index: acs_store_template_sid_tid_en
        sql"""
          #${selectFromAcsTable(tableName)}
          where store_id = $storeId
            and template_id = $templateId
          order by event_number
          limit ${sqlLimit(limit)}
           """.as[AcsStoreRowTemplate],
        "listContracts",
      )
      limited = applyLimit(limit, result)
      withState <- limited.traverse(contractWithStateFromRow(companion)(_))
    } yield withState
  }

  override def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ReadyContract[TCid, T]]] = waitUntilAcsIngested {
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
      _ <- waitUntilAcsIngested()
      result <- storage
        .query( // index: acs_store_template_sid_tid_ce
          sql"""
          #${selectFromAcsTable(tableName)}
          where store_id = $storeId
            and template_id = $templateId
            and contract_expires_at < $now
          order by event_number
          limit ${sqlLimit(limit)}
           """.as[AcsStoreRowTemplate],
          "listExpiredFromPayloadExpiry",
        )
      limited = applyLimit(limit, result)
      withState <- limited.traverse(contractWithStateFromRow(companion)(_))
      // TODO (#5314): adjust the query instead of in-memory filtering via toReadyContract
    } yield withState.flatMap(_.toReadyContract)
  }

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: DomainId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]] = waitUntilAcsIngested {
    // TODO (#5314): the DbMultiDomainAcsStore is currently tied to a single domain,
    //  so this method doesn't make that much sense atm
    resolveDomainId(traceContext).flatMap { thisDomain =>
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
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    streamReadyContracts(companion, PageLimit(100L))
  }

  def streamReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      pageSize: PageLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    val templateId = companionClass.typeId(companion)
    Source
      .future(
        // TODO(#5534): this is currently waiting until the whole ACS has been ingested.
        //  After switching to streaming ACS ingestion, we could start streaming contracts while
        //  the ACS is being ingested.
        waitUntilAcsIngested(resolveDomainId(traceContext))
      ) // TODO (#5314): this probably won't apply anymore
      .flatMapConcat { domainId =>
        Source
          .unfoldAsync(0L) { fromEventNumber =>
            storage
              .query( // index: acs_store_template_sid_tid_en
                sql"""
                    #${selectFromAcsTable(tableName)}
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

  override def signalWhenIngestedOrShutdown(offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def ingestionSink: IngestionSink = new MultiDomainAcsStore.IngestionSink {
    override def ingestionFilter: IngestionFilter = contractFilter.ingestionFilter

    override def initialize()(implicit traceContext: TraceContext): Future[Option[String]] = {
      // Notes:
      // - Postgres JSONB does not preserve white space, does not preserve the order of object keys, and does not keep duplicate object keys
      // - Postgres JSONB columns have a maximum size of 255MB
      // - We are using noSpacesSortKeys to insert a canonical serialization of the JSON object, even though this is not necessary for Postgres
      // - 'ON CONFLICT DO NOTHING RETURNING ...' does not return anything if the row already exists, that's why we are using two separate queries
      val descriptorStr = String256M.tryCreate(storeDescriptor.noSpacesSortKeys)
      for {
        _ <- storage
          .update(
            sql"""
            insert into store_descriptors (descriptor)
            values (${descriptorStr}::jsonb)
            on conflict do nothing
           """.asUpdate,
            "initialize.1",
          )
        descriptorResult <- storage
          .querySingle(
            sql"""
             select id, last_ingested_offset
             from store_descriptors
             where descriptor = ${descriptorStr}::jsonb
             """.as[(Int, Option[String])].headOption,
            "initialize.2",
          )
          .value
      } yield {
        val (newStoreId, lastIngestedOffset) = descriptorResult.getOrElse(
          throw new RuntimeException(s"No row for $storeDescriptor found")
        )

        storeIdA.set(Some(newStoreId))
        logger.info(s"Store $storeDescriptor initialized with storeId $storeId")

        if (lastIngestedOffset.isDefined) {
          finishedAcsIngestion.success(())
        }

        lastIngestedOffset
      }
    }

    // Note: returns a DBIOAction, as updating the offset needs to happen in the same SQL transaction
    // that modifies the ACS/TxLog.
    private def updateOffset(offset: String): DBIOAction[Unit, NoStream, Effect.Write] =
      sql"""
            update store_descriptors
            set last_ingested_offset = ${lengthLimited(offset)}
            where id = $storeId
           """.asUpdate.map(_ => ())

    override def ingestAcs(
        offset: String,
        acs: Seq[ActiveContract],
        inFlight: Seq[InFlightTransferOutEvent],
    )(implicit traceContext: TraceContext): Future[Unit] = {
      assert(
        finishedAcsIngestion.isCompleted == false,
        s"ACS was already ingested for store $storeId",
      )
      for {

        // TODO(#5481): Insert ACS data here, in the same SQL transaction as updating the offset.
        //  Don't insert TxLog data, the TxLog starts after the initial ACS ingestion.
        _ <- storage.update(updateOffset(offset), "ingestAcs.updateOffset")
      } yield {
        finishedAcsIngestion.success(())
        logger.info(
          s"Store $storeId ingested the ACS and switched to ingesting updates at $offset"
        )
      }
    }

    // TODO(#5481): Implement ingesting ACS/TxLog data.
    override def ingestUpdate(domain: DomainId, transfer: TreeUpdate)(implicit
        traceContext: TraceContext
    ): Future[Unit] = ???
  }

  override def close(): Unit = ()

  private def contractWithStateFromRow[C, TCid <: ContractId[_], T](companion: C)(
      row: AcsStoreRowTemplate
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[ContractWithState[TCid, T]] = {
    resolveDomainId(traceContext).map { domainId =>
      val contract = contractFromRow(companion)(row)
      // TODO (#5314): handle InFlight
      val state = ContractState.Assigned(domainId)
      ContractWithState(contract, state)
    }
  }

  override def getTxLogIndicesByOffset(offset: Int, limit: Int)(implicit ec: ExecutionContext) = ???

  override def getLatestTxLogIndex(query: TXI => Boolean)(implicit ec: ExecutionContext) = ???

  override def getTxLogIndicesAfterEventId(
      domainId: DomainId,
      beginAfterEventId: String,
      limit: Int,
  )(implicit ec: ExecutionContext) = ???

  override def getTxLogIndicesByFilter(filter: TXI => Boolean)(implicit ec: ExecutionContext) = ???

  override def findLatestTxLogIndex[A, Z](init: Z)(p: (Z, TXI) => Either[A, Z])(implicit
      ec: ExecutionContext
  ): Future[A] = ???
}
