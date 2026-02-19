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
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.DBIO

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
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
          "tc" -> env.trafficCost.asJson,
          "vid" -> env.viewHashes.asJson,
        )
      }*
    )

    def fromJson(json: Json): Seq[EnvelopeT] = {
      json.asArray.getOrElse(Vector.empty).flatMap { obj =>
        for {
          trafficCost <- obj.hcursor.get[Long]("tc").toOption
          viewHashes <- obj.hcursor.get[Seq[String]]("vid").toOption
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

  /** Returns a DBIO action for inserting traffic summaries (for use in combined transactions).
    * Unlike insertTrafficSummaries, this doesn't wrap in a transaction or Future.
    */
  def insertTrafficSummariesDBIO(
      items: Seq[TrafficSummaryT]
  )(implicit tc: TraceContext): DBIO[Unit] = {
    if (items.isEmpty) DBIO.successful(())
    else {
      val checkExist = (sql"""
        select sequencing_time
        from #${Tables.trafficSummaries}
        where history_id = $historyId
          and """ ++ inClause("sequencing_time", items.map(_.sequencingTime.toMicros)))
        .as[Long]

      for {
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
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            insertTrafficSummariesDBIO(items).transactionally,
            "scanTraffic.insertTrafficSummaries.batch",
          )
      ).map { _ =>
        val maxTs = items.map(_.sequencingTime).maxOption
        maxTs.foreach(advanceLastIngestedSequencingTime)
      }
    }
  }
}
