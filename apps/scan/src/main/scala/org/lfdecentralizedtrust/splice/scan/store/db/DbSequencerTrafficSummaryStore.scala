// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.PostgresProfile
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId
import cats.data.NonEmptyList
import slick.jdbc.canton.SQLActionBuilder
import io.circe.Json
import io.circe.syntax.*

object DbSequencerTrafficSummaryStore {

  /** Represents an envelope within a traffic summary */
  final case class EnvelopeT(
      trafficCost: Long,
      viewHashes: Seq[String],
  )

  object EnvelopeT {
    def toJson(envelopes: Seq[EnvelopeT]): Json = Json.arr(
      envelopes.map { env =>
        Json.obj(
          "traffic_cost" -> env.trafficCost.asJson,
          "view_hashes" -> env.viewHashes.asJson,
        )
      }*
    )

    def fromJson(json: Json): Seq[EnvelopeT] = {
      json.asArray.getOrElse(Vector.empty).flatMap { obj =>
        for {
          trafficCost <- obj.hcursor.get[Long]("traffic_cost").toOption
          viewHashes <- obj.hcursor.get[Seq[String]]("view_hashes").toOption
        } yield EnvelopeT(trafficCost, viewHashes)
      }
    }
  }

  /** Traffic summary (used for both inserts and reads) */
  final case class TrafficSummaryT(
      rowId: Long,
      migrationId: Long,
      sequencingTime: CantonTimestamp,
      totalTrafficCost: Long,
      envelopes: Seq[EnvelopeT],
  )

  def apply(
      storage: DbStorage,
      party: PartyId,
      participantId: ParticipantId,
      synchronizerId: SynchronizerId,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      closeContext: CloseContext,
  ): Future[DbSequencerTrafficSummaryStore] = {
    // Use store_name to make history_id per-synchronizer
    val storeName = s"sequencer-traffic-${synchronizerId.toProtoPrimitive}"
    getOrCreateHistoryId(storage, party, participantId, storeName).map { historyId =>
      new DbSequencerTrafficSummaryStore(storage, historyId, synchronizerId, loggerFactory)
    }
  }

  private def getOrCreateHistoryId(
      storage: DbStorage,
      party: PartyId,
      participantId: ParticipantId,
      storeName: String,
  )(implicit ec: ExecutionContext, tc: TraceContext, closeContext: CloseContext): Future[Long] = {
    storage.queryAndUpdate(
      for {
        _ <- sql"""
          insert into update_history_descriptors (party, participant_id, store_name)
          values ($party, $participantId, $storeName)
          on conflict do nothing
        """.asUpdate
        idOpt <- sql"""
          select id from update_history_descriptors
          where party = $party and participant_id = $participantId and store_name = $storeName
        """.as[Long].headOption
      } yield idOpt.getOrElse(
        throw new RuntimeException(s"Failed to get or create history_id for store $storeName")
      ),
      "getOrCreateHistoryId",
    )
  }
}

class DbSequencerTrafficSummaryStore(
    storage: DbStorage,
    historyId: Long,
    val synchronizerId: SynchronizerId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries { self =>

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  private val lastIngestedSequencingTimeRef =
    new AtomicReference[Option[CantonTimestamp]](None)

  def lastIngestedSequencingTime: Option[CantonTimestamp] = lastIngestedSequencingTimeRef.get()

  private def advanceLastIngestedSequencingTime(ts: CantonTimestamp): Unit = {
    val _ = lastIngestedSequencingTimeRef.updateAndGet { curr =>
      curr match {
        case Some(c) if ts < c => curr
        case _ => Some(ts)
      }
    }
  }

  object Tables {
    val trafficSummaries = "sequencer_traffic_summary_store"
  }

  type TrafficSummaryT = DbSequencerTrafficSummaryStore.TrafficSummaryT
  val TrafficSummaryT = DbSequencerTrafficSummaryStore.TrafficSummaryT
  type EnvelopeT = DbSequencerTrafficSummaryStore.EnvelopeT
  val EnvelopeT = DbSequencerTrafficSummaryStore.EnvelopeT

  private implicit val GetResultTrafficSummaryRow: GetResult[TrafficSummaryT] = GetResult { prs =>
    import prs.*
    val rowId = <<[Long]
    val migrationId = <<[Long]
    val sequencingTime = <<[CantonTimestamp]
    val totalTrafficCost = <<[Long]
    val envelopesJson = <<[Json]
    val envelopes = EnvelopeT.fromJson(envelopesJson)
    TrafficSummaryT(rowId, migrationId, sequencingTime, totalTrafficCost, envelopes)
  }

  /** Batch insert traffic summaries using multi-row INSERT. */
  private def batchInsertTrafficSummaries(items: Seq[TrafficSummaryT]) = {
    if (items.isEmpty) {
      slick.dbio.DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          val envelopesJson = EnvelopeT.toJson(row.envelopes)
          sql"""($historyId, ${row.migrationId}, ${row.sequencingTime},
                ${row.totalTrafficCost}, $envelopesJson)"""
        }
      )

      (sql"""
        insert into #${Tables.trafficSummaries}(
          history_id, migration_id, sequencing_time, total_traffic_cost, envelopes
        ) values """ ++ values).asUpdate
    }
  }

  /** Insert multiple traffic summaries and their envelopes in a single transaction.
    *
    * We check for existing sequencing_times first, then insert only non-existing items.
    * The unique index on (history_id, sequencing_time) serves as a safety net.
    */
  def insertTrafficSummaries(
      items: Seq[TrafficSummaryT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import slick.dbio.DBIO
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      val checkExist = (sql"""
        select sequencing_time
        from #${Tables.trafficSummaries}
        where history_id = $historyId
          and """ ++ inClause("sequencing_time", items.map(_.sequencingTime.toMicros)))
        .as[Long]

      val action: DBIO[Unit] = for {
        alreadyExisting <- checkExist.map(_.toSet)
        nonExisting = items.filter(item => !alreadyExisting.contains(item.sequencingTime.toMicros))
        _ = logger.info(
          s"Already ingested traffic summaries: ${alreadyExisting.size}. Non-existing: ${nonExisting.size}."
        )
        _ <-
          if (nonExisting.nonEmpty) {
            batchInsertTrafficSummaries(nonExisting).map { _ =>
              logger.info(s"Inserted ${nonExisting.size} traffic summaries.")
            }
          } else {
            DBIO.successful(())
          }
      } yield ()

      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            action.transactionally,
            "scanTraffic.insertTrafficSummaries.batch",
          )
      ).map { _ =>
        val maxTs = items.map(_.sequencingTime).maxOption
        maxTs.foreach(advanceLastIngestedSequencingTime)
      }
    }
  }

  def listTrafficSummaries(
      afterO: Option[TimestampWithMigrationId],
      limit: Int,
  )(implicit tc: TraceContext): Future[Seq[TrafficSummaryT]] = {
    val filters = afterFilters(afterO)
    val query = trafficSummariesQuery(filters, limit)
    storage.query(query.toActionBuilder.as[TrafficSummaryT], "scanTraffic.listTrafficSummaries")
  }

  private def afterFilters(
      afterO: Option[TimestampWithMigrationId]
  ): NonEmptyList[SQLActionBuilder] = {
    afterO match {
      case None =>
        NonEmptyList.of(sql"migration_id >= 0 and sequencing_time > ${CantonTimestamp.MinValue}")
      case Some(TimestampWithMigrationId(afterSequencingTime, afterMigrationId)) =>
        // Split into two queries for better index utilization (avoids OR causing seq scan)
        NonEmptyList.of(
          sql"migration_id = $afterMigrationId and sequencing_time > $afterSequencingTime",
          sql"migration_id > $afterMigrationId and sequencing_time > ${CantonTimestamp.MinValue}",
        )
    }
  }

  private def trafficSummariesQuery(
      filters: NonEmptyList[SQLActionBuilder],
      limit: Int,
  ) = {
    def makeSubQuery(afterFilter: SQLActionBuilder) = {
      sql"""
      (select
        row_id,
        migration_id,
        sequencing_time,
        total_traffic_cost,
        envelopes
      from #${Tables.trafficSummaries}
      where history_id = $historyId and """ ++ afterFilter ++
        sql" order by migration_id, sequencing_time limit $limit)"
    }

    if (filters.size == 1) makeSubQuery(filters.head)
    else {
      // Using an OR in a query might cause the query planner to do a Seq scan,
      // whereas using a union all makes it so that the individual queries use the right index,
      // and are merged via Merge Append.
      val unionAll = filters.map(makeSubQuery).reduceLeft(_ ++ sql" union all " ++ _)
      sql"select * from (" ++ unionAll ++ sql") all_queries " ++
        sql"order by migration_id, sequencing_time limit $limit"
    }
  }

  def maxSequencingTime(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    storage
      .query(
        sql"""
          select max(sequencing_time)
          from   #${Tables.trafficSummaries}
          where  history_id = $historyId
          and    migration_id = $migrationId
        """.toActionBuilder
          .as[Option[CantonTimestamp]],
        "scanTraffic.maxSequencingTime",
      )
      .map(_.headOption.flatten)
  }
}
