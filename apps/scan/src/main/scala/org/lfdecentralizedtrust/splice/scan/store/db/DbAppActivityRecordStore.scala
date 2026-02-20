// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.PostgresProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.DBIO

import scala.concurrent.{ExecutionContext, Future}
import io.circe.Json
import io.circe.syntax.*

object DbAppActivityRecordStore {

  /** Represents a single app's activity within a record.
    *
    * @param partyId the featured app provider party
    * @param weight the traffic weight attributed to this app
    */
  final case class AppActivityT(
      partyId: String,
      weight: Long,
  )

  object AppActivityT {
    def toJson(activities: Seq[AppActivityT]): Json = Json.arr(
      activities.map { act =>
        Json.obj(
          "p" -> act.partyId.asJson,
          "w" -> act.weight.asJson,
        )
      }*
    )

    def fromJson(json: Json): Seq[AppActivityT] = {
      json.asArray.getOrElse(Vector.empty).flatMap { obj =>
        for {
          partyId <- obj.hcursor.get[String]("p").toOption
          weight <- obj.hcursor.get[Long]("w").toOption
        } yield AppActivityT(partyId, weight)
      }
    }
  }

  /** App activity record for a given record_time.
    *
    * @param migrationId migration identifier for domain migrations
    * @param recordTime the record_time (= sequencing_time) of the verdict/traffic summary
    * @param roundNumber the mining round that was open at this record_time
    * @param activities the featured app activities with their traffic weights
    */
  final case class AppActivityRecordT(
      migrationId: Long,
      recordTime: CantonTimestamp,
      roundNumber: Long,
      activities: Seq[AppActivityT],
  )
}

class DbAppActivityRecordStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    val synchronizerId: SynchronizerId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries { self =>

  private def historyId = updateHistory.historyId

  def waitUntilInitialized: Future[Unit] = updateHistory.waitUntilInitialized

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  object Tables {
    val appActivityRecords = "app_activity_record_store"
  }

  type AppActivityT = DbAppActivityRecordStore.AppActivityT
  val AppActivityT = DbAppActivityRecordStore.AppActivityT
  type AppActivityRecordT = DbAppActivityRecordStore.AppActivityRecordT
  val AppActivityRecordT = DbAppActivityRecordStore.AppActivityRecordT

  /** Batch insert app activity records using multi-row INSERT. */
  private def batchInsertAppActivityRecords(items: Seq[AppActivityRecordT]) = {
    if (items.isEmpty) {
      DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          val activitiesJson = AppActivityT.toJson(row.activities)
          sql"""($historyId, ${row.migrationId}, ${row.recordTime},
                ${row.roundNumber}, $activitiesJson)"""
        }
      )

      (sql"""
        insert into #${Tables.appActivityRecords}(
          history_id, migration_id, record_time, round_number, activities
        ) values """ ++ values).asUpdate
    }
  }

  /** Returns a DBIO action for inserting app activity records (for use in combined transactions).
    * Unlike insertAppActivityRecords, this doesn't wrap in a transaction or Future.
    */
  def insertAppActivityRecordsDBIO(
      items: Seq[AppActivityRecordT]
  )(implicit tc: TraceContext): DBIO[Unit] = {
    if (items.isEmpty) DBIO.successful(())
    else {
      val checkExist = (sql"""
        select record_time
        from #${Tables.appActivityRecords}
        where history_id = $historyId
          and """ ++ inClause("record_time", items.map(_.recordTime.toMicros)))
        .as[Long]

      for {
        alreadyExisting <- checkExist.map(_.toSet)
        nonExisting = items.filter(item => !alreadyExisting.contains(item.recordTime.toMicros))
        _ = logger.info(
          s"Already ingested app activity records: ${alreadyExisting.size}. Non-existing: ${nonExisting.size}."
        )
        _ <-
          if (nonExisting.nonEmpty) {
            batchInsertAppActivityRecords(nonExisting).map { _ =>
              logger.info(s"Inserted ${nonExisting.size} app activity records.")
            }
          } else {
            DBIO.successful(())
          }
      } yield ()
    }
  }

  /** Insert multiple app activity records in a single transaction.
    *
    * We check for existing record_times first, then insert only non-existing items.
    * The unique constraint on (history_id, record_time) serves as a safety net.
    */
  def insertAppActivityRecords(
      items: Seq[AppActivityRecordT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            insertAppActivityRecordsDBIO(items).transactionally,
            "appActivity.insertAppActivityRecords.batch",
          )
      )
    }
  }
}
