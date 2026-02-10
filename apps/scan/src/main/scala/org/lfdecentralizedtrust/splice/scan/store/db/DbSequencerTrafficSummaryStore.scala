// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.PostgresProfile
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.store.{TimestampWithMigrationId, UpdateHistory}
import cats.data.NonEmptyList
import slick.jdbc.canton.SQLActionBuilder

object DbSequencerTrafficSummaryStore {

  /** Represents an envelope within a traffic summary (in-memory representation for inserts) */
  final case class EnvelopeT(
      trafficCost: Long,
      viewHashes: Seq[String],
  )

  /** Represents an envelope row in the database */
  final case class EnvelopeRowT(
      trafficSummaryRowId: Long,
      envelopeIndex: Int,
      trafficCost: Long,
      viewHashes: Seq[String],
  )

  /** Traffic summary for inserts (includes envelopes) */
  final case class TrafficSummaryT(
      rowId: Long,
      migrationId: Long,
      domainId: SynchronizerId,
      sequencingTime: CantonTimestamp,
      sender: String,
      totalTrafficCost: Long,
      envelopes: Seq[EnvelopeT],
  )

  /** Traffic summary row from database (envelopes fetched separately via listEnvelopes) */
  final case class TrafficSummaryRowT(
      rowId: Long,
      migrationId: Long,
      domainId: SynchronizerId,
      sequencingTime: CantonTimestamp,
      sender: String,
      totalTrafficCost: Long,
  )

  def apply(
      storage: DbStorage,
      updateHistory: UpdateHistory,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): DbSequencerTrafficSummaryStore =
    new DbSequencerTrafficSummaryStore(storage, updateHistory, loggerFactory)
}

class DbSequencerTrafficSummaryStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries { self =>

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  private def historyId = updateHistory.historyId

  def waitUntilInitialized: Future[Unit] = updateHistory.waitUntilInitialized

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
    val envelopes = "sequencer_traffic_envelope_store"
  }

  type TrafficSummaryT = DbSequencerTrafficSummaryStore.TrafficSummaryT
  val TrafficSummaryT = DbSequencerTrafficSummaryStore.TrafficSummaryT
  type TrafficSummaryRowT = DbSequencerTrafficSummaryStore.TrafficSummaryRowT
  val TrafficSummaryRowT = DbSequencerTrafficSummaryStore.TrafficSummaryRowT
  type EnvelopeT = DbSequencerTrafficSummaryStore.EnvelopeT
  val EnvelopeT = DbSequencerTrafficSummaryStore.EnvelopeT
  type EnvelopeRowT = DbSequencerTrafficSummaryStore.EnvelopeRowT
  val EnvelopeRowT = DbSequencerTrafficSummaryStore.EnvelopeRowT

  private implicit val GetResultTrafficSummaryRow: GetResult[TrafficSummaryRowT] = GetResult {
    prs =>
      import prs.*
      TrafficSummaryRowT(
        <<[Long], // row_id
        <<[Long], // migration_id
        <<[SynchronizerId], // domain_id
        <<[CantonTimestamp], // sequencing_time
        <<[String], // sender
        <<[Long], // total_traffic_cost
      )
  }

  private implicit val GetResultEnvelopeRow: GetResult[EnvelopeRowT] = GetResult { prs =>
    import prs.*
    EnvelopeRowT(
      <<[Long], // traffic_summary_row_id
      <<[Int], // envelope_index
      <<[Long], // traffic_cost
      stringArrayGetResult(prs).toSeq, // view_hashes
    )
  }

  private def sqlInsertTrafficSummaryReturningId(row: TrafficSummaryT) = {
    sql"""
      insert into #${Tables.trafficSummaries}(
        history_id,
        migration_id,
        domain_id,
        sequencing_time,
        sender,
        total_traffic_cost
      ) values (
        $historyId,
        ${row.migrationId},
        ${row.domainId},
        ${row.sequencingTime},
        ${row.sender},
        ${row.totalTrafficCost}
      ) returning row_id
    """.as[Long].headOption
  }

  private def sqlInsertEnvelope(row: EnvelopeRowT) = {
    sql"""
      insert into #${Tables.envelopes}(
        traffic_summary_row_id,
        envelope_index,
        traffic_cost,
        view_hashes
      ) values (
        ${row.trafficSummaryRowId},
        ${row.envelopeIndex},
        ${row.trafficCost},
        ${row.viewHashes.map(lengthLimited).toSeq}
      )
    """.asUpdate
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
            DBIO
              .sequence(nonExisting.map { summary =>
                for {
                  idOpt <- sqlInsertTrafficSummaryReturningId(summary)
                  rowId <- idOpt match {
                    case Some(id) => DBIO.successful(id)
                    case None =>
                      DBIO.failed(
                        new RuntimeException("insertTrafficSummary did not return row_id")
                      )
                  }
                  envelopeRows = summary.envelopes.zipWithIndex.map { case (env, idx) =>
                    EnvelopeRowT(rowId, idx, env.trafficCost, env.viewHashes)
                  }
                  _ <- DBIO.sequence(envelopeRows.map(sqlInsertEnvelope)).map(_ => ())
                } yield ()
              })
              .map { _ =>
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
  )(implicit tc: TraceContext): Future[Seq[TrafficSummaryRowT]] = {
    val filters = afterFilters(afterO)
    val query = trafficSummariesQuery(filters, limit)
    storage.query(query.toActionBuilder.as[TrafficSummaryRowT], "scanTraffic.listTrafficSummaries")
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
        domain_id,
        sequencing_time,
        sender,
        total_traffic_cost
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

  def listEnvelopes(trafficSummaryRowId: Long)(implicit
      tc: TraceContext
  ): Future[Seq[EnvelopeRowT]] = {
    storage.query(
      sql"""
        select
          traffic_summary_row_id,
          envelope_index,
          traffic_cost,
          view_hashes
        from #${Tables.envelopes}
        where traffic_summary_row_id = $trafficSummaryRowId
        order by envelope_index asc
      """.as[EnvelopeRowT],
      "scanTraffic.listEnvelopes",
    )
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
