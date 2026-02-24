// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import cats.data.NonEmptyVector
import com.daml.ledger.javaapi.data.CreatedEvent
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, LockedAmulet}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  QueryAcsSnapshotResult,
  amuletQualifiedName,
  lockedAmuletQualifiedName,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.SelectFromCreateEvents
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, LimitHelpers, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsQueries, AdvisoryLockIds}
import org.lfdecentralizedtrust.splice.util.{Contract, HoldingsSummary, PackageQualifiedName}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.{GetResult, JdbcProfile}

import java.util.concurrent.Semaphore
import scala.concurrent.{ExecutionContext, Future}

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

  override val profile: JdbcProfile = storage.profile.jdbc
  import profile.api.jdbcActionExtensionMethods

  private def historyId = updateHistory.historyId

  def lookupSnapshotAtOrBefore(
      migrationId: Long,
      before: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Option[AcsSnapshot]] = {
    storage
      .querySingle(
        sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance
            from acs_snapshot
            where snapshot_record_time <= $before
              and migration_id = $migrationId
              and history_id = $historyId
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
      sql"select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance "
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

  def insertNewSnapshot(
      lastSnapshot: Option[AcsSnapshot],
      migrationId: Long,
      until: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[Int] = {
    Future {
      scala.concurrent.blocking {
        AcsSnapshotStore.PreventConcurrentSnapshotsSemaphore.acquire()
      }
    }.flatMap { _ =>
      val from = lastSnapshot.map(_.snapshotRecordTime).getOrElse(CantonTimestamp.MinValue)
      val gtFrom = lastSnapshot.fold(">=")(_ => ">")
      val previousSnapshotDataFilter = lastSnapshot match {
        case Some(AcsSnapshot(_, _, _, firstRowId, lastRowId, _, _)) =>
          sql"where snapshot.row_id >= $firstRowId and snapshot.row_id <= $lastRowId"
        case None =>
          sql"where false"
      }
      def recordTimeFilter(tableAlias: String) =
        sql"""
          where #$tableAlias.history_id = $historyId
            and #$tableAlias.migration_id = $migrationId
            and #$tableAlias.record_time #$gtFrom $from -- this will be >= MinValue for the first snapshot, which includes ACS imports, otherwise >
            and #$tableAlias.record_time <= $until
           """
      val statement = (sql"""
            with previous_snapshot_data as (select contract_id
                                            from acs_snapshot_data snapshot
                                                     join update_history_creates creates on snapshot.create_id = creates.row_id
                                            """ ++ previousSnapshotDataFilter ++
        sql"""      ),
                new_creates as (select contract_id
                                from update_history_creates creates
                                """ ++ recordTimeFilter("creates") ++ sql"""
                    ),
                archives as (select contract_id
                             from update_history_exercises archives
                             """ ++ recordTimeFilter("archives") ++ sql"""
                               and consuming),
                contracts_to_insert as (select contract_id
                                from previous_snapshot_data
                                union
                                select contract_id
                                from new_creates
                                except
                                select contract_id
                                from archives),
                -- these two materialized CTEs force the join order in a way that doesn't completely blow up the number of rows
                creates_to_insert as materialized (select row_id,
                                                          package_name,
                                                          template_id_module_name,
                                                          template_id_entity_name,
                                                          signatories,
                                                          observers,
                                                          history_id,
                                                          migration_id,
                                                          created_at,
                                                          creates.contract_id,
                                                          create_arguments
                                                   from contracts_to_insert contracts
                                                            join update_history_creates creates
                                                            on contracts.contract_id = creates.contract_id),
                inserted_rows as (insert into acs_snapshot_data (create_id, template_id, stakeholder)
                                  select row_id,
                                         concat(package_name, ':', template_id_module_name, ':', template_id_entity_name),
                                         stakeholder
                                  from creates_to_insert
                                           cross join unnest(array_cat(signatories, observers)) as stakeholders(stakeholder)
                                  where history_id = $historyId
                                    and migration_id = $migrationId
                                  -- consistent ordering across SVs
                                  order by created_at, contract_id
                                  returning row_id, create_id, template_id, stakeholder
                )
        insert
        into acs_snapshot (snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance)
        select
          $until,
          $migrationId,
          $historyId,
          min(inserted_rows.row_id),
          max(inserted_rows.row_id),
          -- the stakeholder filter ensures that we don't double-count amulet amounts
          sum(case when inserted_rows.template_id = $amuletQualifiedName and stakeholder=$dsoParty then (create_arguments->'record'->'fields'->2->'value'->'record'->'fields'->0->'value'->>'numeric')::numeric else 0 end),
          sum(case when inserted_rows.template_id = $lockedAmuletQualifiedName and stakeholder=$dsoParty then (create_arguments->'record'->'fields'->0->'value'->'record'->'fields'->2->'value'->'record'->'fields'->0->'value'->>'numeric')::numeric else 0 end)
        from inserted_rows
        join creates_to_insert on inserted_rows.create_id = creates_to_insert.row_id
        having min(inserted_rows.row_id) is not null;
             """).toActionBuilder.asUpdate
      storage.queryAndUpdate(withExclusiveSnapshotDataLock(statement), "insertNewSnapshot")
    }.andThen { _ =>
      AcsSnapshotStore.PreventConcurrentSnapshotsSemaphore.release()
    }
  }

  /** Wraps the given action in a transaction that holds an exclusive lock on the acs_snapshot_data table.
    *
    *  Note: The acs_snapshot_data table must not have interleaved rows from two different acs snapshots.
    *  In rare cases, it can happen that the application crashes while writing a snapshot, then
    *  restarts and starts writing a different snapshot while the previous statement is still running.
    *
    *  The exclusive lock prevents this.
    *  We use a transaction-scoped advisory lock, which is released when the transaction ends.
    *  Regular locks (e.g. obtained via `LOCK TABLE ... IN EXCLUSIVE MODE`) would conflict with harmless
    *  background operations like autovacuum or create index concurrently.
    *
    *  In case the application crashes while holding the lock, the server _should_ close the connection
    *  and abort the transaction as soon as it detects a disconnect.
    *  TODO(#2488): Verify that the server indeed closes connections in a reasonable time.
    */
  private def withExclusiveSnapshotDataLock[T, E <: Effect](
      action: DBIOAction[T, NoStream, E]
  ): DBIOAction[T, NoStream, Effect.Read & Effect.Transactional & E] =
    (for {
      lockResult <- sql"SELECT pg_try_advisory_xact_lock(${AdvisoryLockIds.acsSnapshotDataInsert})"
        .as[Boolean]
        .head
      result <- lockResult match {
        case true => action
        // Lock conflicts should almost never happen. If they do, we fail immediately and rely on the trigger infrastructure to retry and log errors.
        case false => DBIOAction.failed(new Exception("Failed to acquire exclusive lock"))
      }
    } yield result).transactionally

  def deleteSnapshot(
      snapshot: AcsSnapshot
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val statement = DBIOAction.seq(
      sqlu"""delete from acs_snapshot where snapshot_record_time = ${snapshot.snapshotRecordTime}""",
      sqlu"""delete from acs_snapshot_data where row_id between ${snapshot.firstRowId} and ${snapshot.lastRowId}""",
    )
    storage.update(statement.transactionally, "deleteSnapshot")
  }

  def queryAcsSnapshot(
      migrationId: Long,
      snapshot: CantonTimestamp,
      after: Option[Long],
      limit: Limit,
      partyIds: Seq[PartyId],
      templates: Seq[PackageQualifiedName],
  )(implicit tc: TraceContext): Future[QueryAcsSnapshotResult] = {
    for {
      snapshot <- storage
        .querySingle(
          sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id, unlocked_amulet_balance, locked_amulet_balance
            from acs_snapshot
            where snapshot_record_time = $snapshot
              and migration_id = $migrationId
              and history_id = $historyId
            limit 1""".as[AcsSnapshot].headOption,
          "queryAcsSnapshot.getSnapshot",
        )
        .getOrElseF(
          FutureUnlessShutdown.failed(
            io.grpc.Status.NOT_FOUND
              .withDescription(
                s"Failed to find ACS snapshot for migration id $migrationId at $snapshot"
              )
              .asRuntimeException()
          )
        )
      begin <- after match {
        case Some(value) if value < snapshot.firstRowId || value > snapshot.lastRowId =>
          Future.failed(
            io.grpc.Status.INVALID_ARGUMENT
              .withDescription(
                s"Invalid after token, outside of snapshot range (${snapshot.firstRowId} to ${snapshot.lastRowId})."
              )
              .asRuntimeException()
          )
        case Some(value) => Future.successful(value + 1)
        case None => Future.successful(snapshot.firstRowId)
      }
      end = snapshot.lastRowId
      partyIdsFilter = partyIds match {
        case Nil => sql""
        case partyIds =>
          (sql" and " ++ inClause("stakeholder", partyIds)).toActionBuilder
      }
      templatesFilter = templates match {
        case Nil => sql""
        case _ =>
          (sql" and " ++ inClause(
            "template_id",
            templates.map(t =>
              lengthLimited(
                s"${t.packageName}:${t.qualifiedName.moduleName}:${t.qualifiedName.entityName}"
              )
            ),
          )).toActionBuilder
      }
      events <- storage
        .query(
          (sql"""
               with snapshot as (
                  select create_id, max(row_id) as row_id
                  from acs_snapshot_data
                  where row_id between $begin and $end
               """ ++ partyIdsFilter ++ templatesFilter ++ sql"""
                  group by create_id
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
                 contract_key
              from snapshot
              join update_history_creates creates on creates.row_id = snapshot.create_id
              order by snapshot.row_id
            """).toActionBuilder
            .as[(Long, SelectFromCreateEvents)],
          "queryAcsSnapshot.getCreatedEvents",
        )
    } yield {
      val eventsInPage =
        applyLimitOrFail("queryAcsSnapshot", limit, events.map(_._2.toCreatedEvent))
      val afterToken = if (eventsInPage.size == limit.limit) events.lastOption.map(_._1) else None
      QueryAcsSnapshotResult(
        migrationId = migrationId,
        snapshotRecordTime = snapshot.snapshotRecordTime,
        createdEventsInPage = eventsInPage,
        afterToken = afterToken,
      )
    }
  }

  def getHoldingsState(
      migrationId: Long,
      snapshot: CantonTimestamp,
      after: Option[Long],
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

}

object AcsSnapshotStore {

  // Only relevant for tests, in production this is already guaranteed.
  private val PreventConcurrentSnapshotsSemaphore = new Semaphore(1)

  case class AcsSnapshot(
      snapshotRecordTime: CantonTimestamp,
      migrationId: Long,
      historyId: Long,
      firstRowId: Long,
      lastRowId: Long,
      unlockedAmuletBalance: Option[BigDecimal],
      lockedAmuletBalance: Option[BigDecimal],
  ) extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotRecordTime", _.snapshotRecordTime),
      param("migrationId", _.migrationId),
      param("historyId", _.historyId),
      param("firstRowId", _.firstRowId),
      param("lastRowId", _.lastRowId),
      param("unlockedAmuletBalance", _.unlockedAmuletBalance),
      param("lockedAmuletBalance", _.lockedAmuletBalance),
    )
  }

  object AcsSnapshot {
    implicit val acsSnapshotGetResult: GetResult[AcsSnapshot] = GetResult(r =>
      AcsSnapshot(
        snapshotRecordTime = r.<<[CantonTimestamp],
        migrationId = r.<<[Long],
        historyId = r.<<[Long],
        firstRowId = r.<<[Long],
        lastRowId = r.<<[Long],
        unlockedAmuletBalance = r.<<[Option[BigDecimal]],
        lockedAmuletBalance = r.<<[Option[BigDecimal]],
      )
    )
  }

  case class QueryAcsSnapshotResult(
      migrationId: Long,
      snapshotRecordTime: CantonTimestamp,
      createdEventsInPage: Vector[SpliceCreatedEvent],
      afterToken: Option[Long],
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

  def apply(
      storage: DbStorage,
      updateHistory: UpdateHistory,
      dsoParty: PartyId,
      migrationId: Long,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, closeContext: CloseContext): AcsSnapshotStore =
    new AcsSnapshotStore(storage, updateHistory, dsoParty, migrationId, loggerFactory)

}
