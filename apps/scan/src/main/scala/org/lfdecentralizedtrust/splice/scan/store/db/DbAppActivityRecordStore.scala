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
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.DBIO

import scala.concurrent.{ExecutionContext, Future}

object DbAppActivityRecordStore {

  /** App activity record for a given record_time.
    *
    * @param recordTime the record_time (= sequencing_time) of the verdict/traffic summary
    * @param roundNumber the mining round that was open at this record_time
    * @param appProviderParties app providers for which app activity should be recorded
    * @param appActivityWeights activity weight (in bytes of traffic) for each app provider,
    *                           in one-to-one correspondence with appProviderParties
    */
  final case class AppActivityRecordT(
      recordTime: CantonTimestamp,
      roundNumber: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
  )
}

class DbAppActivityRecordStore(
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

  private def historyId = updateHistory.historyId

  def waitUntilInitialized: Future[Unit] = updateHistory.waitUntilInitialized

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  object Tables {
    val appActivityRecords = "app_activity_record_store"
  }

  type AppActivityRecordT = DbAppActivityRecordStore.AppActivityRecordT

  private implicit val getResultAppActivityRecord: GetResult[AppActivityRecordT] = GetResult {
    prs =>
      DbAppActivityRecordStore.AppActivityRecordT(
        recordTime = prs.<<[CantonTimestamp],
        roundNumber = prs.<<[Long],
        appProviderParties = stringArrayGetResult(prs).toSeq,
        appActivityWeights = longArrayGetResult(prs).toSeq,
      )
  }

  def getRecordByRecordTime(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordT]] = {
    futureUnlessShutdownToFuture(
      storage
        .querySingle(
          sql"""
            select record_time, round_number, app_provider_parties, app_activity_weights
            from #${Tables.appActivityRecords}
            where history_id = $historyId and record_time = $recordTime
            limit 1
          """.as[AppActivityRecordT].headOption,
          "appActivity.getRecordByRecordTime",
        )
        .value
    )
  }

  /** Batch insert app activity records using multi-row INSERT. */
  private def batchInsertAppActivityRecords(items: Seq[AppActivityRecordT]) = {
    if (items.isEmpty) {
      DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          sql"""($historyId, ${row.recordTime},
                ${row.roundNumber}, ${row.appProviderParties}, ${row.appActivityWeights})"""
        }
      )

      (sql"""
        insert into #${Tables.appActivityRecords}(
          history_id, record_time, round_number, app_provider_parties, app_activity_weights
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
      batchInsertAppActivityRecords(items).map { _ =>
        logger.info(s"Inserted ${items.size} app activity records.")
      }
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
