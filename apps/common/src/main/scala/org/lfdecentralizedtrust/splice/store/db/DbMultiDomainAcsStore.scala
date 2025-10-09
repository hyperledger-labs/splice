// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import cats.data.{NonEmptyList, OptionT}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import cats.implicits.*
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Template, TransactionTree}
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.IMPORT_ACS_WORKFLOW_ID_PREFIX
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  ActiveContract,
  IncompleteReassignmentEvent,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  LegacyOffset,
  QualifiedName,
  TemplateJsonDecoder,
  Trees,
}
import com.digitalasset.canton.config.CantonRequireTypes.{String255, String256M, String3}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.showPretty

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.{Seq, SortedMap, VectorMap}
import scala.concurrent.{ExecutionContext, Future, Promise}
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{
  ContractStateEvent,
  ReassignmentId,
}
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{
  AcsStoreId,
  SelectFromAcsTableWithStateResult,
}
import org.lfdecentralizedtrust.splice.store.db.AcsTables.ContractStateRowData
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore.StoreDescriptor
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricsContext
import com.google.protobuf.ByteString
import io.circe.Json
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.DestinationHistory
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId

import scala.collection.mutable
import scala.reflect.ClassTag
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

final class DbMultiDomainAcsStore[TXE](
    storage: DbStorage,
    acsTableName: String,
    txLogTableNameOpt: Option[String],
    interfaceViewsTableNameOpt: Option[String],
    acsStoreDescriptor: StoreDescriptor,
    txLogStoreDescriptor: Option[StoreDescriptor],
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: MultiDomainAcsStore.ContractFilter[
      _ <: AcsRowData,
      _ <: AcsInterfaceViewRowData,
    ],
    txLogConfig: TxLogStore.Config[TXE],
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    retryProvider: RetryProvider,
    /** Allows processing the summary in a store-specific manner, e.g., to produce metrics
      * on ingestion of certain contracts.
      */
    handleIngestionSummary: IngestionSummary => Unit = _ => (),
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends MultiDomainAcsStore
    with AcsTables
    with AcsQueries
    with TxLogQueries[TXE]
    with StoreErrors
    with NamedLogging
    with LimitHelpers {
  import DbMultiDomainAcsStore.*
  import MultiDomainAcsStore.*
  import profile.api.jdbcActionExtensionMethods

  override lazy val storeName = acsStoreDescriptor.name
  override lazy val storeParty = acsStoreDescriptor.party.toString

  override protected def metricsFactory: LabeledMetricsFactory = retryProvider.metricsFactory
  override lazy val metrics = new StoreMetrics(metricsFactory)(mc)

  private val state = new AtomicReference[State](State.empty())

  def acsStoreId: AcsStoreId =
    state
      .get()
      .acsStoreId
      .getOrElse(throw new RuntimeException("Using acsStoreId before it was assigned"))
  def txLogStoreId: TxLogStoreId = {
    if (txLogStoreDescriptor.isDefined) {
      state
        .get()
        .txLogStoreId
        .getOrElse(throw new RuntimeException("Using txLogStoreId before it was assigned"))
    } else {
      throw new RuntimeException("This store is not using a TxLog")
    }
  }

  def domainMigrationId: Long = domainMigrationInfo.currentMigrationId

  private[this] def txLogTableName =
    txLogTableNameOpt.getOrElse(throw new RuntimeException("This store doesn't use a TxLog"))

  private[this] def interfaceViewsTableName = interfaceViewsTableNameOpt.getOrElse(
    throw new RuntimeException("This store does not ingest interfaces")
  )

  // Some callers depend on all queries always returning sensible data, but may perform queries
  // before the ACS is fully ingested. We therefore delay all queries until the ACS is ingested.
  private val finishedAcsIngestion: Promise[Unit] = Promise()

  // Unlike waitUntilAcsIngested().isCompleted, this method returns true immediately after the ingestAcs() method finishes.
  // The former is slightly more asynchronous due to RetryProvider/FutureUnlessShutdown.
  def hasFinishedAcsIngestion: Boolean = finishedAcsIngestion.isCompleted

  def waitUntilAcsIngested[T](f: => Future[T]): Future[T] =
    waitUntilAcsIngested().flatMap(_ => f)

  def waitUntilAcsIngested(): Future[Unit] =
    retryProvider
      .waitUnlessShutdown(finishedAcsIngestion.future)
      .failOnShutdownTo {
        io.grpc.Status.UNAVAILABLE
          .withDescription(
            s"Aborted waitUntilAcsIngested, as RetryProvider(${retryProvider.loggerFactory.properties}) is shutting down in store $acsStoreDescriptor"
          )
          .asRuntimeException()
      }

  override def lookupContractById[C, TCid <: ContractId[_], T](companion: C)(id: ContractId[_])(
      implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] = waitUntilAcsIngested {
    storage
      .querySingle( // index: acs_store_template_sid_mid_cid
        selectFromAcsTableWithState(
          acsTableName,
          acsStoreId,
          domainMigrationId,
          where = sql"""acs.contract_id = ${lengthLimited(id.contractId)}""",
        ).headOption,
        "lookupContractById",
      )
      .map(result => contractWithStateFromRow(companion)(result))
      .value
  }

  /** Returns any contract of the same template as the passed companion.
    */
  override def findAnyContractWithOffset[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            acsTableName,
            acsStoreId,
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(templateId)}""",
            orderLimit = sql"limit 1",
          ).headOption,
          "findAnyContractWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      contractWithState = resultWithOffset.row.map(contractWithStateFromRow(companion)(_))
    } yield {
      QueryResult(
        resultWithOffset.offset,
        contractWithState,
      )
    }
  }

  override def lookupContractStateById(id: ContractId[?])(implicit
      traceContext: TraceContext
  ): Future[Option[ContractState]] = waitUntilAcsIngested {
    storage
      .querySingle( // index: acs_store_template_sid_mid_cid
        selectFromAcsTableWithState(
          acsTableName,
          acsStoreId,
          domainMigrationId,
          where = sql"""acs.contract_id = ${lengthLimited(id.contractId)}""",
        ).headOption,
        "lookupContractStateById",
      )
      .map(result => contractStateFromRow(result.stateRow))
      .value
  }

  def containsArchived(ids: Seq[ContractId[?]])(implicit
      traceContext: TraceContext
  ): Future[Boolean] = waitUntilAcsIngested {
    if (ids.isEmpty) Future.successful(false)
    else {
      val contractIds = inClause(ids)
      val expectedCount = ids.size
      storage
        .query(
          (sql"""
         select count(1)
         from #$acsTableName acs
         where acs.store_id = $acsStoreId
         and acs.migration_id = $domainMigrationId
         and acs.contract_id in """ ++ contractIds ++ sql"""
         """).toActionBuilder
            .as[Int]
            .head,
          "containsArchived",
        )
        .map { count =>
          count != expectedCount
        }
    }
  }

  override def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]] = {
    listContractsPaginated(companion, None, limit, SortOrder.Ascending).map(_.resultsInPage)
  }

  override def listContractsPaginated[C, TCid <: ContractId[_], T](
      companion: C,
      after: Option[Long],
      limit: Limit,
      sortOrder: SortOrder,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[ResultsPage[ContractWithState[TCid, T]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    val opName = s"listContracts:${templateId.getEntityName}"
    val afterCondition =
      after.fold(sql"")(a => (sql" and " ++ sortOrder.whereEventNumber(a)).toActionBuilder)
    for {
      result <- storage.query( // index: acs_store_template_sid_mid_tid_en
        selectFromAcsTableWithState(
          acsTableName,
          acsStoreId,
          domainMigrationId,
          where = (sql"""template_id_qualified_name = ${QualifiedName(
              templateId
            )} """ ++ afterCondition).toActionBuilder,
          orderLimit =
            (sortOrder.orderByAcsEventNumber ++ sql""" limit ${sqlLimit(limit)}""").toActionBuilder,
        ),
        opName,
      )
      limited = applyLimit(opName, limit, result)
      afterToken = limited.lastOption.map(_.acsRow.eventNumber)
      withState = limited.map(contractWithStateFromRow(companion)(_))
    } yield ResultsPage(withState, afterToken)
  }

  override def listAssignedContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[AssignedContract[TCid, T]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage.query( // index: acs_store_template_sid_mid_tid_en
        selectFromAcsTableWithState(
          acsTableName,
          acsStoreId,
          domainMigrationId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              templateId
            )} and assigned_domain is not null""",
          orderLimit = sql"""order by event_number limit ${sqlLimit(limit)}""",
        ),
        "listAssignedContracts",
      )
      limited = applyLimit("listAssignedContracts", limit, result)
      assigned = limited.map(assignedContractFromRow(companion)(_))
    } yield assigned
  }

  override private[splice] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[
    T
  ], T <: Template](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): ListExpiredContracts[TCid, T] = { (now, limit) => implicit traceContext =>
    val templateId = companionClass.typeId(companion)
    for {
      _ <- waitUntilAcsIngested()
      result <- storage
        .query( // index: acs_store_template_sid_mid_tid_ce
          selectFromAcsTableWithState(
            acsTableName,
            acsStoreId,
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                templateId
              )} and acs.contract_expires_at < $now""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listExpiredFromPayloadExpiry",
        )
      limited = applyLimit("listExpiredFromPayloadExpiry", limit, result)
      assigned = limited.map(assignedContractFromRow(companion)(_))
    } yield assigned
  }

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: SynchronizerId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage.query(
        selectFromAcsTableWithState(
          acsTableName,
          acsStoreId,
          domainMigrationId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              templateId
            )} and assigned_domain = $domain""",
          orderLimit = sql"""limit ${sqlLimit(limit)}""",
        ),
        "listContractsOnDomain",
      )
      limited = applyLimit("listContractsOnDomain", limit, result)
      contracts = limited.map(row => contractFromRow(companion)(row.acsRow))
    } yield contracts
  }

  override def listAssignedContractsNotOnDomainN(
      excludedDomain: SynchronizerId,
      companions: Seq[ConstrainedTemplate],
      limit: notOnDomainsTotalLimit.type,
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[?, ?]]] = waitUntilAcsIngested {
    val templateIdMap = companions
      .map(c => QualifiedName(c.getTemplateIdWithPackageId) -> c)
      .toMap
    val templateIds = inClause(templateIdMap.keys)
    for {
      result <- storage.query(
        selectFromAcsTableWithState(
          acsTableName,
          acsStoreId,
          domainMigrationId,
          where =
            (sql"""template_id_qualified_name IN """ ++ templateIds ++ sql""" and assigned_domain is not null and assigned_domain != $excludedDomain""").toActionBuilder,
          // bytea comparison in PG is left-to-right unsigned ascending, shorter
          // array is lesser if bytes are otherwise equal; there's an equivalent
          // soft implementation in
          // InMemoryMultiDomainAcsStore.reassignmentContractOrder
          orderLimit =
            sql"""order by extensions.digest((contract_id || $participantId)::bytea, 'md5'::text)
              limit ${(limit: PageLimit).limit}""",
        ),
        "listAssignedContractsNotOnDomainN",
      )
    } yield result.map { row =>
      assignedContractFromRow(
        templateIdMap(row.acsRow.templateIdQualifiedName)
      )(row)
    }
  }

  override def streamAssignedContracts[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[AssignedContract[TCid, T], NotUsed] = {
    val templateId = companionClass.typeId(companion)
    streamContractsWithState(
      pageSize = defaultPageSizeForContractStream,
      where = sql"""assigned_domain is not null and template_id_qualified_name = ${QualifiedName(
          templateId
        )}""",
    )
      .map(assignedContractFromRow(companion)(_))
  }

  def listTxLogEntries()(implicit
      tc: TraceContext,
      tag: ClassTag[TXE],
  ): Future[Seq[TXE]] = {
    storage
      .query(
        selectFromTxLogTable(
          txLogTableName,
          txLogStoreId,
          where = sql"true",
          orderLimit = sql"order by migration_id, domain_id, record_time, entry_number",
        ),
        "listTextLogEntry",
      )
      .map { rows =>
        rows.map(txLogEntryFromRow[TXE](txLogConfig))
      }
  }
  override def initializeTxLogBackfilling()(implicit tc: TraceContext): Future[Unit] = {
    storage.update(
      DBIOAction
        .seq(
          // Note: this one-time explicit backfilling initialization might run concurrently with
          // `doInitializeFirstIngestedUpdate()`, which is called by the ingestion process.
          // Both methods use `ON CONFLICT` clauses to handle concurrent updates.
          //
          // No matter in which order the above methods are called, the actual backfilling process
          // (i.e., `destinationHistory.insert()`) won't be called until this method finishes, which
          // guarantees that txlog_first_ingested_update is initialized with the first observed record_time
          // for each migration/synchronizer pair.
          sqlu"""
            insert into txlog_first_ingested_update (store_id, migration_id, synchronizer_id, record_time)
            select store_id, migration_id, domain_id, min(record_time) as record_time
              from #$txLogTableName
              where store_id = $txLogStoreId
              group by store_id, migration_id, domain_id
            on conflict (store_id, migration_id, synchronizer_id) do update
              set record_time = least(excluded.record_time, txlog_first_ingested_update.record_time)
          """,
          sqlu"""
            insert into txlog_backfilling_status (store_id, backfilling_complete)
            values ($txLogStoreId, false)
            on conflict do nothing
          """,
        )
        .transactionally,
      "initializeTxLogBackfilling",
    )
  }

  override def getTxLogBackfillingState()(implicit
      tc: TraceContext
  ): Future[TxLogBackfillingState] = for {
    complete <- storage
      .query(
        sql"""
            select backfilling_complete
            from txlog_backfilling_status
            where store_id = $txLogStoreId
            """.as[Boolean].headOption,
        "getTxLogBackfillingComplete",
      )
  } yield complete match {
    case Some(true) =>
      TxLogBackfillingState.Complete
    case Some(false) =>
      TxLogBackfillingState.InProgress
    case None =>
      TxLogBackfillingState.NotInitialized
  }

  def getTxLogFirstIngestedMigrationId(
  )(implicit tc: TraceContext): Future[Option[Long]] = {
    for {
      migrationId <- storage
        .query(
          sql"""
            select min(migration_id)
            from txlog_first_ingested_update
            where store_id = $txLogStoreId
           """
            .as[Option[Long]]
            .head,
          "getTxLogFirstIngestedMigrationId",
        )
    } yield {
      migrationId
    }
  }

  def getTxLogFirstIngestedRecordTimes(
      migrationId: Long
  )(implicit tc: TraceContext): Future[Map[SynchronizerId, CantonTimestamp]] = {
    for {
      rows <- storage
        .query(
          sql"""
            select synchronizer_id, record_time
            from txlog_first_ingested_update
            where store_id = $txLogStoreId and migration_id = $migrationId
           """
            .as[(SynchronizerId, CantonTimestamp)],
          "getTxLogFirstIngestedRecordTimes",
        )
    } yield {
      rows.toMap
    }
  }

  override lazy val destinationHistory
      : HistoryBackfilling.DestinationHistory[UpdateHistoryResponse] =
    new HistoryBackfilling.DestinationHistory[UpdateHistoryResponse] {
      override def isReady: Boolean = state.get().txLogStoreId.isDefined

      override def backfillingInfo(implicit
          tc: TraceContext
      ): Future[Option[HistoryBackfilling.DestinationBackfillingInfo]] = {
        (
          for {
            migrationId <- OptionT(getTxLogFirstIngestedMigrationId())
            range <- OptionT.liftF(getTxLogFirstIngestedRecordTimes(migrationId))
          } yield HistoryBackfilling.DestinationBackfillingInfo(migrationId, range)
        ).value
      }

      private def doInsertEntries(
          migrationId: Long,
          synchronizerId: SynchronizerId,
          treesWithEntries: Seq[(TransactionTree, TXE)],
      ) = {
        treesWithEntries.headOption match {
          case None =>
            // None of the trees in this batch produced any entries, nothing to insert
            DBIOAction.unit
          case Some((firstEntryTree, _)) =>
            val firstRecordTime = CantonTimestamp.assertFromInstant(firstEntryTree.getRecordTime)
            val summary = MutableIngestionSummary.empty
            for {
              // DbStorage requires all actions to be idempotent.
              // We can't use `ON CONFLICT DO NOTHING` because different txlog tables have different uniqueness constraints:
              // - `txlog_store_template` (used in test code) doesn't have any uniqueness constraint
              // - `user_wallet_txlog_store` has a unique index on (store_id, tx_log_id, event_id)
              // - `txlog_first_ingested_update` has an index on (store_id, entry_type, event_id), but it's not unique
              // Uniqueness constraints should also be consistent with parsers - some parsers might want to produce
              // multiple entries for the same event (for example, if an exercise event batches multiple logical operations).
              // Instead of rethinking the whole design, we just check if some entry for one of the trees already exists in the table.
              //
              // Note: this approach protects against repeated calls of this method with the same arguments
              // (e.g., if it's retried because of a transient database connection error or in DbStorageIdempotency test code),
              // but it does NOT protect against this method being called concurrently (both SQL transactions could independently
              // decide that the items do not exist and need to be inserted).
              // This is fine because this method is only called from TxLogBackfillingTrigger, and triggers only run one task at a time.
              itemExists <- sql"""
                select exists(
                  select record_time
                  from #$txLogTableName
                  where
                    store_id = $txLogStoreId and
                    migration_id = $migrationId and
                    domain_id = $synchronizerId and
                    record_time = $firstRecordTime
                )
                """.as[Boolean].head
              _ <-
                if (!itemExists) {
                  DBIOAction.seq(
                    treesWithEntries.map { case (tree, entry) =>
                      doIngestTxLogInsert(
                        migrationId,
                        synchronizerId,
                        tree.getOffset,
                        CantonTimestamp.assertFromInstant(tree.getRecordTime),
                        entry,
                        summary,
                      )
                    }*
                  )
                } else DBIOAction.unit
            } yield ()
        }
      }

      override def insert(
          migrationId: Long,
          synchronizerId: SynchronizerId,
          items: Seq[UpdateHistoryResponse],
      )(implicit
          tc: TraceContext
      ): Future[DestinationHistory.InsertResult] = {
        val trees = items.collect { case UpdateHistoryResponse(TransactionTreeUpdate(tree), _) =>
          assert(
            tree.getRecordTime.isAfter(CantonTimestamp.MinValue.toInstant),
            "insert() must not be called with import updates",
          )
          tree
        }

        NonEmptyList.fromFoldable(trees) match {
          case None =>
            Future.successful(
              DestinationHistory.InsertResult(
                backfilledUpdates = 0L,
                backfilledEvents = 0L,
                lastBackfilledRecordTime =
                  items.headOption.map(_.update.recordTime).getOrElse(CantonTimestamp.MinValue),
              )
            )
          case Some(nonEmpty) =>
            val firstTree = nonEmpty.foldLeft(nonEmpty.head) { case (acc, tree) =>
              if (tree.getRecordTime.isBefore(acc.getRecordTime)) tree else acc
            }
            val firstRecordTime = CantonTimestamp.assertFromInstant(firstTree.getRecordTime)
            val treesWithEntries = trees.flatMap { tree =>
              val entries = txLogConfig.parser.parse(tree, synchronizerId, logger)
              entries.map(e => (tree, e))
            }

            for {
              _ <- storage.queryAndUpdate(
                DBIOAction
                  .seq(
                    doInsertEntries(migrationId, synchronizerId, treesWithEntries),
                    doUpdateFirstIngestedUpdate(
                      synchronizerId,
                      migrationId,
                      firstRecordTime,
                    ),
                  )
                  .transactionally,
                "destinationHistory.insert",
              )
            } yield DestinationHistory.InsertResult(
              backfilledUpdates = trees.size.toLong,
              backfilledEvents =
                trees.foldLeft(0L)((sum, tree) => sum + tree.getEventsById.size().toLong),
              lastBackfilledRecordTime =
                CantonTimestamp.assertFromInstant(nonEmpty.last.getRecordTime),
            )
        }
      }

      override def markBackfillingComplete()(implicit tc: TraceContext): Future[Unit] = {
        storage
          .update(
            sqlu"""
            update txlog_backfilling_status
            set backfilling_complete = true
            where store_id = $txLogStoreId
            """,
            "markBackfillingComplete",
          )
          .map(_ => ())
      }
    }

  private val defaultPageSizeForContractStream = PageLimit.tryCreate(100)

  /** Returns a stream of contracts with their current state.
    * The same contract may appear multiple times in the stream if the contract state changes.
    */
  private def streamContractsWithState(
      pageSize: PageLimit,
      where: SQLActionBuilder,
  )(implicit
      traceContext: TraceContext
  ): Source[SelectFromAcsTableWithStateResult, NotUsed] = {
    Source
      .future(
        // TODO(#863): this is currently waiting until the whole ACS has been ingested.
        //  After switching to streaming ACS ingestion, we could start streaming contracts while
        //  the ACS is being ingested.
        waitUntilAcsIngested()
      )
      .flatMapConcat { _ =>
        Source
          .unfoldAsync(0L) { fromNumber =>
            val offsetPromise = state.get().offsetChanged
            storage
              .query(
                selectFromAcsTableWithState(
                  acsTableName,
                  acsStoreId,
                  domainMigrationId,
                  where = (where ++ sql" and state_number >= $fromNumber").toActionBuilder,
                  orderLimit =
                    (sql"order by state_number limit ${sqlLimit(pageSize)}").toActionBuilder,
                ),
                "streamContractsWithState",
              )
              .flatMap { rows =>
                rows.lastOption.map(_.stateRow.stateNumber) match {
                  case Some(lastNumber) =>
                    Future.successful(
                      (
                        lastNumber + 1,
                        rows,
                      )
                    )
                  case None =>
                    // to avoid polling the DB, we wait for a new offset to have been ingested
                    offsetPromise.future.map(_ => fromNumber -> Vector.empty)
                }
              }
              .map(Some(_))
          }
      }
      .mapConcat(identity)
  }

  override def streamReadyForAssign()(implicit
      tc: TraceContext
  ): Source[ReassignmentEvent.Unassign, NotUsed] = {
    streamContractsWithState(
      pageSize = defaultPageSizeForContractStream,
      where = sql"""assigned_domain is null""",
    )
      .map(reassignmentEventUnassignFromRow)
  }

  override def isReadyForAssign(contractId: ContractId[_], out: ReassignmentId)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    waitUntilAcsIngested {
      storage
        .querySingle(
          selectFromAcsTableWithState(
            acsTableName,
            acsStoreId,
            domainMigrationId,
            where = sql"""acs.contract_id = ${contractId}""",
          ).headOption,
          "isReadyForAssign",
        )
        .value
        .map {
          case Some(SelectFromAcsTableWithStateResult(_, state)) =>
            state.assignedDomain.isEmpty &&
            state.reassignmentSourceDomain.contains(out.source) &&
            state.reassignmentUnassignId.contains(out.id)
          case _ => false
        }
    }
  }

  override def listInterfaceViews[C, ICid <: ContractId[?], View <: DamlRecord[View]](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, ICid, View],
      tc: TraceContext,
  ): Future[Seq[Contract[ICid, View]]] = waitUntilAcsIngested {
    val interfaceId = companionClass.typeId(companion)
    val opName = s"listInterfaceViews:${interfaceId.getEntityName}"
    for {
      rows <- storage.query(
        sql"""
             SELECT contract_id, interface_view, acs.created_at, acs.created_event_blob
             FROM #$interfaceViewsTableName interface
               JOIN #$acsTableName acs ON acs.event_number = interface.acs_event_number
             WHERE interface_id_package_id = ${interfaceId.getPackageId}
               AND interface_id_qualified_name = ${QualifiedName(interfaceId)}
               AND store_id = $acsStoreId
               AND migration_id = $domainMigrationId
             ORDER BY interface.acs_event_number
             LIMIT ${sqlLimit(limit)}
           """.as[(String, Json, Timestamp, Array[Byte])],
        opName,
      )
    } yield {
      val limited = applyLimit(opName, limit, rows)
      limited.map { case (contractId, viewJson, createdAt, createdEventBlob) =>
        companionClass
          .fromJson(companion)(
            interfaceId,
            contractId,
            viewJson,
            ByteString.copyFrom(createdEventBlob),
            createdAt.toInstant,
          )
          .fold(
            err =>
              throw new IllegalStateException(
                s"Contract $contractId cannot be decoded as interface view $interfaceId: $err. Payload: $viewJson"
              ),
            identity,
          )
      }
    }
  }

  override def findInterfaceViewByContractId[C, ICid <: ContractId[_], View <: DamlRecord[View]](
      companion: C
  )(contractId: ICid)(implicit
      companionClass: ContractCompanion[C, ICid, View],
      tc: TraceContext,
  ): Future[Option[ContractWithState[ICid, View]]] = {
    val interfaceId = companionClass.typeId(companion)
    val opName = s"findInterfaceViewByContractId:${interfaceId.getEntityName}"
    (for {
      (contractId, viewJson, createdAt, createdEventBlob, state) <- storage.querySingle(
        sql"""
             SELECT
               contract_id,
               interface_view,
               acs.created_at,
               acs.created_event_blob,
               #${SelectFromAcsTableWithStateResult.stateColumnsCommaSeparated()}
             FROM #$interfaceViewsTableName interface
               JOIN #$acsTableName acs ON acs.event_number = interface.acs_event_number
             WHERE interface_id_package_id = ${interfaceId.getPackageId}
               AND interface_id_qualified_name = ${QualifiedName(interfaceId)}
               AND store_id = $acsStoreId
               AND migration_id = $domainMigrationId
               AND contract_id = $contractId
           """
          .as[(String, Json, Timestamp, Array[Byte], AcsQueries.SelectFromContractStateResult)]
          .headOption,
        opName,
      )
    } yield {
      val contractState = contractStateFromRow(state)
      val contract = companionClass
        .fromJson(companion)(
          interfaceId,
          contractId,
          viewJson,
          ByteString.copyFrom(createdEventBlob),
          createdAt.toInstant,
        )
        .fold(
          err =>
            throw new IllegalStateException(
              s"Contract $contractId cannot be decoded as interface view $interfaceId: $err. Payload: $viewJson"
            ),
          identity,
        )
      ContractWithState(contract, contractState)
    }).value
  }

  override private[store] def listIncompleteReassignments()(implicit
      tc: TraceContext
  ): Future[Map[ContractId[_], NonEmpty[Set[ReassignmentId]]]] = {
    for {
      rows <- storage
        .query(
          sql"""
             select contract_id, source_domain, unassign_id
             from incomplete_reassignments
             where store_id = $acsStoreId and migration_id = $domainMigrationId
             """.as[(String, String, String)],
          "listIncompleteReassignments",
        )
    } yield rows
      .map(row => row._1 -> new ReassignmentId(SynchronizerId.tryFromString(row._2), row._3))
      .groupBy(_._1)
      .map { case (key, values) =>
        new ContractId(key) -> NonEmpty
          .from(values.map(_._2).toSet)
          .getOrElse(sys.error("Impossible"))
      }
  }

  override protected def signalWhenIngestedOrShutdownImpl(offset: Long)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    state
      .updateAndGet(_.withOffsetToSignal(offset))
      .offsetIngestionsToSignal
      .get(offset) match {
      case None => Future.unit
      case Some(offsetIngestedPromise) =>
        val name = s"signalWhenIngested($offset)"
        val ingestedOrShutdown = retryProvider
          .waitUnlessShutdown(offsetIngestedPromise.future)
          .onShutdown(
            logger.debug(s"Aborted $name, as we are shutting down")
          )
        retryProvider.futureSupervisor.supervised(name)(ingestedOrShutdown)
    }
  }

  override lazy val ingestionSink: IngestionSink = new MultiDomainAcsStore.IngestionSink {
    override def ingestionFilter: IngestionFilter = contractFilter.ingestionFilter

    private sealed trait InitializeDescriptorResult[StoreId]
    private case class StoreHasData[StoreId](
        storeId: StoreId,
        lastIngestedOffset: Long,
    ) extends InitializeDescriptorResult[StoreId]
    private case class StoreHasNoData[StoreId](
        storeId: StoreId
    ) extends InitializeDescriptorResult[StoreId]
    private case class StoreNotUsed[StoreId]() extends InitializeDescriptorResult[StoreId]

    private[this] def initializeDescriptor(
        descriptor: StoreDescriptor
    )(implicit
        traceContext: TraceContext
    ): Future[InitializeDescriptorResult[Int]] = {
      // Notes:
      // - Postgres JSONB does not preserve white space, does not preserve the order of object keys, and does not keep duplicate object keys
      // - Postgres JSONB columns have a maximum size of 255MB
      // - We are using noSpacesSortKeys to insert a canonical serialization of the JSON object, even though this is not necessary for Postgres
      // - 'ON CONFLICT DO NOTHING RETURNING ...' does not return anything if the row already exists, that's why we are using two separate queries
      val descriptorStr = String256M.tryCreate(descriptor.toJson.noSpacesSortKeys)
      for {
        _ <- storage
          .update(
            sql"""
            insert into store_descriptors (descriptor)
            values (${descriptorStr}::jsonb)
            on conflict do nothing
           """.asUpdate,
            "initializeDescriptor.1",
          )

        newStoreId <- storage
          .querySingle(
            sql"""
             select id
             from store_descriptors
             where descriptor = ${descriptorStr}::jsonb
             """.as[Int].headOption,
            "initializeDescriptor.2",
          )
          .getOrRaise(
            new RuntimeException(s"No row for $descriptor found, which was just inserted!")
          )

        _ <- storage
          .update(
            sql"""
             insert into store_last_ingested_offsets (store_id, migration_id)
             values (${newStoreId}, ${domainMigrationId})
             on conflict do nothing
             """.asUpdate,
            "initializeDescriptor.3",
          )
        lastIngestedOffset <- storage
          .querySingle(
            sql"""
             select last_ingested_offset
             from store_last_ingested_offsets
             where store_id = ${newStoreId} and migration_id = $domainMigrationId
             """.as[Option[String]].headOption,
            "initializeDescriptor.4",
          )
          .getOrRaise(
            new RuntimeException(s"No row for $newStoreId found, which was just inserted!")
          )
          .map(_.map(LegacyOffset.Api.assertFromStringToLong(_)))
      } yield lastIngestedOffset match {
        case Some(offset) => StoreHasData(newStoreId, offset)
        case None => StoreHasNoData(newStoreId)
      }
    }

    override def initialize()(implicit traceContext: TraceContext): Future[IngestionStart] = {
      for {
        acsInitResult <- initializeDescriptor(acsStoreDescriptor).map(AcsStoreId.subst)
        txLogInitResult <- txLogStoreDescriptor match {
          case Some(descriptor) => initializeDescriptor(descriptor).map(TxLogStoreId.subst)
          case None => Future.successful(StoreNotUsed[TxLogStoreId]())
        }
        _ <- txLogInitResult match {
          case StoreHasData(txLogStoreId, _) => cleanUpDataAfterDomainMigration(txLogStoreId)
          case StoreHasNoData(txLogStoreId) => cleanUpDataAfterDomainMigration(txLogStoreId)
          case _ => Future.unit
        }

        acsSizeInDb <- acsInitResult match {
          case StoreHasData(acsStoreId, _) =>
            storage
              .querySingle(
                sql"""
                  select count(*)
                  from #$acsTableName
                  where store_id = ${acsStoreId} and migration_id = $domainMigrationId
                  """.as[Int].headOption,
                "initialize.getAcsCount",
              )
              .getOrElse(0)
          case _ => FutureUnlessShutdown.pure(0)
        }
      } yield {
        def initState(
            acsStoreId: AcsStoreId,
            txLogStoreId: Option[TxLogStoreId],
            lastIngestedOffset: Option[Long],
        ): Unit = {
          // Note: IngestionSink.initialize() may be called multiple times for the same store instance,
          // if for example the ingestion loop restarts.
          val oldState = state.getAndUpdate(
            _.withInitialState(
              acsStoreId = acsStoreId,
              txLogStoreId = txLogStoreId,
              acsSizeInDb = acsSizeInDb,
              lastIngestedOffset = lastIngestedOffset,
            )
          )
          lastIngestedOffset.foreach(oldState.signalOffsetChanged)
        }

        (acsInitResult, txLogInitResult) match {
          case (StoreNotUsed(), _) =>
            throw new RuntimeException(s"ACS store is not optional.")
          case (StoreHasData(acsStoreId, acsOffset), StoreHasData(txLogStoreId, txLogOffset)) =>
            logger.info(
              s"Acs store $acsStoreDescriptor with id $acsStoreId and TxLog store $txLogStoreDescriptor with id $txLogStoreId " +
                s"both have ingested data in migration $domainMigrationId up to offset $txLogOffset. " +
                s"Resuming ingestion at offset $acsOffset."
            )
            assert(
              acsOffset == txLogOffset,
              s"ACS offset $acsOffset is out of sync with TxLog offset $txLogOffset. " +
                "This should never happen, as we ingest into both stores in one SQL transaction.",
            )
            initState(acsStoreId, Some(txLogStoreId), Some(acsOffset))
            finishedAcsIngestion.trySuccess(()).discard
            IngestionStart.ResumeAtOffset(
              acsOffset
            )
          case (StoreHasData(acsStoreId, acsOffset), StoreHasNoData(txLogStoreId)) =>
            logger.info(
              s"Acs store $acsStoreDescriptor with id $acsStoreId has ingested data in migration $domainMigrationId up to offset $acsOffset. " +
                s"TxLog store $txLogStoreDescriptor with id $txLogStoreId has not ingested any data, presumably because it was reset. " +
                s"Resuming ingestion at offset $acsOffset, TxLog backfilling will start restoring previous entries."
            )
            initState(acsStoreId, Some(txLogStoreId), Some(acsOffset))
            finishedAcsIngestion.trySuccess(()).discard
            IngestionStart.ResumeAtOffset(
              acsOffset
            )
          case (StoreHasData(acsStoreId, acsOffset), StoreNotUsed()) =>
            logger.info(
              s"Acs store $acsStoreDescriptor with id $acsStoreId has ingested data in migration $domainMigrationId up to offset $acsOffset. " +
                s"Resuming ingestion at offset $acsOffset."
            )
            initState(acsStoreId, None, Some(acsOffset))
            finishedAcsIngestion.trySuccess(()).discard
            IngestionStart.ResumeAtOffset(
              acsOffset
            )
          case (StoreHasNoData(acsStoreId), StoreHasData(txLogStoreId, txLogOffset)) =>
            logger.info(
              s"TxLog store $txLogStoreDescriptor with id $txLogStoreId has ingested data in migration $domainMigrationId up to offset $txLogOffset. " +
                s"Acs store $acsStoreDescriptor with id $acsStoreId has not ingested any data, presumably because it was reset. " +
                s"Initializing the ACS at offset $txLogOffset, and resuming ingestion from there."
            )
            initState(acsStoreId, Some(txLogStoreId), Some(txLogOffset))
            IngestionStart.InitializeAcsAtOffset(txLogOffset)
          case (StoreHasNoData(acsStoreId), StoreHasNoData(txLogStoreId)) =>
            logger.info(
              s"Acs store $acsStoreDescriptor with id $acsStoreId and TxLog store $txLogStoreDescriptor with id $txLogStoreId " +
                s"both have not ingested any data for migration $domainMigrationId. " +
                s"Either both stores were reset, or the app is starting for the first time on this migration." +
                s"Initializing the ACS at an offset chosen by the ingestion service, and resuming ingestion from there."
            )
            initState(acsStoreId, Some(txLogStoreId), None)
            IngestionStart.InitializeAcsAtLatestOffset
          case (StoreHasNoData(acsStoreId), StoreNotUsed()) =>
            logger.info(
              s"Acs store $acsStoreDescriptor with id $acsStoreId has not ingested any data for migration $domainMigrationId. " +
                s"Either the store was reset, or the app is starting for the first time on this migration." +
                s"Initializing the ACS at an offset chosen by the ingestion service, and resuming ingestion from there."
            )
            initState(acsStoreId, None, None)
            IngestionStart.InitializeAcsAtLatestOffset
        }
      }
    }

    // Note: returns a DBIOAction, as updating the offset needs to happen in the same SQL transaction
    // that modifies the ACS/TxLog.
    private def updateOffset(offset: Long): DBIOAction[Unit, NoStream, Effect.Write] = {
      DBIO.seq(
        sql"""
        update store_last_ingested_offsets
        set last_ingested_offset = ${lengthLimited(LegacyOffset.Api.fromLong(offset))}
        where store_id = $acsStoreId and migration_id = $domainMigrationId
      """.asUpdate,
        if (txLogStoreDescriptor.isDefined) {
          sql"""
            update store_last_ingested_offsets
            set last_ingested_offset = ${lengthLimited(LegacyOffset.Api.fromLong(offset))}
            where store_id = $txLogStoreId and migration_id = $domainMigrationId
          """.asUpdate
        } else {
          DBIO.unit
        },
      )
    }

    /** Runs the given action to update the database with changes caused at the given offset.
      * The resulting action is guaranteed to be idempotent, even if the given action is not.
      *
      * Note: our storage layer automatically retries database actions that have failed with transient errors.
      * In some cases, it is not known whether the failed action was committed to the database. We therefore have
      * to inspect the last ingested offset, run any updates, and update the last ingested offset, all within one
      * SQL transaction.
      */
    private def ingestUpdateAtOffset[E <: Effect](
        offset: Long,
        action: DBIOAction[?, NoStream, Effect.Read & Effect.Write],
        isOffsetCheckpoint: Boolean = false,
    )(implicit
        tc: TraceContext
    ): DBIOAction[Unit, NoStream, Effect.Read & Effect.Write & Effect.Transactional] = {
      readOffsetAction()
        .flatMap({
          case None =>
            action.andThen(updateOffset(offset))
          case Some(lastIngestedOffset) =>
            if (offset <= lastIngestedOffset) {
              /* we can receive an offset equal to the last ingested and that can be safely ignore */
              if (isOffsetCheckpoint) {
                if (offset < lastIngestedOffset) {
                  logger.warn(
                    s"Checkpoint offset $offset < last ingested offset $lastIngestedOffset for DbMultiDomainAcsStore(storeId=$acsStoreId), skipping database actions. This is expected if the SQL query was automatically retried after a transient database error. Otherwise, this is unexpected and most likely caused by two identical UpdateIngestionService instances ingesting into the same logical database."
                  )
                }
              } else {
                logger.warn(
                  s"Update offset $offset <= last ingested offset $lastIngestedOffset for DbMultiDomainAcsStore(storeId=$acsStoreId), skipping database actions. This is expected if the SQL query was automatically retried after a transient database error. Otherwise, this is unexpected and most likely caused by two identical UpdateIngestionService instances ingesting into the same logical database."
                )
              }
              DBIO.successful(())
            } else {
              action.andThen(updateOffset(offset))
            }
        })
        .transactionally
    }
    override def ingestAcs(
        offset: Long,
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
    )(implicit traceContext: TraceContext): Future[Unit] = {
      if (finishedAcsIngestion.isCompleted) {
        Future.failed(
          new RuntimeException(
            s"ACS was already ingested for store $acsStoreId, cannot ingest again"
          )
        )
      } else {
        // Filter out all contracts we are not interested in
        val todoAcs = acs
          .filter(contract => contractFilter.contains(contract.createdEvent))
        todoAcs.foreach { contract =>
          contractFilter.ensureStakeholderOf(contract.createdEvent)
        }
        val todoIncompleteOut = incompleteOut
          .filter(event => contractFilter.contains(event.createdEvent))
        todoIncompleteOut.foreach { event =>
          contractFilter.ensureStakeholderOf(event.createdEvent)
        }
        val todoIncompleteIn = incompleteIn
          .filter(event => contractFilter.contains(event.reassignmentEvent.createdEvent))
        todoIncompleteIn.foreach { event =>
          contractFilter.ensureStakeholderOf(event.reassignmentEvent.createdEvent)
        }

        val summaryState = MutableIngestionSummary.empty
        for {
          _ <- storage
            .queryAndUpdate(
              ingestUpdateAtOffset(
                offset,
                DBIO
                  .sequence(
                    // TODO (#989): batch inserts
                    todoAcs.map { ac =>
                      for {
                        _ <- doIngestAcsInsert(
                          offset,
                          ac.createdEvent,
                          stateRowDataFromActiveContract(ac.synchronizerId, ac.reassignmentCounter),
                          summaryState,
                        )
                      } yield ()
                    }
                      ++ todoIncompleteOut.map { evt =>
                        for {
                          _ <- doIngestAcsInsert(
                            offset,
                            evt.createdEvent,
                            stateRowDataFromUnassign(evt.reassignmentEvent),
                            summaryState,
                          )
                          _ <- doRegisterIncompleteReassignment(
                            evt.createdEvent.getContractId,
                            evt.reassignmentEvent.source,
                            evt.reassignmentEvent.unassignId,
                            isAssignment = false,
                            summaryState,
                          )
                        } yield ()
                      }
                      ++ todoIncompleteIn.map { evt =>
                        for {
                          _ <- doIngestAcsInsert(
                            offset,
                            evt.reassignmentEvent.createdEvent,
                            stateRowDataFromAssign(evt.reassignmentEvent),
                            summaryState,
                          )
                          _ <- doRegisterIncompleteReassignment(
                            evt.reassignmentEvent.createdEvent.getContractId,
                            evt.reassignmentEvent.source,
                            evt.reassignmentEvent.unassignId,
                            isAssignment = true,
                            summaryState,
                          )
                        } yield ()
                      }
                  ),
              ),
              "ingestAcs",
            )
        } yield {
          val newAcsSize = summaryState.acsSizeDiff
          val summary = summaryState.toIngestionSummary(
            updateId = None,
            synchronizerId = None,
            offset = offset,
            recordTime = None,
            newAcsSize = newAcsSize,
            metrics,
          )
          state
            .getAndUpdate(
              _.withUpdate(newAcsSize, offset)
            )
            .signalOffsetChanged(offset)

          logger.debug(show"Ingested complete ACS at offset $offset: $summary")
          handleIngestionSummary(summary)

          finishedAcsIngestion.success(())
          logger.info(
            s"Store $acsStoreId ingested the ACS and switched to ingesting updates at $offset"
          )
        }
      }
    }

    override def ingestUpdate(updateOrCheckpoint: TreeUpdateOrOffsetCheckpoint)(implicit
        traceContext: TraceContext
    ): Future[Unit] = {
      updateOrCheckpoint match {
        case TreeUpdateOrOffsetCheckpoint.Update(ReassignmentUpdate(reassignment), domain) =>
          ingestReassignment(reassignment.offset, reassignment).map { summaryState =>
            state
              .getAndUpdate(s =>
                s.withUpdate(
                  s.acsSize + summaryState.acsSizeDiff,
                  reassignment.offset,
                )
              )
              .signalOffsetChanged(reassignment.offset)
            val summary =
              summaryState.toIngestionSummary(
                updateId = None,
                synchronizerId = Some(domain),
                offset = reassignment.offset,
                recordTime = Some(reassignment.recordTime),
                newAcsSize = state.get().acsSize,
                metrics,
              )
            logger.debug(show"Ingested reassignment $summary")
            handleIngestionSummary(summary)
          }
        case TreeUpdateOrOffsetCheckpoint.Update(TransactionTreeUpdate(tree), domain) =>
          val offset = tree.getOffset
          ingestTransactionTree(domain, offset, tree).map { summaryState =>
            state
              .getAndUpdate(s =>
                s.withUpdate(
                  s.acsSize + summaryState.acsSizeDiff,
                  offset,
                )
              )
              .signalOffsetChanged(offset)
            val summary =
              summaryState.toIngestionSummary(
                updateId = Some(tree.getUpdateId),
                synchronizerId = Some(domain),
                offset = offset,
                recordTime = Some(CantonTimestamp.assertFromInstant(tree.getRecordTime)),
                newAcsSize = state.get().acsSize,
                metrics,
              )
            logger.debug(show"Ingested transaction $summary")
            handleIngestionSummary(summary)
          }
        case TreeUpdateOrOffsetCheckpoint.Checkpoint(checkpoint) =>
          val offset = checkpoint.getOffset
          storage
            .queryAndUpdate(
              ingestUpdateAtOffset(offset, DBIO.unit, isOffsetCheckpoint = true),
              "ingestOffsetCheckpoint",
            )
            .map { _ =>
              state
                .getAndUpdate(s => s.withUpdate(s.acsSize, offset))
                .signalOffsetChanged(offset)
              val summary =
                MutableIngestionSummary.empty.toIngestionSummary(
                  updateId = None,
                  synchronizerId = None,
                  offset = offset,
                  recordTime = None,
                  newAcsSize = state.get().acsSize,
                  metrics,
                )
              logger.debug(show"Ingested offset checkpoint $offset")
              handleIngestionSummary(summary)
            }

      }
    }

    private def ingestReassignment(
        offset: Long,
        reassignment: Reassignment[ReassignmentEvent],
    )(implicit tc: TraceContext): Future[MutableIngestionSummary] = {
      val summary = MutableIngestionSummary.empty
      for {
        _ <- storage
          .queryAndUpdate(
            ingestUpdateAtOffset(
              offset,
              DBIO
                .seq(
                  reassignment.event match {
                    case assign: ReassignmentEvent.Assign
                        if !contractFilter.contains(assign.createdEvent) =>
                      summary.numFilteredAssignEvents += 1
                      DBIO.successful(())
                    case assign: ReassignmentEvent.Assign =>
                      contractFilter.ensureStakeholderOf(assign.createdEvent)
                      for {
                        case Seq(_hasIncompleteReassignments, _hasAcsEntry) <- DBIO.sequence(
                          Seq(
                            hasIncompleteReassignments(assign.createdEvent.getContractId),
                            hasAcsEntry(assign.createdEvent.getContractId),
                          )
                        )
                        alreadyArchived = !_hasAcsEntry & _hasIncompleteReassignments
                        _ <-
                          if (alreadyArchived) {
                            doRegisterIncompleteReassignment(
                              assign.createdEvent.getContractId,
                              assign.source,
                              assign.unassignId,
                              true,
                              summary,
                            )
                          } else if (_hasAcsEntry) {
                            DBIO.seq(
                              doSetContractStateActive(
                                assign.createdEvent.getContractId,
                                assign.target,
                                assign.counter,
                                summary,
                              ),
                              doRegisterIncompleteReassignment(
                                assign.createdEvent.getContractId,
                                assign.source,
                                assign.unassignId,
                                true,
                                summary,
                              ),
                            )
                          } else {
                            DBIO.seq(
                              doIngestAcsInsert(
                                reassignment.offset,
                                assign.createdEvent,
                                stateRowDataFromAssign(assign),
                                summary,
                              ),
                              doRegisterIncompleteReassignment(
                                assign.createdEvent.getContractId,
                                assign.source,
                                assign.unassignId,
                                true,
                                summary,
                              ),
                            )
                          }
                      } yield ()
                    case unassign: ReassignmentEvent.Unassign =>
                      for {
                        case Seq(_hasIncompleteReassignments, _hasAcsEntry) <- DBIO.sequence(
                          Seq(
                            hasIncompleteReassignments(unassign.contractId.contractId),
                            hasAcsEntry(unassign.contractId.contractId),
                          )
                        )
                        filteredOut = !_hasAcsEntry & !_hasIncompleteReassignments
                        alreadyArchived = !_hasAcsEntry & _hasIncompleteReassignments
                        _ <-
                          if (filteredOut) {
                            summary.numFilteredUnassignEvents += 1
                            DBIO.successful(())
                          } else if (alreadyArchived) {
                            doRegisterIncompleteReassignment(
                              unassign.contractId.contractId,
                              unassign.source,
                              unassign.unassignId,
                              false,
                              summary,
                            )
                          } else {
                            DBIO.seq(
                              doSetContractStateInFlight(
                                unassign,
                                summary,
                              ),
                              doRegisterIncompleteReassignment(
                                unassign.contractId.contractId,
                                unassign.source,
                                unassign.unassignId,
                                false,
                                summary,
                              ),
                            )
                          }
                      } yield ()
                  }
                ),
            ),
            "ingestReassignment",
          )
      } yield summary
    }

    private def ingestTransactionTree(
        synchronizerId: SynchronizerId,
        offset: Long,
        tree: TransactionTree,
    )(implicit tc: TraceContext): Future[MutableIngestionSummary] = {
      val summary = MutableIngestionSummary.empty

      val workTodo = Trees
        .foldTree(
          tree,
          VectorMap.empty[String, OperationToDo],
        )(
          onCreate = (st, ev, _) => {
            if (contractFilter.contains(ev)) {
              contractFilter.ensureStakeholderOf(ev)
              st + (ev.getContractId -> Insert(
                ev
              ))
            } else {
              summary.numFilteredCreatedEvents += 1
              st
            }
          },
          onExercise = (st, ev, _) => {
            if (ev.isConsuming && contractFilter.shouldArchive(ev)) {
              // optimization: a delete on a contract cancels-out with the corresponding insert
              if (st.contains(ev.getContractId)) {
                st - ev.getContractId
              } else {
                st + (ev.getContractId -> Delete(ev))
              }
            } else {
              st
            }
          },
        )
        .toVector
        .map(_._2)
      val txLogEntries =
        if (!tree.getWorkflowId.startsWith(IMPORT_ACS_WORKFLOW_ID_PREFIX))
          txLogConfig.parser.parse(tree, synchronizerId, logger)
        else Seq.empty // do not parse events imported from acs

      for {
        _ <- storage
          .queryAndUpdate(
            ingestUpdateAtOffset(
              offset,
              DBIO
                .seq(
                  DBIO.seq(workTodo.map({
                    case Insert(createdEvent) =>
                      for {
                        alreadyArchived <- hasIncompleteReassignments(createdEvent.getContractId)
                        _ <-
                          if (alreadyArchived) {
                            DBIO.successful(())
                          } else {
                            DBIO.seq(
                              doIngestAcsInsert(
                                offset,
                                createdEvent,
                                stateRowDataFromActiveContract(synchronizerId, 0L),
                                summary,
                              )
                            )
                          }
                      } yield ()
                    case Delete(exercisedEvent) =>
                      doDeleteContract(exercisedEvent, summary)
                  })*),
                  DBIO.seq(txLogEntries.map { txe =>
                    doIngestTxLogInsert(
                      domainMigrationId,
                      synchronizerId,
                      offset,
                      CantonTimestamp.assertFromInstant(tree.getRecordTime),
                      txe,
                      summary,
                    )
                  }*),
                  doInitializeFirstIngestedUpdate(
                    synchronizerId,
                    domainMigrationId,
                    CantonTimestamp.assertFromInstant(tree.getRecordTime),
                  ),
                ),
            ),
            "ingestTransactionTree",
          )
      } yield summary
    }

    private def hasAcsEntry(contractId: String) = (sql"""
           select count(*) from #$acsTableName
           where store_id = $acsStoreId and migration_id = $domainMigrationId and contract_id = ${lengthLimited(
        contractId
      )}
          """).as[Int].head.map(_ > 0)

    private def hasIncompleteReassignments(contractId: String) = (sql"""
           select count(*) from incomplete_reassignments
           where store_id = $acsStoreId and migration_id = $domainMigrationId and contract_id = ${lengthLimited(
        contractId
      )}
          """).as[Int].head.map(_ > 0)

    private def stateRowDataFromActiveContract(
        synchronizerId: SynchronizerId,
        reassignmentCounter: Long,
    ) = ContractStateRowData(
      assignedDomain = Some(synchronizerId),
      reassignmentCounter = reassignmentCounter,
      reassignmentTargetDomain = None,
      reassignmentSourceDomain = None,
      reassignmentSubmitter = None,
      reassignmentUnassignId = None,
    )

    private def stateRowDataFromAssign(
        event: ReassignmentEvent.Assign
    ) = ContractStateRowData(
      assignedDomain = Some(event.target),
      reassignmentCounter = event.counter,
      reassignmentTargetDomain = None,
      reassignmentSourceDomain = None,
      reassignmentSubmitter = None,
      reassignmentUnassignId = None,
    )

    private def stateRowDataFromUnassign(
        event: ReassignmentEvent.Unassign
    ) = ContractStateRowData(
      assignedDomain = None,
      reassignmentCounter = event.counter,
      reassignmentTargetDomain = Some(event.target),
      reassignmentSourceDomain = Some(event.source),
      reassignmentSubmitter = Some(event.submitter),
      reassignmentUnassignId = Some(String255.tryCreate(event.unassignId)),
    )

    private def doIngestAcsInsert(
        offset: Long,
        createdEvent: CreatedEvent,
        stateData: ContractStateRowData,
        summary: MutableIngestionSummary,
    )(implicit
        tc: TraceContext
    ) = {
      (
        contractFilter.matchingInterfaceRows(createdEvent),
        contractFilter.matchingContractToRow(createdEvent),
      ) match {
        case (Some((fallbackRowData, interfaces)), rowData) =>
          // For the acs_table row:
          // If only the interface filter matches, we use the "bare minimum" row data from the interface filter
          // that does not contain any index columns, as the interface table needs to reference the acs_table.
          // If both match, we want to use the row data from the template filter,
          // as that one contains all the information.
          insertContract(rowData.getOrElse(fallbackRowData), createdEvent, stateData, summary)
            .flatMap { eventNumber =>
              DBIO.sequence(interfaces.map { interfaceRow =>
                val interfaceId = interfaceRow.interfaceId
                val interfaceIdQualifiedName = QualifiedName(interfaceId)
                val interfaceIdPackageId = lengthLimited(interfaceId.getPackageId)
                val viewJson =
                  AcsJdbcTypes.payloadJsonFromDefinedDataType(interfaceRow.interfaceView)
                val indexColumnNames = getIndexColumnNames(interfaceRow.indexColumns)
                val indexColumnNameValues = getIndexColumnValues(interfaceRow.indexColumns)
                (sql"""
                insert into #$interfaceViewsTableName(acs_event_number, interface_id_package_id, interface_id_qualified_name, interface_view #$indexColumnNames)
                values ($eventNumber, $interfaceIdPackageId, $interfaceIdQualifiedName, $viewJson """ ++ indexColumnNameValues ++ sql")").toActionBuilder.asUpdate
              })
            }
        case (None, Some(rowData)) =>
          insertContract(rowData, createdEvent, stateData, summary)
        case _ =>
          val errMsg =
            s"Item at offset $offset with contract id ${createdEvent.getContractId} cannot be ingested."
          logger.error(errMsg)
          throw new IllegalArgumentException(errMsg)
      }
    }

    private def insertContract(
        rowData: AcsRowData,
        createdEvent: CreatedEvent,
        stateData: ContractStateRowData,
        summary: MutableIngestionSummary,
    ): DBIOAction[Long, NoStream, Effect.Write & Effect.Read] = {
      summary.ingestedCreatedEvents.addOne(createdEvent)

      val contractId = rowData.contractId.asInstanceOf[ContractId[Any]]
      val templateId = rowData.identifier
      val templateIdQualifiedName = QualifiedName(templateId)
      val templateIdPackageId = lengthLimited(rowData.identifier.getPackageId)
      val createArguments = rowData.payload
      val createdAt = Timestamp.assertFromInstant(rowData.createdAt)
      val contractExpiresAt = rowData.contractExpiresAt
      val createdEventBlob = rowData.createdEventBlob
      val ContractStateRowData(
        assignedDomain,
        reassignmentCounter,
        reassignmentTargetDomain,
        reassignmentSourceDomain,
        reassignmentSubmitter,
        reassignmentUnassignId,
      ) = stateData

      val indexColumnNames = getIndexColumnNames(rowData.indexColumns)
      val indexColumnNameValues = getIndexColumnValues(rowData.indexColumns)

      import storage.DbStorageConverters.setParameterByteArray
      (sql"""
                insert into #$acsTableName(store_id, migration_id, contract_id, template_id_package_id, template_id_qualified_name,
                                           create_arguments, created_event_blob, created_at, contract_expires_at,
                                           assigned_domain, reassignment_counter, reassignment_target_domain,
                                           reassignment_source_domain, reassignment_submitter, reassignment_unassign_id
                                           #$indexColumnNames)
                values ($acsStoreId, $domainMigrationId, $contractId, $templateIdPackageId, $templateIdQualifiedName,
                        $createArguments, $createdEventBlob, $createdAt, $contractExpiresAt,
                        $assignedDomain, $reassignmentCounter, $reassignmentTargetDomain,
                        $reassignmentSourceDomain, $reassignmentSubmitter, $reassignmentUnassignId
              """ ++ indexColumnNameValues ++ sql") returning event_number").toActionBuilder
        .asUpdateReturning[Long]
        .head
    }

    private def doDeleteContract(event: ExercisedEvent, summary: MutableIngestionSummary) = {
      sqlu"""
        delete from #$acsTableName
        where store_id = $acsStoreId
          and migration_id = $domainMigrationId
          and contract_id = ${lengthLimited(event.getContractId)}
      """.map {
        case 1 =>
          summary.ingestedArchivedEvents.addOne(event)
        case _ =>
          // there was actually no contract with that id. This can happen because:
          // `contractFilter.mightContain` in `getIngestionWork` can return true for a template,
          // but that might still satisfy some other filter, so the contract was never inserted
          summary.numFilteredArchivedEvents += 1
      }
    }

    private def doSetContractStateInFlight(
        event: ReassignmentEvent.Unassign,
        summary: MutableIngestionSummary,
    ) = {
      val safeUnassignId = lengthLimited(event.unassignId)
      summary.updatedContractStates.addOne(
        ContractStateEvent(
          event.contractId,
          event.counter,
          StoreContractState.InFlight(event),
        )
      )
      // Only overwrite the current contract state if existing row is "older", i.e., it has
      // a reassignment counter smaller than the state we are trying to insert.
      // Note: reassignment counters increase with Unassign events. Corresponding Assign and Unassign events
      // have the same reassignment counter.
      sqlu"""
        update #$acsTableName
            set
                state_number = default, -- generates a new identity value
                assigned_domain = NULL,
                reassignment_counter = ${event.counter},
                reassignment_target_domain = ${event.target},
                reassignment_source_domain = ${event.source},
                reassignment_submitter = ${event.submitter},
                reassignment_unassign_id = $safeUnassignId
            where
                store_id = $acsStoreId and migration_id = $domainMigrationId and contract_id = ${event.contractId} and
                #$acsTableName.reassignment_counter < ${event.counter}
      """
    }

    private def doSetContractStateActive(
        contractId: String,
        synchronizerId: SynchronizerId,
        reassignmentCounter: Long,
        summary: MutableIngestionSummary,
    ) = {
      val safeContractId = lengthLimited(contractId)
      summary.updatedContractStates.addOne(
        ContractStateEvent(
          new ContractId(contractId),
          reassignmentCounter,
          StoreContractState.Assigned(synchronizerId),
        )
      )
      // Only overwrite the current contract state if existing row is "older", i.e., it has
      // a reassignment counter smaller or equal to the state we are trying to insert.
      // Note: reassignment counters increase with Unassign events. Corresponding Assign and Unassign events
      // have the same reassignment counter.
      sqlu"""
        update #$acsTableName
            set
                state_number = default, -- generates a new identity value
                assigned_domain = $synchronizerId,
                reassignment_counter = $reassignmentCounter,
                reassignment_target_domain = NULL,
                reassignment_source_domain = NULL,
                reassignment_submitter = NULL,
                reassignment_unassign_id = NULL
            where
                store_id = $acsStoreId and migration_id = $domainMigrationId and contract_id = $safeContractId and
                #$acsTableName.reassignment_counter <= $reassignmentCounter
      """
    }

    private def doRegisterIncompleteReassignment(
        contractId: String,
        source: SynchronizerId,
        unassignId: String,
        isAssignment: Boolean,
        summary: MutableIngestionSummary,
    ) = {
      val safeContractId = lengthLimited(contractId)
      val safeUnassignId = lengthLimited(unassignId)
      // If there is a matching "unassign" row, remove it (the reassignment is now complete).
      // Otherwise, add a new "assign" row (register the incomplete reassignment)
      sql"""
        select count(*) from incomplete_reassignments
        where store_id = $acsStoreId and migration_id = $domainMigrationId and contract_id = $safeContractId and unassign_id = $safeUnassignId and is_assignment = ${!isAssignment}
          """
        .as[Int]
        .head
        .flatMap(existingUnassignRows => {
          if (existingUnassignRows > 0) {
            if (isAssignment) {
              summary.removedAssignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            } else {
              summary.removedUnassignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            }
            sqlu"""
            delete from incomplete_reassignments
            where store_id = $acsStoreId and migration_id = $domainMigrationId and contract_id = $safeContractId and unassign_id = $safeUnassignId and is_assignment = ${!isAssignment}
              """
          } else {
            if (isAssignment) {
              summary.addedAssignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            } else {
              summary.addedUnassignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            }
            sqlu"""
            insert into incomplete_reassignments(store_id, migration_id, contract_id, source_domain, unassign_id, is_assignment)
            values ($acsStoreId, $domainMigrationId, $safeContractId, $source, $safeUnassignId, $isAssignment)
            on conflict do nothing
              """
          }
        })
    }

    sealed trait OperationToDo
    case class Insert(evt: CreatedEvent) extends OperationToDo
    case class Delete(evt: ExercisedEvent) extends OperationToDo
  }

  private def getIndexColumnValues(data: Seq[(String, IndexColumnValue[?])]): SQLActionBuilder =
    data
      .map(_._2)
      .map(v => sql"$v")
      .reduceOption { (acc, next) =>
        (acc ++ sql"," ++ next).toActionBuilder
      }
      .map(s => (sql"," ++ s).toActionBuilder)
      .getOrElse(sql"")

  // Note: the column names are hardcoded so they're safe to interpolate raw
  private def getIndexColumnNames(data: Seq[(String, IndexColumnValue[?])]): String =
    if (data.isEmpty) ""
    else data.map(_._1).mkString(",", ", ", "")

  private def doIngestTxLogInsert(
      migrationId: Long,
      domainId: SynchronizerId,
      offset: Long,
      recordTime: CantonTimestamp,
      txe: TXE,
      summary: MutableIngestionSummary,
  ) = {
    val safeOffset = lengthLimited(LegacyOffset.Api.fromLong(offset))
    val (entryType, entryData) = txLogConfig.encodeEntry(txe)
    // Note: lengthLimited() uses String2066 which throws an exception if the string is longer than 2066 characters.
    // Here we use String256M to support larger TxLogEntry payloads.
    val safeEntryData = String256M.tryCreate(entryData)
    val rowData = txLogConfig.entryToRow(txe)
    val indexColumnNames = getIndexColumnNames(rowData.indexColumns)
    val indexColumnNameValues = getIndexColumnValues(rowData.indexColumns)

    summary.ingestedTxLogEntries.addOne((entryType, entryData))
    (sql"""
      insert into #$txLogTableName(store_id, migration_id, transaction_offset, record_time, domain_id,
      entry_type, entry_data #$indexColumnNames)
      values ($txLogStoreId, $migrationId, $safeOffset, $recordTime, $domainId,
              $entryType, ${safeEntryData}::jsonb""" ++ indexColumnNameValues ++ sql""")
    """).toActionBuilder.asUpdate
  }

  private def doUpdateFirstIngestedUpdate(
      synchronizerId: SynchronizerId,
      migrationId: Long,
      recordTime: CantonTimestamp,
  ) = {
    sqlu"""
      insert into txlog_first_ingested_update (store_id, migration_id, synchronizer_id, record_time)
      values ($txLogStoreId, $migrationId, $synchronizerId, $recordTime)
      on conflict (store_id, migration_id, synchronizer_id) do update set record_time = $recordTime
    """
  }

  private def doInitializeFirstIngestedUpdate(
      synchronizerId: SynchronizerId,
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext) = {
    // - doUpdateFirstIngestedUpdate: called by the backfilling process, always overwrites the existing value
    // - doInitializeFirstIngestedUpdate: called by the ingestion process, only inserts a new value if it doesn't exist
    //
    // The backfilling process won't process a synchronizer until there is at least one entry in the
    // txlog for that synchronizer. The two operations will therefore be called in the following order:
    // 1. doInitializeFirstIngestedUpdate() is called once and inserts a new row.
    // 2. doUpdateFirstIngestedUpdate() and doInitializeFirstIngestedUpdate() are called concurrently.
    //    The former updates the existing row, the latter does nothing.
    //
    // This method could be optimized by keeping a cache for which synchronizers have been initialized,
    // and not doing anything if the synchronizer is already in the cache.
    if (txLogStoreDescriptor.isDefined) {
      if (recordTime > CantonTimestamp.MinValue) {
        sqlu"""
          insert into txlog_first_ingested_update (store_id, migration_id, synchronizer_id, record_time)
          values ($txLogStoreId, $migrationId, $synchronizerId, $recordTime)
          on conflict do nothing
        """
      } else {
        logger.debug("Skipping initialization of txlog_first_ingested_update for import updates")
        DBIOAction.unit
      }
    } else {
      DBIOAction.unit
    }
  }

  private[this] def cleanUpDataAfterDomainMigration(
      txLogStoreId: TxLogStoreId
  )(implicit tc: TraceContext): Future[Unit] = {
    txLogTableNameOpt.fold(Future.unit) { _ =>
      val previousMigrationId = domainMigrationInfo.currentMigrationId - 1
      domainMigrationInfo.acsRecordTime match {
        case Some(acsRecordTime) =>
          deleteRolledBackTxLogEntries(txLogStoreId, previousMigrationId, acsRecordTime)
        case _ =>
          logger.debug("No previous domain migration, not checking or deleting txlog entries")
          Future.unit
      }
    }
  }

  private[this] def deleteRolledBackTxLogEntries(
      txLogStoreId: TxLogStoreId, // Not using the storeId from the state, as the state might not be updated yet
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext) = {
    logger.info(
      s"Deleting all txlog entries for $txLogStoreDescriptor where migration = $migrationId and record time > $recordTime"
    )
    val action =
      sqlu"""
            delete from #$txLogTableName
            where store_id = $txLogStoreId and migration_id = $migrationId and record_time > $recordTime
          """.map(rows =>
        if (rows > 0) {
          logger.info(
            s"Deleted $rows txlog entries for $txLogStoreDescriptor where migration_id = $migrationId and record_time > $recordTime. " +
              "This is expected during a disaster recovery, where we are rolling back the domain to a previous state. " +
              "In is NOT expected during regular hard domain migrations."
          )
        } else {
          logger.info(s"No entries deleted for $txLogStoreDescriptor.")
        }
      )
    storage.update(action, "deleteRolledBackTxLogEntries")
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def reassignmentEventUnassignFromRow(
      row: SelectFromAcsTableWithStateResult
  ): ReassignmentEvent.Unassign = {
    assert(row.stateRow.assignedDomain.isEmpty)
    ReassignmentEvent.Unassign(
      submitter = row.stateRow.reassignmentSubmitter.get,
      source = row.stateRow.reassignmentSourceDomain.get,
      target = row.stateRow.reassignmentTargetDomain.get,
      unassignId = row.stateRow.reassignmentUnassignId.get,
      contractId = row.acsRow.contractId,
      counter = row.stateRow.reassignmentCounter,
    )
  }

  private def readOffsetAction(): DBIOAction[Option[Long], NoStream, Effect.Read] = {
    // Note: we only read from the acs store.
    // Initialization makes sure that both the acs store and the txlog store start at the same offset,
    // and we update the store_last_ingested_offsets row for both stores in the same transaction.
    sql"""
        select last_ingested_offset
        from store_last_ingested_offsets
        where store_id = $acsStoreId and migration_id = $domainMigrationId
      """
      .as[Option[String]]
      .head
      .map(_.map(LegacyOffset.Api.assertFromStringToLong(_)))
  }

  private[store] def lookupLastIngestedOffset()(implicit tc: TraceContext): Future[Option[Long]] = {
    storage.query(readOffsetAction(), "readOffset")
  }

  override def close(): Unit =
    metrics.close()
}

object DbMultiDomainAcsStore {

  /** @param acsStoreId The primary key of this stores ACS entry in the store_descriptors table
    * @param txLogStoreId The primary key of this stores TxLog entry in the store_descriptors table
    * @param offset The last ingested offset, if any
    * @param acsSize The number of active contracts in the store
    * @param offsetChanged A promise that is not yet completed, and will be completed the next time the offset changes
    * @param offsetIngestionsToSignal A map from offsets to promises. The keys are offsets that are not ingested yet.
    *                                 The values are promises that are not completed, and will be completed when
    *                                 the corresponding offset is ingested.
    */
  private case class State(
      acsStoreId: Option[AcsStoreId],
      txLogStoreId: Option[TxLogStoreId],
      offset: Option[Long],
      acsSize: Int,
      offsetChanged: Promise[Unit],
      offsetIngestionsToSignal: SortedMap[Long, Promise[Unit]],
  ) {
    def withInitialState(
        acsStoreId: AcsStoreId,
        txLogStoreId: Option[TxLogStoreId],
        acsSizeInDb: Int,
        lastIngestedOffset: Option[Long],
    ): State = {
      assert(
        !offset.exists(inMemoryOffset =>
          lastIngestedOffset.exists(dbOffset => inMemoryOffset > dbOffset)
        ),
        s"Cached offset ${offset} newer than offset stored in the database ${lastIngestedOffset}",
      )
      val nextOffsetChanged = if (offset == lastIngestedOffset) offsetChanged else Promise[Unit]()
      this.copy(
        acsStoreId = Some(acsStoreId),
        txLogStoreId = txLogStoreId,
        acsSize = acsSizeInDb,
        offset = lastIngestedOffset,
        offsetChanged = nextOffsetChanged,
      )
    }

    def withUpdate(newAcsSize: Int, newOffset: Long): State = {
      val nextOffsetChanged = if (offset.contains(newOffset)) offsetChanged else Promise[Unit]()
      this.copy(
        acsSize = newAcsSize,
        offset = Some(newOffset),
        offsetChanged = nextOffsetChanged,
        offsetIngestionsToSignal = offsetIngestionsToSignal.filter { case (offsetToSignal, _) =>
          offsetToSignal > newOffset
        },
      )
    }

    def signalOffsetChanged(newOffset: Long): Unit = {
      if (!offset.contains(newOffset)) {
        offsetChanged.success(())
        offsetIngestionsToSignal.foreach { case (offsetToSignal, promise) =>
          if (offsetToSignal <= newOffset) {
            promise.success(())
          }
        }
      }
    }

    /** Update the state by adding another offset whose ingestion should be signalled. If the signalling of that
      * offset has already been requested, don't change the state.
      */
    def withOffsetToSignal(
        offsetToSignal: Long
    ): State = {
      if (offset.exists(_ >= offsetToSignal)) {
        this
      } else {
        offsetIngestionsToSignal.get(offsetToSignal) match {
          case None =>
            val p = Promise[Unit]()
            copy(
              offsetIngestionsToSignal = offsetIngestionsToSignal + (offsetToSignal -> p)
            )
          case Some(_) => this
        }
      }
    }
  }
  private object State {
    def empty(): State = State(
      acsStoreId = None,
      txLogStoreId = None,
      offset = None,
      acsSize = 0,
      offsetChanged = Promise(),
      offsetIngestionsToSignal = SortedMap.empty,
    )
  }

  case class TxLogEvent(
      eventId: String,
      synchronizerId: SynchronizerId,
      acsContractId: Option[ContractId[?]],
  )

  /** Like [[IngestionSummary]], but with all fields mutable to simplify collecting the content from helper methods */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  case class MutableIngestionSummary(
      ingestedCreatedEvents: mutable.ArrayBuffer[CreatedEvent],
      var numFilteredCreatedEvents: Int,
      ingestedArchivedEvents: mutable.ArrayBuffer[ExercisedEvent],
      var numFilteredArchivedEvents: Int,
      updatedContractStates: mutable.ArrayBuffer[ContractStateEvent],
      addedAssignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      var numFilteredAssignEvents: Int,
      removedAssignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      addedUnassignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      var numFilteredUnassignEvents: Int,
      removedUnassignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      prunedContracts: mutable.ArrayBuffer[ContractId[?]],
      ingestedTxLogEntries: mutable.ArrayBuffer[(String3, String)],
  ) {
    def acsSizeDiff: Int = ingestedCreatedEvents.size - ingestedArchivedEvents.size

    def toIngestionSummary(
        updateId: Option[String],
        synchronizerId: Option[SynchronizerId],
        offset: Long,
        recordTime: Option[CantonTimestamp],
        newAcsSize: Int,
        metrics: StoreMetrics,
    ): IngestionSummary = {
      // We update the metrics in here as it's the easiest way
      // to not miss any place that might need updating.
      metrics.acsSize.updateValue(newAcsSize.toLong)
      metrics.ingestedTxLogEntries.mark(ingestedTxLogEntries.size.toLong)(MetricsContext.Empty)
      metrics.completedIngestions.mark()
      synchronizerId.foreach { synchronizer =>
        recordTime.foreach { recordTime =>
          metrics
            .getLastIngestedRecordTimeMsForSynchronizer(synchronizer)
            .updateValue(recordTime.toEpochMilli)
        }
      }
      IngestionSummary(
        updateId = updateId,
        synchronizerId = synchronizerId,
        offset = Some(offset),
        recordTime = recordTime,
        newAcsSize = newAcsSize,
        ingestedCreatedEvents = this.ingestedCreatedEvents.toVector,
        numFilteredCreatedEvents = this.numFilteredCreatedEvents,
        ingestedArchivedEvents = this.ingestedArchivedEvents.toVector,
        numFilteredArchivedEvents = this.numFilteredArchivedEvents,
        updatedContractStates = this.updatedContractStates.toVector,
        addedAssignEvents = this.addedAssignEvents.toVector,
        numFilteredAssignEvents = this.numFilteredAssignEvents,
        removedAssignEvents = this.removedAssignEvents.toVector,
        addedUnassignEvents = this.addedUnassignEvents.toVector,
        numFilteredUnassignEvents = this.numFilteredUnassignEvents,
        removedUnassignEvents = this.removedUnassignEvents.toVector,
        prunedContracts = Vector.empty,
        ingestedTxLogEntries = this.ingestedTxLogEntries.toSeq,
      )
    }
  }

  object MutableIngestionSummary {
    def empty: MutableIngestionSummary = MutableIngestionSummary(
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
    )
  }

  /** Identifies an instance of a store.
    *
    *  @param version    The version of the store.
    *                    Bumping this number will cause the store to forget all previously ingested data
    *                    and start from a clean state.
    *                    Bump this number whenever you make breaking changes in the ingestion filter or
    *                    TxLog parser, or if you want to reset the store after fixing a bug that lead to
    *                    data corruption.
    * @param name        The name of the store, usually the simple name of the corresponding scala class.
    * @param party       The party that owns the store (i.e., the party that subscribes
    *                    to the update stream that feeds the store).
    * @param participant The participant that serves the update stream that feeds this store.
    * @param key         A set of named values that are used to filter the update stream or
    *                    can otherwise be used to distinguish between different instances of the store.
    */
  case class StoreDescriptor(
      version: Int,
      name: String,
      party: PartyId,
      participant: ParticipantId,
      key: Map[String, String],
  ) {
    def toJson: io.circe.Json = {
      Json.obj(
        "version" -> Json.fromInt(version),
        "name" -> Json.fromString(name),
        "party" -> Json.fromString(party.toProtoPrimitive),
        "participant" -> Json.fromString(participant.toProtoPrimitive),
        "key" -> Json.obj(key.map { case (k, v) => k -> Json.fromString(v) }.toSeq*),
      )
    }
  }
}
