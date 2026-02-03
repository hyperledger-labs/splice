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
import org.lfdecentralizedtrust.splice.store.UpdateHistory

object DbSequencerTrafficSummaryStore {

  /** Represents an envelope within a traffic summary */
  final case class EnvelopeT(
      trafficCost: Long,
      viewHashes: Seq[String],
  )

  /** Delimiter used to encode view hashes as a single string in the database.
    * Using pipe character as it's unlikely to appear in hash values.
    */
  private val ViewHashDelimiter = "|"

  object EnvelopeT {

    /** Encode view hashes as a pipe-delimited string for database storage */
    def encodeViewHashes(viewHashes: Seq[String]): String =
      viewHashes.mkString(ViewHashDelimiter)

    /** Decode view hashes from a pipe-delimited string */
    def decodeViewHashes(encoded: String): Seq[String] =
      if (encoded.isEmpty) Seq.empty
      else encoded.split(ViewHashDelimiter, -1).toSeq
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

  /** Reconstruct envelopes from parallel arrays of traffic costs and encoded view hashes */
  private def parseEnvelopes(
      trafficCosts: Array[Long],
      viewHashes: Array[String],
  ): Seq[EnvelopeT] = {
    require(
      trafficCosts.length == viewHashes.length,
      s"Mismatched array lengths: trafficCosts=${trafficCosts.length}, viewHashes=${viewHashes.length}",
    )
    trafficCosts
      .zip(viewHashes)
      .map { case (cost, encodedHashes) =>
        EnvelopeT(cost, EnvelopeT.decodeViewHashes(encodedHashes))
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
        <<[Array[String]],
      ), // envelope_traffic_costs, envelope_view_hashes
    )
  }

  private def sqlInsertTrafficSummary(row: TrafficSummaryT) = {
    val trafficCosts = row.envelopes.map(_.trafficCost)
    val viewHashes = row.envelopes.map(e => EnvelopeT.encodeViewHashes(e.viewHashes))
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
        $viewHashes
      )
      on conflict (history_id, sequencing_time) do nothing
    """.asUpdate
  }

  /** Insert multiple traffic summaries in a single transaction.
    *
    * We use INSERT ... ON CONFLICT DO NOTHING to handle duplicates.
    * Duplicates are identified by (history_id, sequencing_time).
    */
  def insertTrafficSummaries(
      items: Seq[TrafficSummaryT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import slick.dbio.DBIO
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      val action: DBIO[Unit] = DBIO
        .sequence(items.map(sqlInsertTrafficSummary))
        .map { counts =>
          val inserted = counts.sum
          logger.info(
            s"Inserted $inserted traffic summaries out of ${items.size} (duplicates skipped)."
          )
        }

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
      afterO: Option[(Long, CantonTimestamp)],
      limit: Int,
  )(implicit tc: TraceContext): Future[Seq[TrafficSummaryT]] = {
    val (migrationFilter, timeFilter) = afterO match {
      case None =>
        (sql"migration_id >= 0", sql"sequencing_time > ${CantonTimestamp.MinValue}")
      case Some((afterMigrationId, afterSequencingTime)) =>
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
