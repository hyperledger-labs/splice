// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.network.scan.store.AcsSnapshotStore.{AcsSnapshot, QueryAcsSnapshotResult}
import com.daml.network.store.UpdateHistory.SelectFromCreateEvents
import com.daml.network.store.{PageLimit, UpdateHistory}
import com.daml.network.store.db.{AcsJdbcTypes, AcsQueries}
import com.daml.network.util.PackageQualifiedName
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.{GetResult, JdbcProfile}

import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    val migrationId: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, closeContext: CloseContext)
    extends AcsJdbcTypes
    with AcsQueries
    with NamedLogging {

  override val profile: JdbcProfile = storage.profile.jdbc

  private def historyId = updateHistory.historyId

  def lookupSnapshotBefore(
      migrationId: Long,
      before: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Option[AcsSnapshot]] = {
    storage
      .querySingle(
        sql"""select snapshot_record_time, migration_id, first_row_id, last_row_id
            from acs_snapshot
            where snapshot_record_time <= $before and migration_id = $migrationId
            order by snapshot_record_time desc
            limit 1""".as[AcsSnapshot].headOption,
        "lookupSnapshotBefore",
      )
      .value
  }

  def insertNewSnapshot(
      lastSnapshot: Option[AcsSnapshot],
      until: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[Int] = {
    val from = lastSnapshot.map(_.snapshotRecordTime).getOrElse(CantonTimestamp.Epoch)
    val previousSnapshotDataFiler = lastSnapshot match {
      case Some(AcsSnapshot(_, _, firstRowId, lastRowId)) =>
        sql"where snapshot.row_id >= $firstRowId and snapshot.row_id <= $lastRowId"
      case None =>
        sql"where false"
    }
    storage.update(
      (sql"""
        with inserted_rows as (
            with previous_snapshot_data as (select contract_id
                                            from acs_snapshot_data snapshot
                                                     join update_history_creates creates on snapshot.create_id = creates.row_id
                                            """ ++ previousSnapshotDataFiler ++ sql"""),
                transactions_in_snapshot as not materialized ( -- materialized yields a worse plan
                    select row_id
                    from update_history_transactions txs
                    where txs.history_id = $historyId
                      and txs.migration_id = $migrationId
                      and txs.record_time >= $from -- this will be >= unix_epoch for the first snapshot, which includes ACS imports
                      and txs.record_time < $until
                    ),
                new_creates as (select contract_id
                                from transactions_in_snapshot txs
                                         join update_history_creates creates on creates.update_row_id = txs.row_id
                    ),
                archives as (select contract_id
                             from transactions_in_snapshot txs
                                      join update_history_exercises archives on archives.update_row_id = txs.row_id
                                 and consuming),
                contracts_to_insert as (select contract_id
                                        from previous_snapshot_data
                                        union
                                        select contract_id
                                        from new_creates
                                        except
                                        select contract_id
                                        from archives)
                insert
                    into acs_snapshot_data (create_id, template_id, stakeholder)
                        select
                           creates.row_id,
                           concat(package_name, ':', template_id_module_name, ':', template_id_entity_name),
                           stakeholder
                        from contracts_to_insert contracts
                           join update_history_creates creates
                                on contracts.contract_id = creates.contract_id
                           join update_history_transactions txs on txs.row_id = creates.update_row_id
                           cross join unnest(array_cat(signatories, observers)) as stakeholders(stakeholder)
                        where txs.history_id = $historyId
                          and txs.migration_id = $migrationId
                        -- consistent ordering across SVs
                        order by creates.created_at, creates.contract_id
                        returning row_id
        )
        insert
        into acs_snapshot (snapshot_record_time, migration_id, first_row_id, last_row_id)
        select $until, $migrationId, min(row_id), max(row_id)
        from inserted_rows
        having min(row_id) is not null;
             """).toActionBuilder.asUpdate,
      "insertNewSnapshot",
    )
  }

  def queryAcsSnapshot(
      migrationId: Long,
      snapshot: CantonTimestamp,
      after: Option[Long],
      limit: PageLimit,
      partyIds: Seq[PartyId],
      templates: Seq[PackageQualifiedName],
  )(implicit tc: TraceContext): Future[QueryAcsSnapshotResult] = {
    for {
      snapshot <- storage
        .querySingle(
          sql"""select snapshot_record_time, migration_id, first_row_id, last_row_id
            from acs_snapshot
            where snapshot_record_time = $snapshot and migration_id = $migrationId
            limit 1""".as[AcsSnapshot].headOption,
          "queryAcsSnapshot.getSnapshot",
        )
        .getOrElseF(
          Future.failed(
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
              from acs_snapshot_data snapshot
              join update_history_creates creates on creates.row_id = snapshot.create_id
              where snapshot.row_id between $begin and $end
            """
            ++ partyIdsFilter
            ++ templatesFilter
            ++ sql" order by snapshot.row_id limit ${limit.limit}").toActionBuilder
            .as[(Long, SelectFromCreateEvents)],
          "queryAcsSnapshot.getCreatedEvents",
        )
    } yield QueryAcsSnapshotResult(
      migrationId = migrationId,
      snapshotRecordTime = snapshot.snapshotRecordTime,
      createdEventsInPage = events.map(_._2.toCreatedEvent),
      afterToken = events.lastOption.map(_._1),
    )
  }

}

object AcsSnapshotStore {

  case class AcsSnapshot(
      snapshotRecordTime: CantonTimestamp,
      migrationId: Long,
      firstRowId: Long,
      lastRowId: Long,
  ) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("snapshotRecordTime", _.snapshotRecordTime),
      param("migrationId", _.migrationId),
      param("firstRowId", _.firstRowId),
      param("lastRowId", _.lastRowId),
    )
  }

  object AcsSnapshot {
    implicit val acsSnapshotGetResult: GetResult[AcsSnapshot] = GetResult(r =>
      AcsSnapshot(
        snapshotRecordTime = r.<<[CantonTimestamp],
        migrationId = r.<<[Long],
        firstRowId = r.<<[Long],
        lastRowId = r.<<[Long],
      )
    )
  }

  case class QueryAcsSnapshotResult(
      migrationId: Long,
      snapshotRecordTime: CantonTimestamp,
      createdEventsInPage: Vector[CreatedEvent],
      afterToken: Option[Long],
  )

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
