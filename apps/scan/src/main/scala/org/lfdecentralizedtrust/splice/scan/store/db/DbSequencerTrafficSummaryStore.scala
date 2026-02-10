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
import io.circe.Json
import io.circe.syntax.*

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.store.{TimestampWithMigrationId, UpdateHistory}

object DbSequencerTrafficSummaryStore {

  /** Represents an envelope within a traffic summary */
  final case class EnvelopeT(
      trafficCost: Long,
      viewHashes: Seq[String],
  )

  object EnvelopeT {

    /** Encode view hashes as JSON for database storage */
    def encodeViewHashes(envelopes: Seq[Seq[String]]): Json =
      envelopes.asJson

    /** Decode view hashes from JSON */
    def decodeViewHashes(json: Json): Seq[Seq[String]] =
      json.as[Seq[Seq[String]]].getOrElse(Seq.empty)
  }

  final case class TrafficSummaryT(
      rowId: Long,
      migrationId: Long,
      domainId: SynchronizerId,
      sequencingTime: CantonTimestamp,
      sender: String,
      totalTrafficCost: Long,
      envelopes: Seq[EnvelopeT],
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
  }

  type TrafficSummaryT = DbSequencerTrafficSummaryStore.TrafficSummaryT
  val TrafficSummaryT = DbSequencerTrafficSummaryStore.TrafficSummaryT
  type EnvelopeT = DbSequencerTrafficSummaryStore.EnvelopeT
  val EnvelopeT = DbSequencerTrafficSummaryStore.EnvelopeT

  /** Reconstruct envelopes from parallel arrays of traffic costs and view hashes JSON */
  private def parseEnvelopes(
      trafficCosts: Array[Long],
      viewHashesJson: Json,
  ): Seq[EnvelopeT] = {
    val viewHashes = EnvelopeT.decodeViewHashes(viewHashesJson)
    require(
      trafficCosts.length == viewHashes.length,
      s"Mismatched array lengths: trafficCosts=${trafficCosts.length}, viewHashes=${viewHashes.length}",
    )
    trafficCosts
      .zip(viewHashes)
      .map { case (cost, hashes) =>
        EnvelopeT(cost, hashes)
      }
      .toSeq
  }

  private implicit val GetResultTrafficSummaryRow: GetResult[TrafficSummaryT] = GetResult { prs =>
    import prs.*
    TrafficSummaryT(
      <<[Long], // row_id
      <<[Long], // migration_id
      <<[SynchronizerId], // domain_id
      <<[CantonTimestamp], // sequencing_time
      <<[String], // sender
      <<[Long], // total_traffic_cost
      parseEnvelopes(
        <<[Array[Long]],
        <<[Json],
      ), // envelope_traffic_costs, envelope_view_hashes
    )
  }

  private def sqlInsertTrafficSummary(row: TrafficSummaryT) = {
    val trafficCosts = row.envelopes.map(_.trafficCost)
    val viewHashesJson = EnvelopeT.encodeViewHashes(row.envelopes.map(_.viewHashes))
    sql"""
      insert into #${Tables.trafficSummaries}(
        history_id,
        migration_id,
        domain_id,
        sequencing_time,
        sender,
        total_traffic_cost,
        envelope_traffic_costs,
        envelope_view_hashes
      ) values (
        $historyId,
        ${row.migrationId},
        ${row.domainId},
        ${row.sequencingTime},
        ${row.sender},
        ${row.totalTrafficCost},
        $trafficCosts,
        $viewHashesJson
      )
    """.asUpdate
  }

  /** Insert multiple traffic summaries in a single transaction.
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
        _ <- if (nonExisting.nonEmpty) {
          DBIO
            .sequence(nonExisting.map(sqlInsertTrafficSummary))
            .map { counts =>
              val inserted = counts.sum
              logger.info(s"Inserted $inserted traffic summaries.")
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
    val (migrationFilter, timeFilter) = afterO match {
      case None =>
        (sql"migration_id >= 0", sql"sequencing_time > ${CantonTimestamp.MinValue}")
      case Some(TimestampWithMigrationId(afterSequencingTime, afterMigrationId)) =>
        (
          sql"migration_id >= $afterMigrationId",
          sql"(migration_id > $afterMigrationId or (migration_id = $afterMigrationId and sequencing_time > $afterSequencingTime))",
        )
    }

    val query = sql"""
      select
        row_id,
        migration_id,
        domain_id,
        sequencing_time,
        sender,
        total_traffic_cost,
        envelope_traffic_costs,
        envelope_view_hashes
      from #${Tables.trafficSummaries}
      where history_id = $historyId
        and """ ++ migrationFilter ++ sql" and " ++ timeFilter ++
      sql" order by migration_id, sequencing_time limit $limit"

    storage.query(query.toActionBuilder.as[TrafficSummaryT], "scanTraffic.listTrafficSummaries")
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
