// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import cats.data.NonEmptyVector
import com.daml.ledger.javaapi.data.CreatedEvent
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, LockedAmulet}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  QueryAcsSnapshotResult,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.SelectFromCreateEvents
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, LimitHelpers, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsQueries}
import org.lfdecentralizedtrust.splice.util.{Contract, HoldingsSummary, PackageQualifiedName}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import slick.dbio.DBIOAction
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.{GetResult, JdbcProfile}

import java.util.concurrent.Semaphore
import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotStore(
    storage: DbStorage,
    val updateHistory: UpdateHistory,
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

  def lookupSnapshotBefore(
      migrationId: Long,
      before: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Option[AcsSnapshot]] = {
    storage
      .querySingle(
        sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id
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
      val previousSnapshotDataFilter = lastSnapshot match {
        case Some(AcsSnapshot(_, _, _, firstRowId, lastRowId)) =>
          sql"where snapshot.row_id >= $firstRowId and snapshot.row_id <= $lastRowId"
        case None =>
          sql"where false"
      }
      def recordTimeFilter(tableAlias: String) =
        sql"""
          where #$tableAlias.history_id = $historyId
            and #$tableAlias.migration_id = $migrationId
            and #$tableAlias.record_time >= $from -- this will be >= MinValue for the first snapshot, which includes ACS imports
            and #$tableAlias.record_time < $until
           """
      val statement = (sql"""
        with inserted_rows as (
            with previous_snapshot_data as (select contract_id
                                            from acs_snapshot_data snapshot
                                                     join update_history_creates creates on snapshot.create_id = creates.row_id
                                            """ ++ previousSnapshotDataFilter ++
        sql"""),
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
                                                          creates.contract_id
                                                   from contracts_to_insert contracts
                                                            join update_history_creates creates
                                                            on contracts.contract_id = creates.contract_id)

                insert into acs_snapshot_data (create_id, template_id, stakeholder)
                select row_id,
                       concat(package_name, ':', template_id_module_name, ':', template_id_entity_name),
                       stakeholder
                from creates_to_insert
                         cross join unnest(array_cat(signatories, observers)) as stakeholders(stakeholder)
                where history_id = $historyId
                  and migration_id = $migrationId
                -- consistent ordering across SVs
                order by created_at, contract_id
                returning row_id
        )
        insert
        into acs_snapshot (snapshot_record_time, migration_id, history_id, first_row_id, last_row_id)
        select $until, $migrationId, $historyId, min(row_id), max(row_id)
        from inserted_rows
        having min(row_id) is not null;
             """).toActionBuilder.asUpdate
      storage.update(statement, "insertNewSnapshot")
    }.andThen { _ =>
      AcsSnapshotStore.PreventConcurrentSnapshotsSemaphore.release()
    }
  }

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
          sql"""select snapshot_record_time, migration_id, history_id, first_row_id, last_row_id
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
          (sql" and stakeholder in " ++ inClause(partyIds)).toActionBuilder
      }
      templatesFilter = templates match {
        case Nil => sql""
        case _ =>
          (sql" and template_id in " ++ inClause(
            templates.map(t =>
              lengthLimited(
                s"${t.packageName}:${t.qualifiedName.moduleName}:${t.qualifiedName.entityName}"
              )
            )
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
              order by snapshot.row_id limit ${sqlLimit(limit)}
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
        HardLimit.tryCreate(Limit.MaxPageSize),
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
  ) extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotRecordTime", _.snapshotRecordTime),
      param("migrationId", _.migrationId),
      param("historyId", _.historyId),
      param("firstRowId", _.firstRowId),
      param("lastRowId", _.lastRowId),
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
      )
    )
  }

  case class QueryAcsSnapshotResult(
      migrationId: Long,
      snapshotRecordTime: CantonTimestamp,
      createdEventsInPage: Vector[SpliceCreatedEvent],
      afterToken: Option[Long],
  )

  private val holdingsTemplates =
    Vector(Amulet.TEMPLATE_ID_WITH_PACKAGE_ID, LockedAmulet.TEMPLATE_ID_WITH_PACKAGE_ID).map(
      PackageQualifiedName(_)
    )

  private def decodeHoldingContract(createdEvent: CreatedEvent): Either[
    Contract[LockedAmulet.ContractId, LockedAmulet],
    Contract[Amulet.ContractId, Amulet],
  ] = {
    def failedToDecode = throw io.grpc.Status.FAILED_PRECONDITION
      .withDescription(s"Failed to decode $createdEvent")
      .asRuntimeException()
    if (
      PackageQualifiedName(createdEvent.getTemplateId) == PackageQualifiedName(
        Amulet.TEMPLATE_ID_WITH_PACKAGE_ID
      )
    ) {
      Right(Contract.fromCreatedEvent(Amulet.COMPANION)(createdEvent).getOrElse(failedToDecode))
    } else {
      if (
        PackageQualifiedName(createdEvent.getTemplateId) != PackageQualifiedName(
          LockedAmulet.TEMPLATE_ID_WITH_PACKAGE_ID
        )
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
      storage: Storage,
      updateHistory: UpdateHistory,
      migrationId: Long,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, closeContext: CloseContext): AcsSnapshotStore =
    storage match {
      case db: DbStorage => new AcsSnapshotStore(db, updateHistory, migrationId, loggerFactory)
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }

}
