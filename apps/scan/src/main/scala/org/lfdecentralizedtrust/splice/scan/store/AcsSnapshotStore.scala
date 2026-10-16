// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import cats.data.NonEmptyVector
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier}
import com.digitalasset.nonempty.NonEmpty
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, LockedAmulet}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  AcsSnapshotDDL,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
  LegacyAcsSnapshot,
  PerTableAcsSnapshot,
  QueryAcsSnapshotPaginationToken,
  QueryAcsSnapshotResult,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.SelectFromCreateEvents
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, LimitHelpers, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsJdbcTypes,
  AcsQueries,
  AdvisoryLockIds,
  AdvisoryLocks,
}
import org.lfdecentralizedtrust.splice.util.{Contract, HoldingsSummary, PackageQualifiedName}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.circe.Decoder.Result
import io.circe.HCursor
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import org.lfdecentralizedtrust.splice.util.{EventId, ValueJsonCodecProtobuf as ProtobufCodec}
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.{GetResult, JdbcProfile}

import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class AcsSnapshotStore(
    storage: DbStorage,
    val updateHistory: UpdateHistory,
    dsoParty: PartyId,
    val currentMigrationId: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, closeContext: CloseContext)
    extends AcsJdbcTypes
    with AcsQueries
    with LimitHelpers
    with NamedLogging {
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  private implicit val dbProfile: DbStorage.Profile = storage.profile
  override val profile: JdbcProfile = storage.profile.jdbc
  import profile.api.jdbcActionExtensionMethods

  private def historyId = updateHistory.historyId

  def lookupSnapshotAtOrBefore(
      migrationId: Long,
      before: CantonTimestamp,
      onlyIndexed: Boolean = false,
  )(implicit tc: TraceContext): Future[Option[AcsSnapshot]] = {
    storage
      .querySingle(
        sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance, creates_table_name, stakeholders_table_name, indexes_created
            from acs_snapshot
            where snapshot_record_time <= $before
              and migration_id = $migrationId
              and history_id = $historyId
              and (indexes_created or not $onlyIndexed)
            order by snapshot_record_time desc
            limit 1""".as[AcsSnapshot].headOption,
        "lookupSnapshotBefore",
      )
      .value
  }

  def lookupSnapshotAfter(
      migrationId: Long,
      after: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Option[AcsSnapshot]] = {

    val select =
      sql"select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance, creates_table_name, stakeholders_table_name, indexes_created "
    val orderLimit = sql" order by snapshot_record_time asc limit 1 "
    val sameMig = select ++ sql""" from acs_snapshot
            where snapshot_record_time > $after
              and migration_id = $migrationId
              and history_id = $historyId """ ++ orderLimit
    val largerMig = select ++ sql""" from acs_snapshot
            where migration_id > $migrationId
              and history_id = $historyId """ ++ orderLimit

    val query =
      sql"select * from ((" ++ sameMig ++ sql") union all (" ++ largerMig ++ sql")) all_queries order by snapshot_record_time asc limit 1"

    storage
      .querySingle(
        query.toActionBuilder.as[AcsSnapshot].headOption,
        "lookupSnapshotAfter",
      )
      .value

  }

  def lookupOldestUnindexedSnapshot()(implicit
      tc: TraceContext
  ): Future[Option[PerTableAcsSnapshot]] = {
    storage
      .querySingle(
        sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance, creates_table_name, stakeholders_table_name, indexes_created
            from acs_snapshot
            where not indexes_created
            and   history_id = $historyId
            order by snapshot_record_time;
         """.as[AcsSnapshot].headOption,
        "getOldestUnindexedSnapshot",
      )
      .map {
        case snapshot: PerTableAcsSnapshot =>
          snapshot
        case _: LegacyAcsSnapshot =>
          throw io.grpc.Status.FAILED_PRECONDITION
            .withDescription("Legacy snapshots shouldn't have the indexes_created flag as false.")
            .asRuntimeException()
      }
      .value
  }

  /** *
    * TESTING ONLY
    * Creates a new ACS snapshot at time `until`.
    * For ease of implementation, it clears the incremental snapshot state and reapplies all updates since then.
    */
  def insertNewSnapshot(
      table: IncrementalAcsSnapshotTable,
      migrationId: Long,
      until: CantonTimestamp,
      shouldIndexSnapshot: Boolean = true,
  )(implicit tc: TraceContext): Future[Unit] = {
    Future {
      scala.concurrent.blocking {
        AcsSnapshotStore.PreventConcurrentSnapshotsSemaphore.acquire()
      }
    }.flatMap { _ =>
      for {
        _ <- getIncrementalSnapshot(table).flatMap {
          case Some(dirtyIncrementalSnapshot) =>
            deleteIncrementalSnapshot(table, dirtyIncrementalSnapshot)
          case None => Future.successful(())
        }
        _ <- initializeIncrementalSnapshotFromImportUpdates(
          table,
          CantonTimestamp.MinValue.plusSeconds(1L),
          until,
          migrationId,
        )
        incrementalSnapshot <- getIncrementalSnapshot(table).map(
          _.getOrElse(
            throw io.grpc.Status.FAILED_PRECONDITION
              .withDescription("This should've been just created")
              .asRuntimeException()
          )
        )
        _ <- updateIncrementalSnapshot(table, incrementalSnapshot, until)
        _ <- saveIncrementalSnapshot(
          table,
          // We just did that, no need to re-fetch
          incrementalSnapshot.copy(recordTime = incrementalSnapshot.targetRecordTime),
          until,
        )
        snapshot <- lookupSnapshotAtOrBefore(migrationId, until)
        _ <- snapshot match {
          case Some(snapshot: PerTableAcsSnapshot) if shouldIndexSnapshot =>
            indexSnapshotStakeholdersTable(snapshot)
          case Some(_) => Future.unit
          case None =>
            Future.failed(
              io.grpc.Status.FAILED_PRECONDITION
                .withDescription("This should've been just created")
                .asRuntimeException()
            )
        }
      } yield ()
    }.andThen { _ =>
      AcsSnapshotStore.PreventConcurrentSnapshotsSemaphore.release()
    }
  }

  def deleteSnapshot(
      snapshot: AcsSnapshot
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val statement = snapshot match {
      case snapshot: LegacyAcsSnapshot =>
        DBIOAction.seq(
          sqlu"""delete from acs_snapshot where snapshot_record_time = ${snapshot.snapshotRecordTime}""",
          sqlu"""delete from acs_snapshot_data where row_id between ${snapshot.firstRowId} and ${snapshot.lastRowId}""",
        )
      case _: PerTableAcsSnapshot =>
        for {
          tableNames <-
            sql"""delete from acs_snapshot where snapshot_record_time = ${snapshot.snapshotRecordTime} returning creates_table_name, stakeholders_table_name"""
              .as[(String, String)]
              .headOption
          _ <- tableNames match {
            case Some((createsTableName, stakeholdersTableName)) =>
              DBIO.seq(
                AdvisoryLocks.withDdlLock(sqlu"drop table if exists #$createsTableName"),
                AdvisoryLocks.withDdlLock(sqlu"drop table if exists #$stakeholdersTableName"),
              )
            case None => DBIO.successful(())
          }
        } yield ()
    }
    storage.queryAndUpdate(statement.transactionally, "deleteSnapshot")
  }

  def queryAcsSnapshot(
      migrationId: Long,
      snapshot: CantonTimestamp,
      after: Option[QueryAcsSnapshotPaginationToken],
      limit: Limit,
      partyIds: Seq[PartyId],
      templates: Seq[PackageQualifiedName],
  )(implicit tc: TraceContext): Future[QueryAcsSnapshotResult] = {

    def notFound = FutureUnlessShutdown.failed(
      io.grpc.Status.NOT_FOUND
        .withDescription(
          s"Failed to find ACS snapshot for migration id $migrationId at $snapshot"
        )
        .asRuntimeException()
    )

    for {
      snapshot <- storage
        .querySingle(
          sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance, creates_table_name, stakeholders_table_name, indexes_created
            from acs_snapshot
            where snapshot_record_time = $snapshot
              and migration_id = $migrationId
              and history_id = $historyId
            limit 1""".as[AcsSnapshot].headOption,
          "queryAcsSnapshot.getSnapshot",
        )
        .value
        .flatMap {
          case None => notFound
          case Some(snapshot) if !snapshot.indexesCreated => notFound
          case Some(snapshot) => Future.successful(snapshot)
        }
      events <- snapshot match {
        case snapshot: LegacyAcsSnapshot =>
          queryLegacyTable(snapshot, after, limit, partyIds, templates)
        case ownTable: PerTableAcsSnapshot =>
          querySnapshotInOwnTable(ownTable, after, limit, partyIds, templates)
      }
    } yield {
      val eventsInPage =
        applyLimitOrFail("queryAcsSnapshot", limit, events.map(_._2))
      val afterToken = if (eventsInPage.size == limit.limit) events.lastOption.map(_._1) else None
      QueryAcsSnapshotResult(
        migrationId = migrationId,
        snapshotRecordTime = snapshot.snapshotRecordTime,
        createdEventsInPage = eventsInPage,
        afterToken = afterToken,
      )
    }
  }

  private def querySnapshotInOwnTable(
      snapshot: PerTableAcsSnapshot,
      after: Option[QueryAcsSnapshotPaginationToken],
      limit: Limit,
      partyIds: Seq[PartyId],
      templates: Seq[PackageQualifiedName],
  )(implicit
      tc: TraceContext
  ): Future[Vector[
    (
        QueryAcsSnapshotPaginationToken,
        SpliceCreatedEvent,
    )
  ]] = {
    val createsTableName = snapshot.createsTableName
    val stakeholdersTableName = snapshot.stakeholdersTableName
    val afterFilter = after.fold(sql"") {
      case QueryAcsSnapshotPaginationToken.CreatedAtContractIdAcsSnapshotPaginationToken(
            createdAt,
            contractId,
          ) =>
        sql" and (s.created_at, s.contract_id) > ($createdAt, $contractId)"
      case QueryAcsSnapshotPaginationToken.RowIdQueryAcsSnapshotPaginationToken(_) =>
        throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription(s"Invalid after token provided.")
          .asRuntimeException()
    }
    storage
      .query(
        (sql"""
           -- 'created_at' is redundant, but required for 'distinct on' to work
           select distinct on(s.created_at, s.contract_id)
           event_id,
           record_time,
           template_id_package_id,
           template_id,
           s.contract_id,
           create_arguments,
           contract_key,
           signatories,
           observers,
           s.created_at
          from #$stakeholdersTableName s
          join #$createsTableName c on s.contract_id = c.contract_id
          where """ ++ stakeholdersFilter(partyIds) ++
          templatesFilter(templates) ++
          afterFilter ++
          sql"""
             order by s.created_at, s.contract_id
             limit ${sqlLimit(limit)}
          """).toActionBuilder.as[
          (
              String,
              CantonTimestamp,
              String,
              String,
              String,
              String,
              Option[String],
              Seq[String],
              Seq[String],
              CantonTimestamp,
          )
        ],
        "querySnapshotInOwnTable",
      )
      .map(_.map {
        case (
              eventId,
              recordTime,
              packageId,
              rawTemplateIdPackageQualifiedName,
              contractId,
              createArguments,
              contractKey,
              signatories,
              observers,
              createdAt,
            ) =>
          val templateIdPackageQualifiedName =
            PackageQualifiedName.assertFromString(rawTemplateIdPackageQualifiedName)
          QueryAcsSnapshotPaginationToken
            .CreatedAtContractIdAcsSnapshotPaginationToken(
              createdAt,
              contractId,
            ) -> SpliceCreatedEvent(
            eventId = eventId,
            recordTime = recordTime,
            new CreatedEvent(
              /*witnessParties = */ java.util.Collections.emptyList(),
              /*offset = */ 0, // not populated
              /*nodeId = */ EventId.nodeIdFromEventId(eventId),
              /*templateId = */ new Identifier(
                packageId,
                templateIdPackageQualifiedName.qualifiedName.moduleName,
                templateIdPackageQualifiedName.qualifiedName.entityName,
              ),
              /* packageName = */ templateIdPackageQualifiedName.packageName,
              /*contractId = */ contractId,
              /*arguments = */ ProtobufCodec.deserializeValue(createArguments).asRecord().get(),
              /*createdEventBlob = */ ByteString.EMPTY,
              /*interfaceViews = */ java.util.Collections.emptyMap(),
              /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
              /*contractKey = */ contractKey.map(ProtobufCodec.deserializeValue).toJava,
              /*signatories = */ signatories.asJava,
              /*observers = */ observers.asJava,
              /*createdAt = */ createdAt.toInstant,
              /*acsDelta = */ false,
              /*representativePackageId = */ packageId,
            ),
          )
      })
  }

  private def queryLegacyTable(
      snapshot: LegacyAcsSnapshot,
      after: Option[QueryAcsSnapshotPaginationToken],
      limit: Limit,
      partyIds: Seq[PartyId],
      templates: Seq[PackageQualifiedName],
  )(implicit
      tc: TraceContext
  ): Future[Vector[
    (QueryAcsSnapshotPaginationToken, SpliceCreatedEvent)
  ]] = {
    for {
      begin <- after match {
        case Some(
              QueryAcsSnapshotPaginationToken.CreatedAtContractIdAcsSnapshotPaginationToken(_, _)
            ) =>
          Future.failed(
            io.grpc.Status.INVALID_ARGUMENT
              .withDescription(s"Invalid after toke format.")
              .asRuntimeException()
          )
        case Some(
              AcsSnapshotStore.QueryAcsSnapshotPaginationToken.RowIdQueryAcsSnapshotPaginationToken(
                value
              )
            ) if value < snapshot.firstRowId || value > snapshot.lastRowId =>
          Future.failed(
            io.grpc.Status.INVALID_ARGUMENT
              .withDescription(
                s"Invalid after token, outside of snapshot range (${snapshot.firstRowId} to ${snapshot.lastRowId})."
              )
              .asRuntimeException()
          )
        case Some(
              AcsSnapshotStore.QueryAcsSnapshotPaginationToken.RowIdQueryAcsSnapshotPaginationToken(
                value
              )
            ) =>
          Future.successful(value + 1)
        case None => Future.successful(snapshot.firstRowId)
      }
      end = snapshot.lastRowId
      events <- storage
        .query(
          (sql"""
               with snapshot as (
                  -- acs_snapshot_data contains one row per stakeholder of each contract, so we need to deduplicate.
                  -- The rows of each contract have contiguous row_ids: they are inserted ordered by (created_at, contract_id).
                  -- We therefore keep only the last matching row of each contract.
                  -- Unlike a `group by create_id`, this doesn't require fetching all the rows and then grouping them,
                  -- instead it streams rows until it arrives to LIMIT rows.
                  -- i.e., at maximum it will fetch limit*length(stakeholders)*length(templates) rows
                  select create_id, row_id
                  from (
                    select
                      create_id,
                      row_id,
                      lead(create_id) over (order by row_id) as next_create_id
                    from acs_snapshot_data
                    where row_id between $begin and $end and
               """ ++ stakeholdersFilter(partyIds) ++ templatesFilter(templates) ++ sql"""
                  ) matching_rows
                  where next_create_id is distinct from create_id
                  order by row_id asc
                  -- this CTE already will contain all snapshot rows (filtered by party id and template, if necessary).
                  -- They just need to be joined with u_h_creates.
                  -- Applying the limit later yields a worse query plan, where it requires fetching all snapshots first.
                  -- See #1685
                  limit ${sqlLimit(limit)}
               )
               select
                 snapshot.row_id,
                 update_row_id,
                 event_id,
                 contract_id,
                 created_at,
                 template_id_package_id,
                 template_id_module_name,
                 template_id_entity_name,
                 package_name,
                 create_arguments,
                 signatories,
                 observers,
                 contract_key,
                 record_time
              from snapshot
              join update_history_creates creates on creates.row_id = snapshot.create_id
              order by snapshot.row_id
            """).toActionBuilder
            .as[(Long, SelectFromCreateEvents)],
          "queryAcsSnapshot.getCreatedEvents",
        )
    } yield events.map { case (rowId, select) =>
      QueryAcsSnapshotPaginationToken.RowIdQueryAcsSnapshotPaginationToken(
        rowId
      ) -> select.toCreatedEvent
    }
  }

  private def stakeholdersFilter(partyIds: Seq[PartyId]) = {
    def dsoClause = sql" stakeholder = ${dsoParty}"

    NonEmpty.from(partyIds) match {
      case None =>
        // This expression is always true (scan only processes data where the DSO is stakeholder).
        // It is included to make sure the query plan uses the right index (acs_snapshot_data_all_filters)
        dsoClause
      case Some(partyIds) if partyIds.contains(dsoParty) =>
        // Optimization: DSO is stakeholder in all contracts so no need to include filters for the other parties
        dsoClause
      case Some(partyIds) =>
        DbStorage.toInClause("stakeholder", partyIds)
    }
  }

  private def templatesFilter(templates: Seq[PackageQualifiedName]) =
    NonEmpty.from(templates) match {
      case None => sql""
      case Some(templates) =>
        (sql" and " ++ DbStorage.toInClause(
          "template_id",
          templates.map(t =>
            lengthLimited(
              s"${t.packageName}:${t.qualifiedName.moduleName}:${t.qualifiedName.entityName}"
            )
          ),
        )).toActionBuilder
    }

  def getHoldingsState(
      migrationId: Long,
      snapshot: CantonTimestamp,
      after: Option[QueryAcsSnapshotPaginationToken],
      limit: Limit,
      partyIds: NonEmptyVector[PartyId],
  )(implicit tc: TraceContext): Future[QueryAcsSnapshotResult] = {
    this
      .queryAcsSnapshot(
        migrationId,
        snapshot,
        after,
        limit,
        partyIds.toVector,
        AcsSnapshotStore.holdingsTemplates,
      )
      .map { result =>
        val partyIdsSet = partyIds.toVector.toSet
        QueryAcsSnapshotResult(
          result.migrationId,
          result.snapshotRecordTime,
          result.createdEventsInPage
            .filter { createdEvent =>
              AcsSnapshotStore
                .decodeHoldingContract(createdEvent.event)
                .fold(
                  locked =>
                    partyIdsSet
                      .contains(PartyId.tryFromProtoPrimitive(locked.payload.amulet.owner)),
                  amulet =>
                    partyIdsSet.contains(PartyId.tryFromProtoPrimitive(amulet.payload.owner)),
                )
            },
          result.afterToken,
        )
      }
  }

  def getHoldingsSummary(
      migrationId: Long,
      recordTime: CantonTimestamp,
      partyIds: NonEmptyVector[PartyId],
      asOfRound: Long,
  )(implicit tc: TraceContext): Future[AcsSnapshotStore.HoldingsSummaryResult] = {
    this
      .getHoldingsState(
        migrationId,
        recordTime,
        None,
        // if the limit is exceeded by the results from the DB, an exception will be thrown
        HardLimit.tryCreate(Limit.DefaultMaxPageSize),
        partyIds,
      )
      .map { result =>
        val contracts = result.createdEventsInPage
          .map(event => AcsSnapshotStore.decodeHoldingContract(event.event))
        contracts.foldLeft(
          AcsSnapshotStore.HoldingsSummaryResult(migrationId, recordTime, asOfRound, Map.empty)
        ) {
          case (acc, Right(amulet)) => acc.addAmulet(amulet.payload)
          case (acc, Left(lockedAmulet)) => acc.addLockedAmulet(lockedAmulet.payload)
        }
      }
  }

  private def getIncrementalSnapshotAction(
      table: IncrementalAcsSnapshotTable
  ) = {
    sql"""
      select
        snapshot_id,
        history_id,
        table_name,
        record_time,
        migration_id,
        target_record_time
      from acs_incremental_snapshot
      where history_id = $historyId
        and table_name = ${table.tableName}
    """.as[IncrementalAcsSnapshot].headOption
  }

  /** The state of an incremental snapshot.
    *
    * @param table The table name of the incremental snapshot.
    *              MUST be a compile time constant, as it is included directly in the SQL statement.
    */
  def getIncrementalSnapshot(
      table: IncrementalAcsSnapshotTable
  )(implicit tc: TraceContext): Future[Option[IncrementalAcsSnapshot]] = {
    storage
      .querySingle(
        getIncrementalSnapshotAction(table),
        "getIncrementalSnapshot",
      )
      .value
  }

  /** Wraps the given database action in a transaction that makes sure the action silently
    * does nothing if the action has already been applied.
    *
    * Note: Database actions must be idempotent in Canton.
    * For incremental snapshots, all methods of this store modify the state of the incremental snapshot
    * (i.e., the acs_incremental_snapshot table). We therefore use the state to determine if
    * an action has already been applied.
    */
  private def withIncrementalSnapshotIdempotencyCheck[R, E <: Effect](
      table: IncrementalAcsSnapshotTable,
      action: DBIOAction[R, NoStream, E],
      expectedState: Option[IncrementalAcsSnapshot],
  )(implicit
      tc: TraceContext
  ): DBIOAction[Option[R], NoStream, Effect.Transactional & Effect.Read & E] = {
    (for {
      actualState <- getIncrementalSnapshotAction(table)
      result <-
        if (actualState == expectedState) {
          action.map(Option.apply)
        } else {
          logger.info(
            s"Skipping action because actual state $actualState != expected state $expectedState. " +
              "In production, this is expected only during retries after transient network failures."
          )
          DBIOAction.successful(Option.empty)
        }
    } yield result).transactionally
  }

  /** Initializes an incremental snapshot from a snapshot stored in historical storage.
    *
    * @param table             The table name of the incremental snapshot.
    *                          MUST be a compile time constant, as it is included directly in the SQL statement.
    * @param initializeFrom    The snapshot to initialize from.
    * @param targetRecordTime  The record time at which the incremental snapshot should be finished and copied back
    *                          to a historical snapshot.
    */
  def initializeIncrementalSnapshot(
      table: IncrementalAcsSnapshotTable,
      initializeFromT: AcsSnapshot,
      targetRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    val initializeFrom = initializeFromT match {
      case legacy: LegacyAcsSnapshot => legacy
      case _: PerTableAcsSnapshot =>
        // If we enable PerTableAcsSnapshots, we will necessarily initialize from a LegacyAcsSnapshot,
        // never from a PerTableAcsSnapshot. Then initializeIncrementalSnapshot will never be called again.
        throw io.grpc.Status.FAILED_PRECONDITION
          .withDescription(
            "BUG: This shouldn't be called: we shouldn't be initializing from a PerTableAcsSnapshot."
          )
          .asRuntimeException()
    }
    assert(targetRecordTime.isAfter(initializeFrom.snapshotRecordTime))
    val statement = for {
      snapshotId <- sql"""
        insert into acs_incremental_snapshot (
          history_id,
          table_name,
          record_time,
          migration_id,
          target_record_time
        )
        values (
          ${historyId},
          ${table.tableName},
          ${initializeFrom.snapshotRecordTime},
          ${initializeFrom.migrationId},
          ${targetRecordTime}
        )
        returning snapshot_id
      """.as[Long].head
      insertedRows <- (sql"""
        insert into #${table.tableName} (
          """ ++ table.copyFromUpdateHistoryTargetColumns ++ sql""",
          snapshot_id
        )
        select
          """ ++ table.copyFromUpdateHistorySourceColumns(dsoParty) ++ sql""",
          $snapshotId
        from acs_snapshot_data d
        join update_history_creates c on d.create_id=c.row_id
        where
          d.row_id between ${initializeFrom.firstRowId} and ${initializeFrom.lastRowId}
          -- The source table `acs_snapshot_data` contains one row per stakeholder for each contract,
          -- the target table contains only one row per contract.
          -- We know the DSO is a stakeholder of all contracts in the scan ACS, so we can filter by that.
          and d.stakeholder = $dsoParty
      """).toActionBuilder.asUpdate
    } yield {
      logger.info(
        s"Initialized incremental snapshot $snapshotId at ${initializeFrom.snapshotRecordTime} from historical snapshot. " +
          s"ACS: $insertedRows."
      )
    }

    storage
      .queryAndUpdate(
        withIncrementalSnapshotIdempotencyCheck(table, statement, None),
        "initializeIncrementalSnapshot",
      )
      .map(_ => ())
  }

  /** Initializes an incremental snapshot that represents an empty ACS at the given record time.
    *
    * @param table             The table name of the incremental snapshot.
    *                          MUST be a compile time constant, as it is included directly in the SQL statement.
    * @param recordTime        The record time as of which the ACS is empty. Use a time just before the first
    *                          non-import update.
    * @param targetRecordTime  The record time at which the incremental snapshot should be finished and copied back
    *                          to a historical snapshot.
    * @param migrationId       The migration id of the snapshot.
    */
  def initializeIncrementalSnapshotFromImportUpdates(
      table: IncrementalAcsSnapshotTable,
      recordTime: CantonTimestamp,
      targetRecordTime: CantonTimestamp,
      migrationId: Long,
  )(implicit tc: TraceContext): Future[Unit] = {
    assert(targetRecordTime.isAfter(recordTime))
    val statement = for {
      snapshotId <- sql"""
          insert into acs_incremental_snapshot (
            history_id,
            table_name,
            record_time,
            migration_id,
            target_record_time
          )
          values (
            ${historyId},
            ${table.tableName},
            ${recordTime},
            ${migrationId},
            ${targetRecordTime}
          )
          returning snapshot_id
        """.as[Long].head
      insertedRows <-
        (sql"""
          insert into #${table.tableName} (
            """ ++ table.copyFromUpdateHistoryTargetColumns ++ sql""",
            snapshot_id
          )
          select
            """ ++ table.copyFromUpdateHistorySourceColumns(dsoParty) ++ sql""",
            $snapshotId
          from update_history_creates c
          where history_id = $historyId
            and migration_id = ${migrationId}
            and record_time = ${CantonTimestamp.MinValue}
        """).toActionBuilder.asUpdate
    } yield {
      logger.debug(
        s"Initialized incremental snapshot $snapshotId at $recordTime from import updates. ACS: $insertedRows."
      )
    }

    storage
      .queryAndUpdate(
        withIncrementalSnapshotIdempotencyCheck(table, statement, None),
        "initializeIncrementalSnapshotFromImportUpdates",
      )
      .map(_ => ())
  }

  /** Copies an incremental snapshot to historical storage.
    *
    * @param table The table name of the incremental snapshot.
    *   MUST be a compile time constant, as it is included directly in the SQL statement.
    * @param snapshot The incremental snapshot to update.
    * @param nextSnapshotTargetRecordTime The record time at which the next incremental snapshot
    *   should be finished and copied back to a historical snapshot.
    */
  def saveIncrementalSnapshot(
      table: IncrementalAcsSnapshotTable,
      snapshot: IncrementalAcsSnapshot,
      nextSnapshotTargetRecordTime: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[Option[AcsSnapshotStore.SaveIncrementalAcsSnapshotInsertedRows]] = {
    logger.debug(
      s"Saving incremental snapshot ${snapshot.snapshotId} at ${snapshot.recordTime}"
    )
    assert(snapshot.tableName == table.tableName)
    assert(snapshot.historyId == historyId)
    assert(snapshot.recordTime == snapshot.targetRecordTime)
    val statement: DBIO[AcsSnapshotStore.SaveIncrementalAcsSnapshotInsertedRows] = table match {
      case IncrementalAcsSnapshotTable.Next | IncrementalAcsSnapshotTable.Backfill =>
        saveLegacyIncrementalSnapshotStatement(table, snapshot, nextSnapshotTargetRecordTime)
      case IncrementalAcsSnapshotTable.NextV2 =>
        saveV2IncrementalSnapshot(table, snapshot, nextSnapshotTargetRecordTime)(tc)
    }
    storage.queryAndUpdate(
      withIncrementalSnapshotIdempotencyCheck(
        table,
        statement,
        Some(snapshot),
      ),
      "saveIncrementalSnapshot",
    )
  }

  private def saveV2IncrementalSnapshot(
      table: IncrementalAcsSnapshotTable,
      snapshot: IncrementalAcsSnapshot,
      nextSnapshotTargetRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): DBIO[AcsSnapshotStore.SaveIncrementalAcsSnapshotInsertedRows] = {
    val createsTableName =
      s"acs_snapshot_creates_v1_${historyId}_${snapshot.targetRecordTime.toEpochMilli}"
    val stakeholdersTableName =
      s"acs_snapshot_stakeholders_v1_${historyId}_${snapshot.targetRecordTime.toEpochMilli}"

    for {
      _ <-
        sqlu"create table #$createsTableName (like acs_snapshot_creates_v1_template including all)"
      _ <-
        sqlu"create table #$stakeholdersTableName (like acs_snapshot_stakeholders_v1_template including all)"
      // `snapshot_id= ?` will match all rows in production, so a direct table scan will be used
      copiedCreateRows <- (sql"""
        insert into #$createsTableName (contract_id, create_arguments, event_id, record_time, template_id_package_id, contract_key, created_at, signatories, observers, unlocked_amulet_balance, locked_amulet_balance)
        select s.contract_id, s.create_arguments, s.event_id, s.record_time, s.template_id_package_id, s.contract_key, s.created_at, s.signatories, s.observers, """ ++ IncrementalAcsSnapshotTable.QueryParts
        .unlockedAmuletBalance(dsoParty) ++ sql", " ++ IncrementalAcsSnapshotTable.QueryParts
        .lockedAmuletBalance(dsoParty) ++ sql"""
        from #${table.tableName} s
        where s.snapshot_id = ${snapshot.snapshotId}
      """).toActionBuilder.asUpdate
      copiedStakeholderRows <- sqlu"""
        insert into #${stakeholdersTableName} (stakeholder, template_id, contract_id, created_at)
        select stakeholder, concat(s.package_name, ':', s.template_id_module_name, ':', s.template_id_entity_name) as template_id, contract_id, created_at
        from #${table.tableName} s
        cross join unnest(array_cat(s.observers, s.signatories)) as stakeholder
        where s.snapshot_id = ${snapshot.snapshotId}
      """
      // Indexes are created by `AcsSnapshotIndexTrigger` calling `indexSnapshotTable` in order to:
      // - prevent this from blocking for too long
      // - allow the index creation to be retried in case of a transient failure

      (unlocked_amulet_balance, locked_amulet_balance) <- sql"""
        select
            sum(s.unlocked_amulet_balance) AS unlocked_amulet_balance,
            sum(s.locked_amulet_balance) AS locked_amulet_balance
        from #${table.tableName} s
        where snapshot_id = ${snapshot.snapshotId}
      """.as[(BigDecimal, BigDecimal)].head

      _ <- sqlu"""
        insert into acs_snapshot (
          snapshot_record_time,
          migration_id,
          history_id,
          first_row_id,
          last_row_id,
          unlocked_amulet_balance,
          locked_amulet_balance,
          creates_table_name,
          stakeholders_table_name,
          indexes_created
        )
        values (
          ${snapshot.recordTime},
          ${snapshot.migrationId},
          ${snapshot.historyId},
          null,
          null,
          ${unlocked_amulet_balance},
          ${locked_amulet_balance},
          ${createsTableName},
          ${stakeholdersTableName},
          false
        )
       """

      _ <- sqlu"""
        update acs_incremental_snapshot
        set
          target_record_time = ${nextSnapshotTargetRecordTime}
        where snapshot_id = ${snapshot.snapshotId}
      """
    } yield {
      logger.debug(
        s"Saved incremental snapshot ${snapshot.snapshotId} at ${snapshot.recordTime} with $copiedCreateRows create rows and $copiedStakeholderRows stakeholder rows." +
          s" Next snapshot target record time: $nextSnapshotTargetRecordTime"
      )
      // This doesn't make much sense anymore
      AcsSnapshotStore.SaveIncrementalAcsSnapshotInsertedRows(
        copiedCreateRows,
        copiedStakeholderRows,
      )
    }
  }

  def indexSnapshotStakeholdersTable(
      snapshot: PerTableAcsSnapshot
  )(implicit tc: TraceContext): Future[Unit] = {
    // This doesn't have to be transactional:
    // - if the `set indexes_created = true` executes, we know everything succeeded.
    // - otherwise, on retry the `create index IF NOT EXISTS` will just move on.
    for {
      _ <- storage.queryAndUpdate(
        AdvisoryLocks.withSessionLock(
          AdvisoryLockIds.acsSnapshotIndex,
          AcsSnapshotDDL.stakeholderIndexAction(
            snapshot.stakeholdersTableName,
            historyId,
            snapshot.snapshotRecordTime,
          ),
        ),
        "index_stakeholders",
      )
      _ <- storage.queryAndUpdate(
        AdvisoryLocks.withSessionLock(
          AdvisoryLockIds.acsSnapshotIndex,
          AcsSnapshotDDL.stakeholderTemplateIdIndexAction(
            snapshot.stakeholdersTableName,
            historyId,
            snapshot.snapshotRecordTime,
          ),
        ),
        "index_stakeholders_templateid",
      )
      _ <- storage
        .update(
          sqlu"""
            update acs_snapshot
            set indexes_created = true
            where history_id = $historyId
            and   snapshot_record_time = ${snapshot.snapshotRecordTime}
          """,
          "markSnapshotAsIndexed",
        )
    } yield ()
  }

  private def saveLegacyIncrementalSnapshotStatement(
      table: IncrementalAcsSnapshotTable,
      snapshot: IncrementalAcsSnapshot,
      nextSnapshotTargetRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): DBIO[AcsSnapshotStore.SaveIncrementalAcsSnapshotInsertedRows] = {
    for {
      // Note: Only one client can write to acs_snapshot_data at a time, enforced via advisory locks.
      // We therefore don't need to worry about concurrent writes between getting max_row_id_before and using it.
      max_row_id_before <- sql"""
        select coalesce(max(row_id), -1) from acs_snapshot_data
      """.as[Long].head

      // Copy rows from incremental snapshot to acs_snapshot_data.
      // This is the main, slow part of this operation.
      copiedRows <- sqlu"""
        insert into acs_snapshot_data (create_id, template_id, stakeholder)
        select s.create_id, s.template_id, stakeholder
        from #${table.tableName} s
        cross join unnest(s.stakeholders) as stakeholder
        where s.snapshot_id = ${snapshot.snapshotId}
        order by created_at, contract_id
      """

      (min_row_id, max_row_id) <- sql"""
        select
          min(row_id) as min_row_id,
          max(row_id) as max_row_id
        from acs_snapshot_data
        where row_id > $max_row_id_before
      """.as[(Long, Long)].head

      (unlocked_amulet_balance, locked_amulet_balance) <- sql"""
        select
            sum(s.unlocked_amulet_balance) AS unlocked_amulet_balance,
            sum(s.locked_amulet_balance) AS locked_amulet_balance
        from #${table.tableName} s
        where snapshot_id = ${snapshot.snapshotId}
      """.as[(BigDecimal, BigDecimal)].head

      _ <- sqlu"""
        insert into acs_snapshot (
          snapshot_record_time,
          migration_id,
          history_id,
          first_row_id,
          last_row_id,
          unlocked_amulet_balance,
          locked_amulet_balance
        )
        values (
          ${snapshot.recordTime},
          ${snapshot.migrationId},
          ${snapshot.historyId},
          ${min_row_id},
          ${max_row_id},
          ${unlocked_amulet_balance},
          ${locked_amulet_balance}
        )
       """

      _ <- sqlu"""
        update acs_incremental_snapshot
        set
          target_record_time = ${nextSnapshotTargetRecordTime}
        where snapshot_id = ${snapshot.snapshotId}
      """
    } yield {
      logger.debug(
        s"Saved incremental snapshot ${snapshot.snapshotId} at ${snapshot.recordTime} with $copiedRows rows." +
          s" Next snapshot target record time: $nextSnapshotTargetRecordTime"
      )
      // Best effort at reporting numbers
      AcsSnapshotStore.SaveIncrementalAcsSnapshotInsertedRows(copiedRows, copiedRows)
    }
  }

  /** Updates an incremental snapshot to a new record time.
    *
    * @param table             The table name of the incremental snapshot.
    *                          MUST be a compile time constant, as it is included directly in the SQL statement.
    * @param snapshot          The incremental snapshot to update.
    * @param targetRecordTime  The record time to update the snapshot to.
    */
  def updateIncrementalSnapshot(
      table: IncrementalAcsSnapshotTable,
      snapshot: IncrementalAcsSnapshot,
      targetRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    assert(snapshot.tableName == table.tableName)
    assert(snapshot.historyId == historyId)
    // snapshot.recordTime < targetRecordTime <= snapshot.targetRecordTime
    assert(targetRecordTime.isAfter(snapshot.recordTime))
    assert(
      !targetRecordTime.isAfter(snapshot.targetRecordTime),
      s"Target record time ($targetRecordTime) must be <= snapshot's target record time ${snapshot.targetRecordTime}",
    )
    logger.debug(
      s"Updating incremental snapshot ${snapshot.snapshotId} from ${snapshot.recordTime} to $targetRecordTime"
    )
    val statement = for {
      insertedRows <-
        (sql"""
          insert into #${table.tableName} (
            """ ++ table.copyFromUpdateHistoryTargetColumns ++ sql""",
            snapshot_id
          )
          select
            """ ++ table.copyFromUpdateHistorySourceColumns(dsoParty) ++ sql""",
            ${snapshot.snapshotId}
          from update_history_creates c
          where history_id = $historyId
            and migration_id = ${snapshot.migrationId}
            and record_time > ${snapshot.recordTime}
            and record_time <= $targetRecordTime
        """).toActionBuilder.asUpdate
      deletedRows <-
        sql"""
          delete
          from #${table.tableName} as s
          using update_history_exercises as e
          where s.snapshot_id = ${snapshot.snapshotId}
            and s.contract_id = e.contract_id
            and e.history_id = $historyId
            and e.migration_id = ${snapshot.migrationId}
            and e.record_time > ${snapshot.recordTime}
            and e.record_time <= $targetRecordTime
            and e.consuming
        """.as[Int].head
      _ <- sqlu"""
          update acs_incremental_snapshot
          set record_time = $targetRecordTime
          where snapshot_id = ${snapshot.snapshotId}
        """
    } yield {
      logger.info(
        s"Updated incremental snapshot ${snapshot.snapshotId} from ${snapshot.recordTime} to $targetRecordTime. ACS: +$insertedRows, -$deletedRows."
      )
      ()
    }
    storage.queryAndUpdate(statement.transactionally, "updateIncrementalSnapshot")
  }

  def deleteIncrementalSnapshot(
      table: IncrementalAcsSnapshotTable,
      snapshot: IncrementalAcsSnapshot,
  )(implicit tc: TraceContext): Future[Unit] = {
    assert(snapshot.tableName == table.tableName)
    assert(snapshot.historyId == historyId)
    val statement = for {
      _ <- sqlu"""delete from #${table.tableName} where snapshot_id = ${snapshot.snapshotId}"""
      _ <- sqlu"""delete from acs_incremental_snapshot where snapshot_id = ${snapshot.snapshotId}"""
    } yield ()
    storage
      .queryAndUpdate(
        withIncrementalSnapshotIdempotencyCheck(table, statement, Some(snapshot)),
        "deleteIncrementalSnapshot",
      )
      .map(_ => ())
  }
}

object AcsSnapshotStore {

  sealed trait IncrementalAcsSnapshotTable {
    def tableName: String
    def copyFromUpdateHistoryTargetColumns: SQLActionBuilderChain
    def copyFromUpdateHistorySourceColumns(dsoParty: PartyId): SQLActionBuilderChain
  }
  object IncrementalAcsSnapshotTable {
    case object NextV2 extends IncrementalAcsSnapshotTable {
      val tableName: String = "acs_incremental_snapshot_data_next_v2"

      override def copyFromUpdateHistoryTargetColumns: SQLActionBuilderChain =
        QueryParts.v2CopyFromUpdateHistoryTargetColumns

      override def copyFromUpdateHistorySourceColumns(dsoParty: PartyId): SQLActionBuilderChain =
        QueryParts.v2CopyFromUpdateHistorySourceColumns(dsoParty)
    }
    case object Next extends IncrementalAcsSnapshotTable {
      val tableName: String = "acs_incremental_snapshot_data_next"

      override def copyFromUpdateHistoryTargetColumns: SQLActionBuilderChain =
        QueryParts.legacyCopyFromUpdateHistoryTargetColumns

      override def copyFromUpdateHistorySourceColumns(dsoParty: PartyId): SQLActionBuilderChain =
        QueryParts.legacyCopyFromUpdateHistorySourceColumns(dsoParty)
    }
    case object Backfill extends IncrementalAcsSnapshotTable {
      val tableName: String = "acs_incremental_snapshot_data_backfill"

      override def copyFromUpdateHistoryTargetColumns: SQLActionBuilderChain =
        QueryParts.legacyCopyFromUpdateHistoryTargetColumns

      override def copyFromUpdateHistorySourceColumns(dsoParty: PartyId): SQLActionBuilderChain =
        QueryParts.legacyCopyFromUpdateHistorySourceColumns(dsoParty)
    }

    object QueryParts {

      private[IncrementalAcsSnapshotTable] val v2CopyFromUpdateHistoryTargetColumns
          : SQLActionBuilderChain = {
        sql"""
            create_arguments,
            event_id,
            record_time,
            template_id_package_id,
            package_name,
            template_id_module_name,
            template_id_entity_name,
            contract_key,
            signatories,
            observers,
            contract_id,
            created_at,
            unlocked_amulet_balance,
            locked_amulet_balance
           """
      }

      private[IncrementalAcsSnapshotTable] def v2CopyFromUpdateHistorySourceColumns(
          dsoParty: PartyId
      ): SQLActionBuilderChain = {
        sql"""
            c.create_arguments,
            c.event_id,
            c.record_time,
            c.template_id_package_id,
            c.package_name,
            c.template_id_module_name,
            c.template_id_entity_name,
            c.contract_key,
            c.signatories,
            c.observers,
            c.contract_id,
            c.created_at,""" ++
          unlockedAmuletBalance(dsoParty) ++ sql"," ++
          lockedAmuletBalance(dsoParty)
      }

      private[IncrementalAcsSnapshotTable] val legacyCopyFromUpdateHistoryTargetColumns
          : SQLActionBuilderChain = {
        sql"""
            create_id,
            template_id,
            stakeholders,
            contract_id,
            created_at,
            unlocked_amulet_balance,
            locked_amulet_balance"""
      }

      private[IncrementalAcsSnapshotTable] def legacyCopyFromUpdateHistorySourceColumns(
          dsoParty: PartyId
      ): SQLActionBuilderChain = {
        sql"""
              c.row_id,
              concat(c.package_name, ':', c.template_id_module_name, ':', c.template_id_entity_name) as template_id,
              array_cat(c.signatories, c.observers) as stakeholder,
              c.contract_id,
              c.created_at,
           """ ++ unlockedAmuletBalance(dsoParty) ++ sql"," ++
          lockedAmuletBalance(dsoParty)
      }

      def unlockedAmuletBalance(dsoParty: PartyId) = {
        sql"""
           case
            when package_name = ${Amulet.COMPANION.PACKAGE_NAME}
              and template_id_module_name = ${Amulet.COMPANION.TEMPLATE_ID.getModuleName}
              and template_id_entity_name = ${Amulet.COMPANION.TEMPLATE_ID.getEntityName}
              and $dsoParty = ANY(signatories)
            then (create_arguments->'record'->'fields'->2->'value'->'record'->'fields'->0->'value'->>'numeric')::numeric
            else 0
          end
         """
      }

      def lockedAmuletBalance(dsoParty: PartyId) = {
        sql"""
           case
            when package_name = ${LockedAmulet.COMPANION.PACKAGE_NAME}
              and template_id_module_name = ${LockedAmulet.COMPANION.TEMPLATE_ID.getModuleName}
              and template_id_entity_name = ${LockedAmulet.COMPANION.TEMPLATE_ID.getEntityName}
              and $dsoParty = ANY(signatories)
            then (create_arguments->'record'->'fields'->0->'value'->'record'->'fields'->2->'value'->'record'->'fields'->0->'value'->>'numeric')::numeric
            else 0
          end
         """
      }

    }
  }

  // Only relevant for tests, in production this is already guaranteed.
  private val PreventConcurrentSnapshotsSemaphore = new Semaphore(1)

  final case class IncrementalAcsSnapshot(
      snapshotId: Long,
      historyId: Long,
      tableName: String,
      recordTime: CantonTimestamp,
      migrationId: Long,
      targetRecordTime: CantonTimestamp,
  ) extends PrettyPrinting {

    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotId", _.snapshotId),
      param("historyId", _.historyId),
      param("tableName", _.tableName.unquoted),
      param("recordTime", _.recordTime),
      param("migrationId", _.migrationId),
      param("targetRecordTime", _.targetRecordTime),
    )
  }

  object IncrementalAcsSnapshot {
    implicit val incrementalSnapshotGetResult: GetResult[IncrementalAcsSnapshot] = GetResult(r =>
      IncrementalAcsSnapshot(
        snapshotId = r.<<[Long],
        historyId = r.<<[Long],
        tableName = r.<<[String],
        recordTime = r.<<[CantonTimestamp],
        migrationId = r.<<[Long],
        targetRecordTime = r.<<[CantonTimestamp],
      )
    )
  }

  sealed trait AcsSnapshot extends PrettyPrinting {
    val snapshotRecordTime: CantonTimestamp
    val migrationId: Long
    val historyId: Long
    val unlockedAmuletBalance: Option[BigDecimal]
    val lockedAmuletBalance: Option[BigDecimal]
    val indexesCreated: Boolean
  }

  case class LegacyAcsSnapshot(
      snapshotRecordTime: CantonTimestamp,
      migrationId: Long,
      historyId: Long,
      firstRowId: Long,
      lastRowId: Long,
      unlockedAmuletBalance: Option[BigDecimal],
      lockedAmuletBalance: Option[BigDecimal],
      indexesCreated: Boolean,
  ) extends AcsSnapshot {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotRecordTime", _.snapshotRecordTime),
      param("migrationId", _.migrationId),
      param("historyId", _.historyId),
      param("firstRowId", _.firstRowId),
      param("lastRowId", _.lastRowId),
      param("unlockedAmuletBalance", _.unlockedAmuletBalance),
      param("lockedAmuletBalance", _.lockedAmuletBalance),
      param("indexesCreated", _.indexesCreated),
    )
  }

  case class PerTableAcsSnapshot(
      snapshotRecordTime: CantonTimestamp,
      migrationId: Long,
      historyId: Long,
      createsTableName: String,
      stakeholdersTableName: String,
      unlockedAmuletBalance: Option[BigDecimal],
      lockedAmuletBalance: Option[BigDecimal],
      indexesCreated: Boolean,
  ) extends AcsSnapshot {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotRecordTime", _.snapshotRecordTime),
      param("migrationId", _.migrationId),
      param("historyId", _.historyId),
      param("createsTableName", _.createsTableName.singleQuoted),
      param("stakeholdersTableName", _.stakeholdersTableName.singleQuoted),
      param("unlockedAmuletBalance", _.unlockedAmuletBalance),
      param("lockedAmuletBalance", _.lockedAmuletBalance),
      param("indexesCreated", _.indexesCreated),
    )
  }

  object AcsSnapshot {
    implicit val acsSnapshotGetResult: GetResult[AcsSnapshot] = GetResult { r =>
      val snapshotRecordTime = r.<<[CantonTimestamp]
      val migrationId = r.<<[Long]
      val historyId = r.<<[Long]
      val firstRowId = r.<<[Option[Long]]
      val lastRowId = r.<<[Option[Long]]
      val unlockedAmuletBalance = r.<<[Option[BigDecimal]]
      val lockedAmuletBalance = r.<<[Option[BigDecimal]]
      val createsTableName = r.<<[Option[String]]
      val stakeholdersTableName = r.<<[Option[String]]
      val indexesCreated = r.<<[Boolean]
      (firstRowId, lastRowId, createsTableName, stakeholdersTableName) match {
        case (Some(first), Some(last), None, None) =>
          LegacyAcsSnapshot(
            snapshotRecordTime,
            migrationId,
            historyId,
            first,
            last,
            unlockedAmuletBalance,
            lockedAmuletBalance,
            indexesCreated,
          )
        case (None, None, Some(createsTableName), Some(stakeholdersTableName)) =>
          PerTableAcsSnapshot(
            snapshotRecordTime,
            migrationId,
            historyId,
            createsTableName,
            stakeholdersTableName,
            unlockedAmuletBalance,
            lockedAmuletBalance,
            indexesCreated,
          )
        case _ =>
          throw new IllegalStateException(
            s"Invalid ACS snapshot row: recordTime=$snapshotRecordTime firstRowId=$firstRowId, lastRowId=$lastRowId, createsTableName=$createsTableName, stakeholdersTableName=$stakeholdersTableName. " +
              s"The constraint 'legacy_or_per_snapshot' should make this impossible."
          )
      }
    }
  }

  sealed trait QueryAcsSnapshotPaginationToken {
    def encodeToBase64: String = {
      val jsonString = QueryAcsSnapshotPaginationToken.encoder(this).noSpaces
      java.util.Base64.getEncoder.encodeToString(jsonString.getBytes(StandardCharsets.UTF_8))
    }
  }
  object QueryAcsSnapshotPaginationToken {
    import cats.implicits.*

    case class CreatedAtContractIdAcsSnapshotPaginationToken(
        createdAt: CantonTimestamp,
        contractId: String,
    ) extends QueryAcsSnapshotPaginationToken
    case class RowIdQueryAcsSnapshotPaginationToken(after: Long)
        extends QueryAcsSnapshotPaginationToken

    private val decoder: io.circe.Decoder[QueryAcsSnapshotPaginationToken] = io.circe
      .Decoder[Long]
      .map(RowIdQueryAcsSnapshotPaginationToken(_): QueryAcsSnapshotPaginationToken)
      .or(
        (new io.circe.Decoder[CreatedAtContractIdAcsSnapshotPaginationToken] {
          override def apply(c: HCursor): Result[CreatedAtContractIdAcsSnapshotPaginationToken] =
            for {
              createdAt <- c.downField("created_at").as[Long]
              contractId <- c.downField("contract_id").as[String]
            } yield CreatedAtContractIdAcsSnapshotPaginationToken(
              CantonTimestamp.assertFromLong(createdAt),
              contractId,
            )
        }).widen
      )

    private val encoder: io.circe.Encoder[QueryAcsSnapshotPaginationToken] = {
      case CreatedAtContractIdAcsSnapshotPaginationToken(createdAt, contractId) =>
        io.circe.Json.obj(
          "created_at" -> io.circe.Json.fromLong(createdAt.toMicros),
          "contract_id" -> io.circe.Json.fromString(contractId),
        )
      case RowIdQueryAcsSnapshotPaginationToken(after) => io.circe.Encoder[Long].apply(after)
    }

    def tryDecodeFromBase64(token: String): QueryAcsSnapshotPaginationToken = {
      import cats.implicits.*

      (for {
        decodedString <- scala.util
          .Try {
            val decodedBytes = java.util.Base64.getDecoder.decode(token)
            new String(decodedBytes, StandardCharsets.UTF_8)
          }
          .toEither
          .leftMap(_ => "Failed to decode base64 token")
        decoded <- io.circe.parser.decode(decodedString)(decoder).leftMap(_.getMessage)
      } yield decoded).fold(
        msg =>
          throw io.grpc.Status.INVALID_ARGUMENT
            .withDescription(msg)
            .asRuntimeException(),
        identity,
      )
    }
  }

  case class QueryAcsSnapshotResult(
      migrationId: Long,
      snapshotRecordTime: CantonTimestamp,
      createdEventsInPage: Vector[SpliceCreatedEvent],
      afterToken: Option[QueryAcsSnapshotPaginationToken],
  )

  private val amuletQualifiedName =
    PackageQualifiedName.fromJavaCodegenCompanion(Amulet.COMPANION)
  private val lockedAmuletQualifiedName =
    PackageQualifiedName.fromJavaCodegenCompanion(LockedAmulet.COMPANION)
  private val holdingsTemplates = Vector(amuletQualifiedName, lockedAmuletQualifiedName)

  private def decodeHoldingContract(createdEvent: CreatedEvent): Either[
    Contract[LockedAmulet.ContractId, LockedAmulet],
    Contract[Amulet.ContractId, Amulet],
  ] = {
    def failedToDecode = throw io.grpc.Status.FAILED_PRECONDITION
      .withDescription(s"Failed to decode $createdEvent")
      .asRuntimeException()
    if (
      PackageQualifiedName
        .fromEvent(createdEvent) == PackageQualifiedName.fromJavaCodegenCompanion(Amulet.COMPANION)
    ) {
      Right(Contract.fromCreatedEvent(Amulet.COMPANION)(createdEvent).getOrElse(failedToDecode))
    } else {
      if (
        PackageQualifiedName.fromEvent(createdEvent) != PackageQualifiedName
          .fromJavaCodegenCompanion(LockedAmulet.COMPANION)
      ) {
        throw io.grpc.Status.INTERNAL
          .withDescription(
            s"Unexpected holding contract, expected either Amulet or LockedAmulet: $createdEvent"
          )
          .asRuntimeException()
      }
      Left(
        Contract.fromCreatedEvent(LockedAmulet.COMPANION)(createdEvent).getOrElse(failedToDecode)
      )
    }
  }

  case class HoldingsSummaryResult(
      migrationId: Long,
      recordTime: CantonTimestamp,
      asOfRound: Long,
      summaries: Map[PartyId, HoldingsSummary],
  ) {
    private val summaryZero = HoldingsSummary(0, 0, 0, 0, 0, 0, 0)
    def addAmulet(amulet: Amulet): HoldingsSummaryResult =
      copy(summaries = summaries.updatedWith(PartyId.tryFromProtoPrimitive(amulet.owner)) { entry =>
        Some(entry.getOrElse(summaryZero).addAmulet(amulet, asOfRound))
      })
    def addLockedAmulet(amulet: LockedAmulet): HoldingsSummaryResult =
      copy(summaries = summaries.updatedWith(PartyId.tryFromProtoPrimitive(amulet.amulet.owner)) {
        entry =>
          Some(entry.getOrElse(summaryZero).addLockedAmulet(amulet, asOfRound))
      })
  }

  case class SaveIncrementalAcsSnapshotInsertedRows(
      createRows: Int,
      stakeholderRows: Int,
  )

  object AcsSnapshotDDL {
    def stakeholderIndexName(historyId: Long, snapshotRecordTime: CantonTimestamp) =
      s"acs_snapshot_stakeholders_${historyId}_${snapshotRecordTime.toEpochMilli}_s_ca_ci"

    def stakeholderIndexAction(
        stakeholdersTableName: String,
        historyId: Long,
        snapshotRecordTime: CantonTimestamp,
    ) =
      sql"""create index if not exists #${stakeholderIndexName(
          historyId,
          snapshotRecordTime,
        )}
           on #$stakeholdersTableName (stakeholder, created_at, contract_id) """.asUpdate

    def stakeholderTemplateIdIndexName(historyId: Long, snapshotRecordTime: CantonTimestamp) =
      s"acs_snapshot_stakeholders_${historyId}_${snapshotRecordTime.toEpochMilli}_s_tid_ca_ci"

    def stakeholderTemplateIdIndexAction(
        stakeholdersTableName: String,
        historyId: Long,
        snapshotRecordTime: CantonTimestamp,
    ) =
      sql"""create index if not exists #${stakeholderTemplateIdIndexName(
          historyId,
          snapshotRecordTime,
        )}
           on #$stakeholdersTableName (stakeholder, template_id, created_at, contract_id) """.asUpdate
  }

  def apply(
      storage: DbStorage,
      updateHistory: UpdateHistory,
      dsoParty: PartyId,
      migrationId: Long,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, closeContext: CloseContext): AcsSnapshotStore =
    new AcsSnapshotStore(storage, updateHistory, dsoParty, migrationId, loggerFactory)

}
