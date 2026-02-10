// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import cats.data.{NonEmptyList, NonEmptyVector, OptionT}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import cats.implicits.*
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Template, Transaction}
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.IMPORT_ACS_WORKFLOW_ID_PREFIX
import org.lfdecentralizedtrust.splice.environment.{BaseLedgerConnection, RetryProvider}
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
import com.digitalasset.canton.config.CantonRequireTypes.{String255, String256M}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.showPretty

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.{Seq, SortedMap, VectorMap}
import scala.concurrent.{ExecutionContext, Future, Promise}
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{
  AcsStoreId,
  SelectFromAcsTableWithStateResult,
}
import org.lfdecentralizedtrust.splice.store.db.AcsTables.ContractStateRowData
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import com.digitalasset.canton.util.MonadUtil
import com.google.protobuf.ByteString
import io.circe.Json
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection.ActiveContractsItem
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.DestinationHistory
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId

import scala.reflect.ClassTag
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

import java.lang
import java.time.Instant

final class DbMultiDomainAcsStore[TXE](
    storage: DbStorage,
    acsTableName: String,
    txLogTableNameOpt: Option[String],
    interfaceViewsTableNameOpt: Option[String],
    acsStoreDescriptor: StoreDescriptor,
    txLogStoreDescriptor: Option[StoreDescriptor],
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: MultiDomainAcsStore.ContractFilter[
      ? <: AcsRowData,
      ? <: AcsInterfaceViewRowData,
    ],
    txLogConfig: TxLogStore.Config[TXE],
    domainMigrationInfo: DomainMigrationInfo,
    retryProvider: RetryProvider,
    ingestionConfig: IngestionConfig,
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
  override lazy val storeParty = acsStoreDescriptor.party

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

  override def lookupContractById[C, TCid <: ContractId[?], T](companion: C)(id: ContractId[?])(
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
          companion,
          additionalWhere = sql"""and acs.contract_id = ${lengthLimited(id.contractId)}""",
        ).headOption,
        "lookupContractById",
      )
      .map(result => contractWithStateFromRow(companion)(result))
      .value
  }

  /** Returns any contract of the same template as the passed companion.
    */
  override def findAnyContractWithOffset[C, TCid <: ContractId[?], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            acsTableName,
            acsStoreId,
            domainMigrationId,
            companion,
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
        (sql"""
         select #${SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated()}
         from #$acsTableName acs
         where acs.store_id = $acsStoreId
           and acs.migration_id = $domainMigrationId
           and acs.contract_id = ${lengthLimited(id.contractId)}""").toActionBuilder
          .as[AcsQueries.SelectFromAcsTableWithStateResult]
          .headOption,
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
      val expectedCount = ids.size
      storage
        .query(
          (sql"""
         select count(1)
         from #$acsTableName acs
         where acs.store_id = $acsStoreId
         and acs.migration_id = $domainMigrationId
         and """ ++ inClause("acs.contract_id", ids) ++ sql"""
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

  override def listContracts[C, TCid <: ContractId[?], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]] = {
    listContractsPaginated(companion, None, limit, SortOrder.Ascending).map(_.resultsInPage)
  }

  override def listContractsPaginated[C, TCid <: ContractId[?], T](
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
          companion,
          additionalWhere = afterCondition,
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

  override def listAssignedContracts[C, TCid <: ContractId[?], T](
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
          companion,
          additionalWhere = sql"""and assigned_domain is not null""",
          orderLimit = sql"""order by event_number limit ${sqlLimit(limit)}""",
        ),
        s"listAssignedContracts:$templateId",
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
    for {
      _ <- waitUntilAcsIngested()
      result <- storage
        .query( // index: acs_store_template_sid_mid_tid_ce
          selectFromAcsTableWithState(
            acsTableName,
            acsStoreId,
            domainMigrationId,
            companion,
            additionalWhere = sql"""and acs.contract_expires_at < $now""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listExpiredFromPayloadExpiry",
        )
      limited = applyLimit("listExpiredFromPayloadExpiry", limit, result)
      assigned = limited.map(assignedContractFromRow(companion)(_))
    } yield assigned
  }

  override def listContractsOnDomain[C, TCid <: ContractId[?], T](
      companion: C,
      domain: SynchronizerId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]] = waitUntilAcsIngested {
    for {
      result <- storage.query(
        selectFromAcsTableWithState(
          acsTableName,
          acsStoreId,
          domainMigrationId,
          companion,
          additionalWhere = sql"""and assigned_domain = $domain""",
          orderLimit = sql"""limit ${sqlLimit(limit)}""",
        ),
        "listContractsOnDomain",
      )
      limited = applyLimit("listContractsOnDomain", limit, result)
      contracts = limited.map(row => contractFromRow(companion)(row.acsRow))
    } yield contracts
  }

  override def streamAssignedContracts[C, TCid <: ContractId[?], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[AssignedContract[TCid, T], NotUsed] = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    streamContractsWithState(
      pageSize = defaultPageSizeForContractStream,
      where = sql"""assigned_domain is not null
                and package_name = ${packageQualifiedName.packageName}
                and template_id_qualified_name = ${packageQualifiedName.qualifiedName}""",
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
          treesWithEntries: Seq[(Transaction, TXE)],
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
        val trees = items.collect {
          case UpdateHistoryResponse(TransactionTreeUpdate(tree), _)
              if !tree.getWorkflowId.startsWith(IMPORT_ACS_WORKFLOW_ID_PREFIX) =>
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
                backfilledCreatedEvents = 0L,
                backfilledExercisedEvents = 0L,
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
            } yield {
              val ingestedEvents = IngestedEvents.eventCount(trees)
              DestinationHistory.InsertResult(
                backfilledUpdates = trees.size.toLong,
                backfilledExercisedEvents = ingestedEvents.numExercisedEvents,
                backfilledCreatedEvents = ingestedEvents.numCreatedEvents,
                lastBackfilledRecordTime =
                  CantonTimestamp.assertFromInstant(nonEmpty.last.getRecordTime),
              )
            }
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
                (sql"""
                   select #${SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated()}
                   from #$acsTableName acs
                   where acs.store_id = $acsStoreId
                     and acs.migration_id = $domainMigrationId
                     and state_number >= $fromNumber
                     and """ ++ where ++ sql"""
                   order by state_number limit ${sqlLimit(pageSize)}""").toActionBuilder
                  .as[AcsQueries.SelectFromAcsTableWithStateResult],
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

  override def isReadyForAssign(contractId: ContractId[?], out: ReassignmentId)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    waitUntilAcsIngested {
      storage
        .querySingle(
          (sql"""
             select #${SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated()}
             from #$acsTableName acs
             where acs.store_id = $acsStoreId
               and acs.migration_id = $domainMigrationId
               and acs.contract_id = $contractId""").toActionBuilder
            .as[AcsQueries.SelectFromAcsTableWithStateResult]
            .headOption,
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

  override def findInterfaceViewByContractId[C, ICid <: ContractId[?], View <: DamlRecord[View]](
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
  ): Future[Map[ContractId[?], NonEmpty[Set[ReassignmentId]]]] = {
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

    private[this] def initializeDescriptor(
        descriptor: StoreDescriptor
    )(implicit
        traceContext: TraceContext
    ): Future[InitializeDescriptorResult[Int]] = {
      for {
        newStoreId <- StoreDescriptorStore.getStoreIdForDescriptor(descriptor, storage)

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

    def ingestAcsStreamInBatches(
        source: Source[Seq[BaseLedgerConnection.ActiveContractsItem], NotUsed],
        offset: Long,
    )(implicit
        tc: TraceContext,
        mat: Materializer,
    ): Future[Unit] = {
      if (hasFinishedAcsIngestion) {
        Future.failed(
          new RuntimeException(
            s"ACS was already ingested for store $acsStoreId, cannot ingest again"
          )
        )
      } else {
        for {
          // If we crash halfway through we want to start over with the whole ACS ingestion.
          // If the offset is the same between calls, this isn't strictly necessary, as `on conflict do nothing` prevents any issues.
          // But if the offset changes between calls (which can happen in InitializeAcsAtLatestOffset), then we might miss some archival events,
          // and thus we need to ingest the entire ACS from the beginning.
          // Since we are not using any long-running transaction spanning the entire ACS ingestion
          // process, ACS queries could return incomplete data shortly after application start.
          // This is fine because all clients are expected to use [[waitUntilAcsIngested()]] to avoid
          // reading ACS data before it has finished ingesting.
          _ <- clearDataForCurrentMigrationId()
          acsSize <- source.runWith(
            Sink.foldAsync[Int, Seq[BaseLedgerConnection.ActiveContractsItem]](0) {
              case (acsSizeSoFar, batch) =>
                val summaryState = MutableIngestionSummary.empty
                ingestAcsBatch(
                  offset,
                  batch.collect { case ActiveContractsItem.ActiveContract(contract) => contract },
                  batch.collect { case ActiveContractsItem.IncompleteUnassign(unassign) =>
                    unassign
                  },
                  batch.collect { case ActiveContractsItem.IncompleteAssign(assign) => assign },
                  summaryState,
                ).map { _ =>
                  val newAcsSize = summaryState.acsSizeDiff + acsSizeSoFar
                  val summary = summaryState
                    .toIngestionSummary(
                      synchronizerIdToRecordTime = Map.empty,
                      offset = offset,
                      newAcsSize = newAcsSize,
                      metrics = metrics,
                    )
                  handleIngestionSummary(summary)
                  logger.debug(show"Ingested ACS batch $summary")
                  newAcsSize
                }
            }
          )
          // A store is considered initialized if the last ingested offset is set
          // Therefore, we must do that after the ACS is ingested,
          // so that in case of failure the whole ACS ingestion will be retried.
          _ <- markAcsIngestedAsOf(offset, acsSize)
        } yield ()
      }
    }

    private def clearDataForCurrentMigrationId()(implicit
        traceContext: TraceContext
    ): Future[Unit] = {
      for {
        nAcs <- storage
          .update(
            sqlu"delete from #$acsTableName where store_id = $acsStoreId and migration_id = $domainMigrationId",
            "clearDataForCurrentMigrationId.deleteAcs",
          )
        nReassignments <- storage.update(
          sqlu"delete from incomplete_reassignments where store_id = $acsStoreId and migration_id = $domainMigrationId",
          "clearDataForCurrentMigrationId.deleteReassignments",
        )
      } yield {
        if (nAcs > 0 || nReassignments > 0) {
          logger.warn(
            s"Deleted $nAcs rows from ACS table and $nReassignments incomplete reassignments for store $acsStoreId and migration $domainMigrationId. " +
              "This should only happen if the store already had some data, but was not marked as having ingested the ACS " +
              "(because of a previous failed ACS ingestion attempt)."
          )
        }
      }
    }

    private def ingestAcsBatch(
        offset: Long,
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
        summaryState: MutableIngestionSummary,
    )(implicit traceContext: TraceContext): Future[Unit] = {
      if (hasFinishedAcsIngestion) {
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

        val acsInserts = NonEmptyList
          .fromFoldable(todoAcs)
          .map(acs =>
            doIngestAcsInserts(
              acs.map { ac =>
                AcsInsertEntry(
                  offset,
                  ac.createdEvent,
                  stateRowDataFromActiveContract(
                    ac.synchronizerId,
                    ac.reassignmentCounter,
                  ),
                )
              },
              summaryState,
              onConflictDoNothing = true,
            )
          )

        val incompleteOutInserts = NonEmptyList
          .fromFoldable(todoIncompleteOut)
          .map { evts =>
            doIngestAcsInserts(
              evts.map { evt =>
                AcsInsertEntry(
                  offset,
                  evt.createdEvent,
                  stateRowDataFromUnassign(evt.reassignmentEvent),
                )
              },
              summaryState,
              onConflictDoNothing = true,
            )
          }
          .toList ++ todoIncompleteOut.map { evt =>
          // TODO (#3884): batch inserts
          doRegisterIncompleteReassignment(
            evt.createdEvent.getContractId,
            evt.reassignmentEvent.source,
            evt.reassignmentEvent.unassignId,
            isAssignment = false,
            summaryState,
          )
        }

        val incompleteInInserts = NonEmptyList
          .fromFoldable(todoIncompleteIn)
          .map { evts =>
            doIngestAcsInserts(
              evts.map { evt =>
                AcsInsertEntry(
                  offset,
                  evt.reassignmentEvent.createdEvent,
                  stateRowDataFromAssign(evt.reassignmentEvent),
                )
              },
              summaryState,
              onConflictDoNothing = true,
            )
          }
          .toList ++ todoIncompleteIn.map { evt =>
          // TODO (#3884): batch inserts
          doRegisterIncompleteReassignment(
            evt.reassignmentEvent.createdEvent.getContractId,
            evt.reassignmentEvent.source,
            evt.reassignmentEvent.unassignId,
            isAssignment = true,
            summaryState,
          )
        }

        for {
          _ <- storage
            .queryAndUpdate(
              DBIO
                .sequence(
                  acsInserts.toList ++ incompleteOutInserts ++ incompleteInInserts
                ),
              "ingestAcsBatch",
            )
        } yield ()
      }
    }

    private def markAcsIngestedAsOf(offset: Long, acsSize: Int)(implicit
        traceContext: TraceContext
    ): Future[Unit] = {
      storage.update(updateOffset(offset), "markAcsIngestedAsOf").map { _ =>
        state
          .getAndUpdate(
            _.withUpdate(acsSize, offset)
          )
          .signalOffsetChanged(offset)

        logger.debug(show"Ingested complete ACS at offset $offset")

        finishedAcsIngestion.success(())
        logger.info(
          s"Store $acsStoreId ingested the ACS and switched to ingesting updates at $offset"
        )
      }
    }

    override def ingestUpdateBatch(batch: NonEmptyList[TreeUpdateOrOffsetCheckpoint])(implicit
        traceContext: TraceContext
    ): Future[Unit] = {
      metrics.updateLastSeenMetrics(batch.last)
      metrics.batchSize.update(batch.length)
      val steps = batchInsertionSteps(batch)
      MonadUtil
        .sequentialTraverse(steps) {
          case batch: IngestTransactionTreesBatch =>
            storage
              .queryAndUpdate(ingestTransactionTrees(batch), "ingestTransactionTrees")
              .map { summaryState =>
                val lastTree = batch.batch.last.tree
                val synchronizerIdToRecordTime = batch.batch
                  .groupBy(_.synchronizerId)
                  .view
                  .mapValues(trees =>
                    CantonTimestamp.assertFromInstant(trees.last.tree.getRecordTime)
                  )
                state
                  .getAndUpdate(s =>
                    s.withUpdate(
                      s.acsSize + summaryState.acsSizeDiff,
                      lastTree.getOffset,
                    )
                  )
                  .signalOffsetChanged(lastTree.getOffset)
                val summary =
                  summaryState.toIngestionSummary(
                    offset = lastTree.getOffset,
                    synchronizerIdToRecordTime = synchronizerIdToRecordTime.toMap,
                    newAcsSize = state.get().acsSize,
                    metrics = metrics,
                  )
                logger.debug(
                  show"Ingested transaction batch of ${batch.batch.length} elements: $summary"
                )
                handleIngestionSummary(summary)
              }
          case IngestReassignment(reassignment, synchronizerId) =>
            storage
              .queryAndUpdate(
                ingestReassignment(reassignment.offset, reassignment.transfer),
                "ingestReassignment",
              )
              .map { summaryState =>
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
                    synchronizerIdToRecordTime = Map(synchronizerId -> reassignment.recordTime),
                    offset = reassignment.offset,
                    newAcsSize = state.get().acsSize,
                    metrics = metrics,
                  )
                logger.debug(show"Ingested reassignment $summary")
                handleIngestionSummary(summary)
              }
          case UpdateCheckpoint(checkpoint) =>
            val offset = checkpoint.checkpoint.getOffset
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
                    synchronizerIdToRecordTime = Map.empty,
                    offset = offset,
                    newAcsSize = state.get().acsSize,
                    metrics = metrics,
                  )
                logger.debug(show"Ingested offset checkpoint $offset")
                handleIngestionSummary(summary)
              }
        }
        .map(_ => ())
    }

    private def ingestReassignment(
        offset: Long,
        reassignment: Reassignment[ReassignmentEvent],
    )(implicit tc: TraceContext) = {
      val summary = MutableIngestionSummary.empty
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
                        doIngestAcsInserts(
                          NonEmptyList
                            .of(
                              AcsInsertEntry(
                                reassignment.offset,
                                assign.createdEvent,
                                stateRowDataFromAssign(assign),
                              )
                            ),
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
      ).map(_ => summary)
    }

    private def ingestTransactionTrees(
        trees: IngestTransactionTreesBatch
    )(implicit tc: TraceContext) = {
      val summary = MutableIngestionSummary.empty

      val workTodo: Seq[OperationToDo] = trees.batch
        .map(tree =>
          Trees
            .foldTree(
              tree.tree,
              VectorMap.empty[String, OperationToDo],
            )(
              onCreate = (st, ev, _) => {
                if (contractFilter.contains(ev)) {
                  contractFilter.ensureStakeholderOf(ev)
                  st + (ev.getContractId -> Insert(
                    ev,
                    tree.synchronizerId,
                  ))
                } else {
                  summary.numFilteredCreatedEvents += 1
                  st
                }
              },
              onExercise = (st, ev, _) => {
                if (ev.isConsuming && contractFilter.shouldArchive(ev)) {
                  st + (ev.getContractId -> Delete(ev))
                } else {
                  st
                }
              },
            )
        )
        // optimization: a delete on a contract cancels-out with the corresponding insert
        .foldLeft(VectorMap.empty[String, OperationToDo]) { case (acc, treeOps) =>
          val (toRemove, toAdd) = treeOps.partition {
            case (contractId, Delete(_)) if acc.contains(contractId) => true
            case _ => false
          }
          (acc -- toRemove.keys) ++ toAdd
        }
        .values
        .toSeq

      val txLogEntries: Seq[(Instant, (TXE, lang.Long, SynchronizerId))] = trees.batch
        // do not parse events imported from acs
        .filter(tree => !tree.tree.getWorkflowId.startsWith(IMPORT_ACS_WORKFLOW_ID_PREFIX))
        .flatMap(tree =>
          txLogConfig.parser
            .parse(tree.tree, tree.synchronizerId, logger)
            .map(tree.tree.getRecordTime -> (_, tree.tree.getOffset, tree.synchronizerId))
        )
      val synchronizerIdsToMinRecordTime =
        trees.batch.groupBy(_.synchronizerId).map { case (synchronizerId, batch) =>
          synchronizerId -> batch.map(_.tree.getRecordTime).minimumBy(_.toEpochMilli)
        }

      val allDbOps = for {
        insertContractIdsWithIncompleteReassignments <- checkIncompleteReassignments(
          workTodo.collect { case insert: Insert =>
            insert.evt.getContractId
          }
        )
        insertsToDo = workTodo.collect {
          case insert: Insert
              if !insertContractIdsWithIncompleteReassignments.contains(insert.evt.getContractId) =>
            insert
        }
        _ <- NonEmptyList.fromList(insertsToDo.toList) match {
          case Some(inserts) =>
            doIngestAcsInserts(
              inserts.map(insert =>
                AcsInsertEntry(
                  insert.evt.getOffset,
                  insert.evt,
                  stateRowDataFromActiveContract(insert.synchronizerId, 0L),
                )
              ),
              summary,
            ).map(_ => ())
          case None =>
            DBIO.successful(())
        }
        _ <- doDeleteContracts(
          workTodo.collect { case Delete(exercisedEvent) =>
            exercisedEvent
          },
          summary,
        )
        // TODO (#3048): batch this
        _ <- DBIO.seq(txLogEntries.map { case (recordTime, (txe, offset, synchronizerId)) =>
          doIngestTxLogInsert(
            domainMigrationId,
            synchronizerId,
            offset,
            CantonTimestamp.assertFromInstant(recordTime),
            txe,
            summary,
          )
        }*)
        _ <- DBIO.seq(synchronizerIdsToMinRecordTime.toSeq.map {
          case (synchronizerId, recordTime) =>
            doInitializeFirstIngestedUpdate(
              synchronizerId,
              domainMigrationId,
              CantonTimestamp.assertFromInstant(recordTime),
            )
        }*)
      } yield summary

      ingestUpdateAtOffset(trees.batch.last.tree.getOffset, allDbOps).map(_ => summary)
    }

    private def hasAcsEntry(contractId: String) = (sql"""
           select count(*) from #$acsTableName
           where store_id = $acsStoreId and migration_id = $domainMigrationId and contract_id = ${lengthLimited(
        contractId
      )}
          """).as[Int].head.map(_ > 0)

    private def hasIncompleteReassignments(contractId: String) =
      checkIncompleteReassignments(Seq(contractId)).map(_.nonEmpty)

    /** @return which contract ids have incomplete reassignments
      */
    private def checkIncompleteReassignments(
        contractIds: Seq[String]
    ): DBIOAction[Set[String], NoStream, Effect.Read] = {
      if (contractIds.isEmpty) DBIO.successful(Set.empty)
      else {
        DBIO
          .sequence(contractIds.grouped(ingestionConfig.maxLookupsPerStatement).map { contractIds =>
            (sql"""
           select distinct contract_id from incomplete_reassignments
           where store_id = $acsStoreId and migration_id = $domainMigrationId and """ ++ inClause(
              "contract_id",
              contractIds.map(lengthLimited),
            )).toActionBuilder.as[String].map(_.toSet)
          })
          .map(_.foldLeft(Set.empty[String])(_ ++ _))
      }
    }

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

    case class AcsInsertEntry(
        // not always the same as `createdEvent.getOffset`: when `ingestACS`ing, it's `participantBegin`
        offset: Long,
        createdEvent: CreatedEvent,
        stateData: ContractStateRowData,
    )
    private def doIngestAcsInserts(
        entries: NonEmptyList[AcsInsertEntry],
        summary: MutableIngestionSummary,
        onConflictDoNothing: Boolean = false,
    )(implicit tc: TraceContext) = {
      val onConflictDoNothingClause =
        if (onConflictDoNothing) sql" on conflict do nothing " else sql""
      DBIO.sequence(entries.grouped(ingestionConfig.maxEntriesPerInsert).map { entries =>
        val insertValues = entries
          .map(entry => entry.createdEvent.getContractId -> getInsertValues(entry))
        val acsTableValues = insertValues.map(_._2.acsTable)
        val joinedAcsTableValues = acsTableValues.reduceLeft(_ ++ sql"," ++ _)
        // column names are hardcoded so they can be raw-interpolated
        val acsIndexColumnNames = mkIndexColumnNames(contractFilter.getAcsIndexColumnNames)
        val interfaceViewsIndexColumnNames = mkIndexColumnNames(
          contractFilter.getInterfaceViewsIndexColumnNames
        )
        for {
          rawContractIdToEventNumber <- (sql"""
                insert into #$acsTableName(store_id, migration_id, contract_id, template_id_package_id, template_id_qualified_name, package_name,
                                           create_arguments, created_event_blob, created_at, contract_expires_at,
                                           assigned_domain, reassignment_counter, reassignment_target_domain,
                                           reassignment_source_domain, reassignment_submitter, reassignment_unassign_id
                                           #$acsIndexColumnNames)
                values """ ++ joinedAcsTableValues ++ onConflictDoNothingClause ++ sql" returning contract_id, event_number").toActionBuilder
            .asUpdateReturning[(String, Long)]
          rawContractIdToEventNumberMap = rawContractIdToEventNumber.toMap
          interfaceViewsValues = insertValues.toList.flatMap { case (contractId, insertValue) =>
            // If there's no conflict, this will include all views.
            // If there's a conflict (which then does NOT include the row in the result):
            // The contract was inserted in a previous call and therefore so were its interface views, so we can skip their inserts.
            // To introduce a new view the store_descriptor must change, and therefore either:
            // - Both the contract and view are already inserted
            // - Neither are inserted
            rawContractIdToEventNumberMap.get(contractId).toList.flatMap { eventNumber =>
              insertValue.interfaceViews(eventNumber)
            }
          }
          joinedInterfaceViewsValues = interfaceViewsValues.reduceLeftOption(_ ++ sql"," ++ _)
          _ <- joinedInterfaceViewsValues match {
            case None => // no interfaces to insert
              DBIO.successful(0)
            case Some(interfaceViewValues) =>
              (sql"""
                insert into #$interfaceViewsTableName(acs_event_number, interface_id_package_id, interface_id_qualified_name, interface_view #$interfaceViewsIndexColumnNames)
                values """ ++ interfaceViewValues ++ onConflictDoNothingClause).toActionBuilder.asUpdate
          }
        } yield {
          summary.ingestedCreatedEvents.addAll(entries.map(_.createdEvent).toIterable)
        }
      })
    }

    case class InsertValues(
        acsTable: SQLActionBuilderChain,
        interfaceViews: Long => Seq[SQLActionBuilderChain],
    )
    private def getInsertValues(
        entry: AcsInsertEntry
    )(implicit tc: TraceContext): InsertValues = {
      (
        contractFilter.matchingInterfaceRows(entry.createdEvent),
        contractFilter.matchingContractToRow(entry.createdEvent),
      ) match {
        case (Some((fallbackRowData, interfaces)), rowData) =>
          // For the acs_table row:
          // If only the interface filter matches, we use the "bare minimum" row data from the interface filter
          // that does not contain any index columns, as the interface table needs to reference the acs_table.
          // If both match, we want to use the row data from the template filter,
          // as that one contains all the information.
          val acsIndexColumnValues = rowData
            .map(rd => getIndexColumnValues(rd.indexColumns))
            .getOrElse(
              if (contractFilter.getAcsIndexColumnNames.isEmpty) SQLActionBuilderChain(sql"")
              else {
                sql"," ++ sqlCommaSeparated(
                  contractFilter.getAcsIndexColumnNames.map(_ => sql"null")
                )
              }
            )
          InsertValues(
            acsTable = insertContractValues(
              rowData.getOrElse(fallbackRowData),
              entry.createdEvent,
              entry.stateData,
              acsIndexColumnValues,
            ),
            interfaceViews = eventNumber =>
              interfaces.map { interfaceRow =>
                val interfaceId = interfaceRow.interfaceId
                val interfaceIdQualifiedName = QualifiedName(interfaceId)
                val interfaceIdPackageId = lengthLimited(interfaceId.getPackageId)
                val viewJson =
                  AcsJdbcTypes.payloadJsonFromDefinedDataType(interfaceRow.interfaceView)
                val indexColumnNameValues = getIndexColumnValues(interfaceRow.indexColumns)
                (sql"($eventNumber, $interfaceIdPackageId, $interfaceIdQualifiedName, $viewJson " ++ indexColumnNameValues ++ sql")")
              },
          )
        case (None, Some(rowData)) =>
          InsertValues(
            acsTable = insertContractValues(
              rowData,
              entry.createdEvent,
              entry.stateData,
              getIndexColumnValues(rowData.indexColumns),
            ),
            interfaceViews = _ => Seq.empty,
          )
        case _ =>
          val errMsg =
            s"Item at offset ${entry.createdEvent.getOffset} with contract id ${entry.createdEvent.getContractId} cannot be ingested."
          logger.error(errMsg)
          throw new IllegalArgumentException(errMsg)
      }
    }

    private def insertContractValues(
        rowData: AcsRowData,
        createdEvent: CreatedEvent,
        stateData: ContractStateRowData,
        indexColumnNameValues: SQLActionBuilderChain,
    ): SQLActionBuilderChain = {
      val contractId = rowData.contractId.asInstanceOf[ContractId[Any]]
      val templateId = rowData.identifier
      val templateIdQualifiedName = QualifiedName(templateId)
      val templateIdPackageId = lengthLimited(rowData.identifier.getPackageId)
      val packageName = createdEvent.getPackageName
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

      import storage.DbStorageConverters.setParameterByteArray
      (sql"""($acsStoreId, $domainMigrationId, $contractId, $templateIdPackageId, $templateIdQualifiedName, $packageName,
                        $createArguments, $createdEventBlob, $createdAt, $contractExpiresAt,
                        $assignedDomain, $reassignmentCounter, $reassignmentTargetDomain,
                        $reassignmentSourceDomain, $reassignmentSubmitter, $reassignmentUnassignId
              """ ++ indexColumnNameValues ++ sql")")
    }

    private def doDeleteContracts(events: Seq[ExercisedEvent], summary: MutableIngestionSummary) = {
      if (events.isEmpty) DBIO.successful(())
      else {
        DBIO.sequence(events.grouped(ingestionConfig.maxDeletesPerStatement).map { events =>
          (sql"""
            delete from #$acsTableName
            where store_id = $acsStoreId
              and migration_id = $domainMigrationId
              and """ ++ inClause(
            "contract_id",
            events.map(e => lengthLimited(e.getContractId)),
          ) ++ sql" returning contract_id").toActionBuilder.as[String].map { deletedCids =>
            val deletedCidSet = deletedCids.toSet
            val ingestedArchivedEvents =
              events.filter(evt => deletedCidSet.contains(evt.getContractId))
            summary.ingestedArchivedEvents.addAll(ingestedArchivedEvents)
            // there were no contracts with some id. This can happen because:
            // `contractFilter.mightContain` in `getIngestionWork` can return true for a template,
            // but that might still satisfy some other filter, so the contract was never inserted
            summary.numFilteredArchivedEvents += (events.length - deletedCids.size)
          }
        })
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
    case class Insert(evt: CreatedEvent, synchronizerId: SynchronizerId) extends OperationToDo
    case class Delete(evt: ExercisedEvent) extends OperationToDo
  }

  private def getIndexColumnValues(
      data: Seq[(String, IndexColumnValue[?])]
  ): SQLActionBuilderChain = {
    if (data.isEmpty) SQLActionBuilderChain(sql"")
    else sql"," ++ sqlCommaSeparated(data.map(_._2).map(v => sql"$v"))
  }

  // Note: the column names are hardcoded so they're safe to interpolate raw
  private def getIndexColumnNames(data: Seq[(String, IndexColumnValue[?])]): String =
    mkIndexColumnNames(data.map(_._1))

  private def mkIndexColumnNames(names: Seq[String]): String =
    if (names.isEmpty) ""
    else names.mkString(",", ", ", "")

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
    val rowDataO = txLogConfig.entryToRow(txe)
    rowDataO match {
      case Some(rowData) =>
        val indexColumnNames = getIndexColumnNames(rowData.indexColumns)
        val indexColumnNameValues = getIndexColumnValues(rowData.indexColumns)

        summary.ingestedTxLogEntries.addOne((entryType, entryData))
        (sql"""
        insert into #$txLogTableName(store_id, migration_id, transaction_offset, record_time, domain_id,
        entry_type, entry_data #$indexColumnNames)
        values ($txLogStoreId, $migrationId, $safeOffset, $recordTime, $domainId,
                $entryType, ${safeEntryData}::jsonb""" ++ indexColumnNameValues ++ sql""")
        """).toActionBuilder.asUpdate
      case None =>
        DBIO.successful(())
    }
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
      domainMigrationInfo.migrationTimeInfo match {
        case Some(info) =>
          if (info.synchronizerWasPaused) {
            verifyNoRolledBackData(txLogStoreId, previousMigrationId, info.acsRecordTime)
          } else {
            deleteRolledBackTxLogEntries(txLogStoreId, previousMigrationId, info.acsRecordTime)
          }
        case _ =>
          logger.debug("No previous domain migration, not checking or deleting txlog entries")
          Future.unit
      }
    }
  }

  private[this] def verifyNoRolledBackData(
      txLogStoreId: TxLogStoreId, // Not using the storeId from the state, as the state might not be updated yet
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext) = {
    val action =
      sql"""
            select count(*) from #$txLogTableName
            where store_id = $txLogStoreId and migration_id = $migrationId and record_time > $recordTime
          """
        .as[Long]
        .head
        .map(rows =>
          if (rows > 0) {
            throw new IllegalStateException(
              s"Found $rows rows for $txLogStoreDescriptor where migration_id = $migrationId and record_time > $recordTime, " +
                "but the configuration says the domain was paused during the migration. " +
                "Check the domain migration configuration and the content of the txlog database."
            )
          } else {
            logger.debug(
              s"No txlog entries found for $txLogStoreDescriptor where migration_id = $migrationId and record_time > $recordTime"
            )
          }
        )
    storage.query(action, "verifyNoRolledBackData")
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

  sealed trait BatchStep
  case class UpdateCheckpoint(checkpoint: TreeUpdateOrOffsetCheckpoint.Checkpoint) extends BatchStep
  case class IngestReassignment(update: ReassignmentUpdate, synchronizerId: SynchronizerId)
      extends BatchStep
  case class IngestTransactionTreesBatch(batch: NonEmptyVector[IngestTransactionTree])
      extends BatchStep
  case class IngestTransactionTree(tree: Transaction, synchronizerId: SynchronizerId)

  /** Rolls up consecutive transaction tree updates into a single "step".
    * Also removes all TreeUpdateOrOffsetCheckpoint.Checkpoint except if it's the last element of the batch,
    * as their offset will be overridden by the next update.
    */
  private def batchInsertionSteps(
      batch: NonEmptyList[TreeUpdateOrOffsetCheckpoint]
  ): Vector[BatchStep] = {
    val steps = batch.map(toBatchStep)
    steps.tail
      .foldLeft(Vector[BatchStep](steps.head)) {
        case (
              accExceptLast :+ IngestTransactionTreesBatch(existingBatch),
              IngestTransactionTreesBatch(moreItems),
            ) =>
          accExceptLast :+ IngestTransactionTreesBatch(existingBatch ++: moreItems)
        case (acc, next) =>
          acc :+ next
      }
      .view
      .zipWithIndex
      .filter {
        // checkpoints are only useful if they're the last operation of the batch
        case (_: UpdateCheckpoint, index) =>
          index == batch.size - 1
        case _ => true
      }
      .map(_._1)
      .toVector
  }
  private def toBatchStep(op: TreeUpdateOrOffsetCheckpoint): BatchStep = op match {
    case TreeUpdateOrOffsetCheckpoint.Update(reassignment: ReassignmentUpdate, synchronizerId) =>
      IngestReassignment(reassignment, synchronizerId)
    case checkpoint: TreeUpdateOrOffsetCheckpoint.Checkpoint =>
      UpdateCheckpoint(checkpoint)
    case TreeUpdateOrOffsetCheckpoint.Update(update: TransactionTreeUpdate, synchronizerId) =>
      IngestTransactionTreesBatch(
        NonEmptyVector.of(IngestTransactionTree(update.tree, synchronizerId))
      )
  }
}
